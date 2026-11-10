#!/usr/bin/env python3
"""MyTV cloud link server: implements docs/cloud-protocol.md version 1.

Python standard library and SQLite only. It speaks plain HTTP on 127.0.0.1 and is meant to sit behind a
reverse proxy that terminates TLS (see cloud/README.md).

  ./server.py [--host 127.0.0.1] [--port 8791] [--db PATH] [--trust-proxy] [--bot-key-file PATH]

Every option also has an environment variable (MYTV_CLOUD_HOST, MYTV_CLOUD_PORT, MYTV_CLOUD_DB,
MYTV_CLOUD_TRUST_PROXY=1, MYTV_CLOUD_BOT_KEY_FILE).
"""
import argparse
import hashlib
import hmac
import http.server
import json
import logging
import os
import re
import secrets
import socketserver
import sqlite3
import sys
import threading
import time
import urllib.parse

PROTOCOL = "1"
PAIR_TTL_S = 300
ONLINE_S = 90
MAX_PARENTS = 8                           # paired phones per TV, requests waiting for approval included
MAX_PENDING = 3                           # requests waiting for the owner, per TV
PENDING_TTL_S = 24 * 3600                 # an unanswered request is forgotten after a day
MAX_QUEUE = 50
MAX_BODY = 16 * 1024
KEEP_S = 30 * 24 * 3600
WAIT_MAX_S = 30
FAIL_LIMIT, FAIL_WINDOW_S = 5, 300       # wrong pairing codes per client
RATE_PER_MIN = 60                         # calls per token per minute
NEW_DEVICES_PER_HOUR = 10                 # POST /v1/devices per address
JOURNAL_MAX = 200
JOURNAL_PER_POST = 20
DAYS_MAX = 30
COMMAND_TYPES = ("lock", "unlock", "message", "pause", "bonus", "power_off")
DEFAULT_BOT_KEY_FILE = os.path.expanduser("~/.config/mytv-bot/cloud-key")

log = logging.getLogger("mytv-cloud")


def sha(token):
    return hashlib.sha256(token.encode()).hexdigest()


def clip(value, n):
    return str(value)[:n]


def is_int(value):
    return isinstance(value, int) and not isinstance(value, bool)


SCHEMA = """
create table if not exists devices(
    id text primary key, name text not null, secret_hash text not null unique,
    code text, code_exp real, state text, rev integer not null default 0,
    last_seen real not null default 0, days text not null default '[]', created real not null);
create index if not exists devices_code on devices(code);
create table if not exists parents(
    id text primary key, device_id text not null, name text not null, token_hash text not null unique, created real not null,
    owner integer not null default 0, status text not null default 'active');
create index if not exists parents_device on parents(device_id);
create table if not exists commands(
    seq integer primary key autoincrement, id text not null unique, device_id text not null, type text not null,
    args text not null, expires_at real not null, status text not null, detail text not null default '', created real not null);
create index if not exists commands_device on commands(device_id, seq);
create table if not exists events(
    id integer primary key autoincrement, device_id text not null, t integer not null, kind text not null, text text not null,
    unique(device_id, t, kind, text));
create index if not exists events_device on events(device_id, t);
"""


class Store:
    """All state and rules. Every public operation returns (http_status, json_body)."""

    def __init__(self, path, clock=time.time, bot_key=None):
        self.clock = clock
        self.bot_key = bot_key or None
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.execute("pragma journal_mode=wal")
        self.db.execute("pragma synchronous=normal")
        self.lock = threading.RLock()
        self.cond = threading.Condition(self.lock)
        self.fails = {}      # pairing client -> [times of wrong codes]
        self.calls = {}      # token hash -> [times]
        self.creations = {}  # address -> [times]
        with self.lock:
            self.db.executescript(SCHEMA)
            if "owner" not in [r[1] for r in self.db.execute("pragma table_info(parents)")]:   # a database from before owners existed
                self.db.execute("alter table parents add column owner integer not null default 0")
                self.db.execute("alter table parents add column status text not null default 'active'")
                self.db.execute("update parents set owner=1 where rowid in (select min(rowid) from parents group by device_id)")
            self.db.commit()
        if os.path.exists(path):
            os.chmod(path, 0o600)

    # -- helpers ---------------------------------------------------------------------------------
    def now(self):
        return self.clock()

    def _recent(self, table, key, window, add=True):
        t = self.now()
        hits = [x for x in table.get(key, []) if t - x < window]
        if add:
            hits.append(t)
        if hits:
            table[key] = hits
        else:
            table.pop(key, None)
        return hits

    def _limited(self, token):
        return len(self._recent(self.calls, sha(token), 60)) > RATE_PER_MIN

    def device_of(self, token):
        if not token:
            return None
        row = self.db.execute("select id from devices where secret_hash=?", (sha(token),)).fetchone()
        return row[0] if row else None

    def parent_of(self, token):
        """(parent_id, device_id, name, is_owner, status) or None. A request nobody answered for a day does not count."""
        if not token:
            return None
        return self.db.execute("select id, device_id, name, owner, status from parents where token_hash=? and not (status='pending' and created<?)",
                               (sha(token), self.now() - PENDING_TTL_S)).fetchone()

    def _active_parent(self, token):
        """(parent, None) for a parent who may act, else (None, (status, body)): 401 unknown, 403 still waiting for the owner."""
        p = self.parent_of(token)
        if not p:
            return None, (401, {"error": "unauthorized"})
        if p[4] != "active":
            return None, (403, {"error": "pending approval"})
        return p, None

    def _fix_owner(self, did):
        """After a parent left: the oldest remaining parent becomes the owner; with nobody left, waiting requests are dropped."""
        if self.db.execute("select 1 from parents where device_id=? and owner=1 and status='active'", (did,)).fetchone():
            return
        nxt = self.db.execute("select id from parents where device_id=? and status='active' order by rowid limit 1", (did,)).fetchone()
        if nxt:
            self.db.execute("update parents set owner=1 where id=?", (nxt[0],))
        else:
            self.db.execute("delete from parents where device_id=?", (did,))

    def _expire(self):
        self.db.execute("update commands set status='expired' where status in ('queued','delivered') and expires_at<=?", (self.now(),))

    def _trim_events(self, did):
        self.db.execute("delete from events where device_id=? and id not in "
                        "(select id from events where device_id=? order by t desc, id desc limit ?)", (did, did, JOURNAL_MAX))

    def purge(self):
        """Delete everything older than 30 days; forget abandoned registrations."""
        with self.lock:
            t = self.now()
            cutoff = t - KEEP_S
            self.db.execute("delete from commands where created < ?", (cutoff,))
            self.db.execute("delete from parents where status='pending' and created < ?", (t - PENDING_TTL_S,))
            self.db.execute("delete from events where t < ?", (int(cutoff * 1000),))
            day = time.strftime("%Y-%m-%d", time.gmtime(cutoff))
            for did, days in self.db.execute("select id, days from devices where days != '[]'").fetchall():
                kept = [d for d in json.loads(days) if isinstance(d, dict) and str(d.get("day", "")) >= day]
                self.db.execute("update devices set days=? where id=?", (json.dumps(kept), did))
            # a TV silent for 30 days keeps its pairing but not what it reported
            self.db.execute("update devices set state=null, days='[]' where last_seen > 0 and last_seen < ?", (cutoff,))
            self.db.execute("update devices set code=null, code_exp=null where code_exp is not null and code_exp < ?", (t,))
            # registered, never heard from and nobody paired for 30 days: abandoned
            self.db.execute("delete from devices where last_seen = 0 and created < ? and id not in (select device_id from parents)", (cutoff,))
            self.db.execute("delete from commands where device_id not in (select id from devices)")
            self.db.execute("delete from events where device_id not in (select id from devices)")
            for table in (self.calls, self.fails, self.creations):
                for key in list(table):
                    self._recent(table, key, 3600, add=False)
            self.db.commit()

    # -- pairing ---------------------------------------------------------------------------------
    def create_device(self, name, address):
        with self.lock:
            if len(self._recent(self.creations, address, 3600)) > NEW_DEVICES_PER_HOUR:
                return 429, {"error": "too many attempts"}
            name = name.strip() if isinstance(name, str) else ""
            did, secret = secrets.token_hex(8), secrets.token_hex(32)
            self.db.execute("insert into devices(id, name, secret_hash, created) values(?,?,?,?)",
                            (did, clip(name or "TV", 60), sha(secret), self.now()))
            self.db.commit()
            return 200, {"device_id": did, "device_secret": secret}

    def new_code(self, token):
        with self.lock:
            did = self.device_of(token)
            if not did:
                return 401, {"error": "unauthorized"}
            if self._limited(token):
                return 429, {"error": "rate limit"}
            while True:  # live codes are unique, so a code never pairs with the wrong TV
                code = "%06d" % secrets.randbelow(1000000)
                if not self.db.execute("select 1 from devices where code=? and code_exp>? and id!=?", (code, self.now(), did)).fetchone():
                    break
            self.db.execute("update devices set code=?, code_exp=? where id=?", (code, self.now() + PAIR_TTL_S, did))
            self.db.commit()
            return 200, {"code": code, "expires_in": PAIR_TTL_S}

    def pair(self, code, name, client):
        with self.lock:
            t = self.now()
            fails = self._recent(self.fails, client, FAIL_WINDOW_S, add=False)
            if len(fails) >= FAIL_LIMIT:
                return 429, {"error": "too many attempts"}
            row = None
            if isinstance(code, str) and re.fullmatch(r"\d{6}", code):
                row = self.db.execute("select id, name from devices where code=? and code_exp>?", (code, t)).fetchone()
            if not row:
                self._recent(self.fails, client, FAIL_WINDOW_S)
                return 403, {"error": "wrong code"}
            did, device_name = row
            self.db.execute("delete from parents where device_id=? and status='pending' and created<?", (did, t - PENDING_TTL_S))
            rows = self.db.execute("select owner, status from parents where device_id=?", (did,)).fetchall()
            if len(rows) >= MAX_PARENTS:
                return 409, {"error": "too many phones"}
            # the first phone to pair owns the TV; every later one waits until the owner allows it
            owned = any(r[0] for r in rows)
            if owned and sum(1 for r in rows if r[1] == "pending") >= MAX_PENDING:
                return 409, {"error": "too many requests"}
            status = "pending" if owned else "active"
            parent_name = name.strip() if isinstance(name, str) else ""
            token, pid = secrets.token_hex(32), secrets.token_hex(8)
            self.db.execute("update devices set code=null, code_exp=null where id=?", (did,))
            self.db.execute("insert into parents(id, device_id, name, token_hash, created, owner, status) values(?,?,?,?,?,?,?)",
                            (pid, did, clip(parent_name or "Parent", 30), sha(token), t, 0 if owned else 1, status))
            self.db.commit()
            self.fails.pop(client, None)
            return 200, {"parent_token": token, "parent_id": pid, "device_id": did, "device_name": device_name,
                         "status": status, "owner": not owned}

    def revoke(self, token, parent_id):
        with self.lock:
            did, p = self.device_of(token), self.parent_of(token)
            if not (did or p):
                return 401, {"error": "unauthorized"}
            row = self.db.execute("select device_id from parents where id=?", (parent_id,)).fetchone()
            if not row:
                return 404, {"error": "not found"}
            # the TV may remove anyone, a phone may remove itself (a waiting request included), the owner may remove anyone on its TV
            if not ((did and row[0] == did) or (p and p[0] == parent_id) or (p and p[4] == "active" and p[3] and p[1] == row[0])):
                if p and p[1] == row[0]:
                    return 403, {"error": "owner only"}
                return 404, {"error": "not found"}
            self.db.execute("delete from parents where id=?", (parent_id,))
            self._fix_owner(row[0])
            self.db.commit()
            return 200, {}

    def list_parents(self, token):
        with self.lock:
            did = self.device_of(token)
            if did:   # the TV lists everyone, waiting requests included, so its owner can remove a phone whose account is lost
                if self._limited(token):
                    return 429, {"error": "rate limit"}
                rows = self.db.execute("select id, name, owner, status from parents where device_id=? and not (status='pending' and created<?) order by rowid",
                                       (did, self.now() - PENDING_TTL_S)).fetchall()
                return 200, {"parents": [{"id": r[0], "name": r[1], "owner": bool(r[2]), "status": r[3]} for r in rows]}
            p, err = self._active_parent(token)
            if err:
                return err
            if self._limited(token):
                return 429, {"error": "rate limit"}
            rows = self.db.execute("select id, name, owner, status from parents where device_id=? and not (status='pending' and created<?) order by rowid",
                                   (p[1], self.now() - PENDING_TTL_S)).fetchall()
            return 200, {"you": p[0], "owner": bool(p[3]), "parents": [
                {"id": r[0], "name": r[1], "owner": bool(r[2]), "status": r[3]} for r in rows if r[3] == "active" or p[3]]}

    def approve(self, token, parent_id):
        with self.lock:
            p, err = self._active_parent(token)
            if err:
                return err
            if self._limited(token):
                return 429, {"error": "rate limit"}
            if not p[3]:
                return 403, {"error": "owner only"}
            cur = self.db.execute("update parents set status='active' where id=? and device_id=? and status='pending' and created>=?",
                                  (parent_id, p[1], self.now() - PENDING_TTL_S))
            self.db.commit()
            return (200, {}) if cur.rowcount else (404, {"error": "not found"})

    def delete_device(self, token, device_id):
        with self.lock:
            did = self.device_of(token)
            if not did:
                return 401, {"error": "unauthorized"}
            if did != device_id:
                return 404, {"error": "not found"}
            for table in ("parents", "commands", "events"):
                self.db.execute("delete from %s where device_id=?" % table, (did,))
            self.db.execute("delete from devices where id=?", (did,))
            self.db.commit()
            return 200, {}

    # -- the TV ----------------------------------------------------------------------------------
    @staticmethod
    def _clean_state(body):
        state = {}
        for key in ("locked", "scheduled", "service"):
            if isinstance(body.get(key), bool):
                state[key] = body[key]
        screen = body.get("screen")
        if isinstance(screen, dict):
            clean = {}
            if isinstance(screen.get("on"), bool):
                clean["on"] = screen["on"]
            if is_int(screen.get("since")):
                clean["since"] = screen["since"]
            for key, n in (("pkg", 120), ("label", 60)):
                if isinstance(screen.get(key), str):
                    clean[key] = screen[key][:n]
            state["screen"] = clean
        return state

    def put_state(self, token, body):
        with self.lock:
            did = self.device_of(token)
            if not did:
                return 401, {"error": "unauthorized"}
            if self._limited(token):
                return 429, {"error": "rate limit"}
            if not isinstance(body, dict):
                return 400, {"error": "malformed"}
            t = self.now()
            self.db.execute("update devices set state=?, rev=rev+1, last_seen=? where id=?", (json.dumps(self._clean_state(body)), t, did))
            journal = body.get("journal")
            if isinstance(journal, list):
                for ev in journal[:JOURNAL_PER_POST]:
                    if (isinstance(ev, dict) and is_int(ev.get("t")) and 0 < ev["t"] <= (t + 86400) * 1000
                            and isinstance(ev.get("kind"), str) and isinstance(ev.get("text", ""), str)):
                        self.db.execute("insert or ignore into events(device_id, t, kind, text) values(?,?,?,?)",
                                        (did, ev["t"], clip(ev["kind"], 32), clip(ev.get("text", ""), 200)))
                self._trim_events(did)
            days = body.get("days")
            if isinstance(days, list):
                kept = [d for d in days[:DAYS_MAX] if isinstance(d, dict)]
                self.db.execute("update devices set days=? where id=?", (json.dumps(kept), did))
            self.db.commit()
            return 200, {"rev": self.db.execute("select rev from devices where id=?", (did,)).fetchone()[0]}

    def poll(self, token, since, wait):
        with self.cond:
            deadline = self.now() + min(max(wait, 0), WAIT_MAX_S)
            did = self.device_of(token)
            if not did:
                return 401, {"error": "unauthorized"}
            if self._limited(token):
                return 429, {"error": "rate limit"}
            while True:
                self._expire()
                self.db.execute("update devices set last_seen=? where id=?", (self.now(), did))
                rows = self.db.execute("select seq, id, type, args, expires_at from commands where device_id=? and seq>? "
                                       "and status in ('queued','delivered') order by seq", (did, since)).fetchall()
                if rows:
                    for r in rows:
                        self.db.execute("update commands set status='delivered' where seq=? and status='queued'", (r[0],))
                    self.db.commit()
                    return 200, {"commands": [{"seq": r[0], "id": r[1], "type": r[2], "args": json.loads(r[3]),
                                               "expires_at": int(r[4] * 1000)} for r in rows], "seq": rows[-1][0]}
                self.db.commit()
                left = deadline - self.now()
                if left <= 0:
                    return 200, {"commands": [], "seq": since}
                self.cond.wait(min(left, 5.0))

    def ack(self, token, body):
        with self.lock:
            did = self.device_of(token)
            if not did:
                return 401, {"error": "unauthorized"}
            if self._limited(token):
                return 429, {"error": "rate limit"}
            if not isinstance(body, dict) or body.get("result") not in ("ok", "failed", "expired") or not isinstance(body.get("id"), str):
                return 400, {"error": "malformed"}
            row = self.db.execute("select status from commands where id=? and device_id=?", (body["id"], did)).fetchone()
            if not row:
                return 404, {"error": "not found"}
            if row[0] in ("queued", "delivered", "expired"):  # the TV's word beats the server's clock, but the first final answer stands
                self.db.execute("update commands set status=?, detail=? where id=?", (body["result"], clip(body.get("detail", ""), 80), body["id"]))
                self.db.commit()
            return 200, {}

    # -- parents ---------------------------------------------------------------------------------
    @staticmethod
    def _clean_command(kind, args, by):
        """Returns (clean args, error) for the arguments of one command type."""
        if kind == "message":
            text = args.get("text")
            if not isinstance(text, str) or not text.strip() or len(text) > 140:
                return None, "text"
            return {"text": text, "by": by}, None
        if kind == "lock":
            note = args.get("note", "")
            if not isinstance(note, str) or len(note) > 140:
                return None, "note"
            return ({"note": note, "by": by} if note else {"by": by}), None
        if kind == "bonus":
            minutes = args.get("minutes")
            if not is_int(minutes) or not 5 <= minutes <= 120:
                return None, "minutes"
            return {"minutes": minutes, "by": by}, None
        if kind == "power_off":
            cancel, delay = args.get("cancel", False), args.get("delay_s", 0)
            if not isinstance(cancel, bool) or not is_int(delay) or not 0 <= delay <= 86400:
                return None, "delay_s"
            return ({"cancel": True, "by": by} if cancel else {"delay_s": delay, "by": by}), None
        return {"by": by}, None  # unlock, pause

    def add_command(self, token, body):
        with self.lock:
            p, err = self._active_parent(token)
            if err:
                return err
            if self._limited(token):
                return 429, {"error": "rate limit"}
            if not isinstance(body, dict):
                return 400, {"error": "malformed"}
            kind, args, ttl = body.get("type"), body.get("args", {}), body.get("ttl_s", 600)
            if kind not in COMMAND_TYPES or not isinstance(args, dict) or not is_int(ttl) or not 10 <= ttl <= 3600:
                return 400, {"error": "malformed"}
            args, error = self._clean_command(kind, args, p[2])
            if error:
                return 400, {"error": error}
            did, now = p[1], self.now()
            cid = secrets.token_hex(8)
            self.db.execute("insert into commands(id, device_id, type, args, expires_at, status, created) values(?,?,?,?,?,?,?)",
                            (cid, did, kind, json.dumps(args), now + ttl, "queued", now))
            queued = [r[0] for r in self.db.execute("select seq from commands where device_id=? and status in ('queued','delivered') order by seq", (did,))]
            for seq in queued[:-MAX_QUEUE]:
                self.db.execute("update commands set status='expired', detail='queue full' where seq=?", (seq,))
            if kind == "unlock":  # decision 1: no PIN in the cloud, so every unlock is recorded with who asked
                self.db.execute("insert or ignore into events(device_id, t, kind, text) values(?,?,?,?)",
                                (did, int(now * 1000), "cloud_unlock", "unlock requested by " + p[2]))
                self._trim_events(did)
            self.db.commit()
            self.cond.notify_all()
            return 200, {"id": cid, "expires_at": int((now + ttl) * 1000)}

    def command_status(self, token, cid):
        with self.lock:
            p, err = self._active_parent(token)
            if err:
                return err
            if self._limited(token):
                return 429, {"error": "rate limit"}
            self._expire()
            self.db.commit()
            row = self.db.execute("select status, detail from commands where id=? and device_id=?", (cid, p[1])).fetchone()
            return (200, {"status": row[0], "detail": row[1]}) if row else (404, {"error": "not found"})

    def read(self, token, what):
        with self.lock:
            p, err = self._active_parent(token)
            if err:
                return err
            if self._limited(token):
                return 429, {"error": "rate limit"}
            row = self.db.execute("select name, state, rev, last_seen, days from devices where id=?", (p[1],)).fetchone()
            if not row:
                return 404, {"error": "not found"}
            if what == "state":
                state = json.loads(row[1]) if row[1] else {}
                online = row[3] > 0 and self.now() - row[3] <= ONLINE_S
                return 200, dict(state, name=row[0], rev=row[2], last_seen=int(row[3] * 1000), online=online)
            if what == "journal":
                events = self.db.execute("select t, kind, text from events where device_id=? order by t desc, id desc limit ?", (p[1], JOURNAL_MAX))
                return 200, {"events": [{"t": r[0], "kind": r[1], "text": r[2]} for r in events]}
            return 200, {"days": json.loads(row[4])}

    # -- pairing client identity -------------------------------------------------------------------
    def pair_client(self, address, bot_key, subject):
        """A trusted bot serves many families from one address, so its pairing limit is per chat, not per address."""
        if self.bot_key and bot_key and hmac.compare_digest(self.bot_key, bot_key) and re.fullmatch(r"[A-Za-z0-9:_-]{1,64}", subject or ""):
            return "bot:" + subject
        return "ip:" + address


class ThreadingServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True
    request_queue_size = 128   # many TVs may reconnect at once after a restart


class Handler(http.server.BaseHTTPRequestHandler):
    store = None
    trust_proxy = False
    server_version = "mytv-cloud"
    sys_version = ""
    timeout = 60   # a client that stalls while sending is dropped; a long poll waits on the server side

    def _address(self):
        if self.trust_proxy:
            real = (self.headers.get("X-Real-IP") or "").strip()
            if real:
                return real[:64]
        return self.client_address[0]

    def _body(self):
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            return None, (400, {"error": "malformed"})
        if n < 0:
            return None, (400, {"error": "malformed"})
        if n > MAX_BODY:
            return None, (413, {"error": "too large"})
        raw = self.rfile.read(n) if n else b""
        try:
            return (json.loads(raw) if raw else {}), None
        except ValueError:
            return None, (400, {"error": "malformed"})

    def _token(self):
        h = self.headers.get("Authorization") or ""
        return h[7:].strip() if h[:7].lower() == "bearer " else ""

    def _send(self, status, body):
        self.status = status
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        if status == 429:
            self.send_header("Retry-After", "60")
        self.end_headers()
        self.wfile.write(data)

    def _route(self, method):
        url = urllib.parse.urlsplit(self.path)
        parts = url.path.strip("/").split("/")
        if parts[:1] != ["v1"]:
            return self._send(404, {"error": "not found"})
        r = parts[1:]
        if method == "GET" and r == ["health"]:
            return self._send(200, {"ok": True})
        if self.headers.get("X-Protocol") != PROTOCOL:
            return self._send(400, {"error": "X-Protocol: 1 required"})
        query = dict(urllib.parse.parse_qsl(url.query))
        s, tok = self.store, self._token()
        body = {}
        if method == "POST":
            body, err = self._body()
            if err:
                return self._send(*err)
        try:
            if method == "POST" and r == ["devices"]:
                return self._send(*s.create_device(body.get("name") if isinstance(body, dict) else None, self._address()))
            if method == "POST" and r == ["pair", "code"]:
                return self._send(*s.new_code(tok))
            if method == "POST" and r == ["pair"]:
                if not isinstance(body, dict):
                    return self._send(400, {"error": "malformed"})
                client = s.pair_client(self._address(), self.headers.get("X-Bot-Key"), self.headers.get("X-Subject"))
                return self._send(*s.pair(body.get("code"), body.get("name"), client))
            if method == "GET" and r == ["parents"]:
                return self._send(*s.list_parents(tok))
            if method == "POST" and len(r) == 3 and r[0] == "parents" and r[2] == "approve":
                return self._send(*s.approve(tok, r[1]))
            if method == "DELETE" and len(r) == 2 and r[0] == "parents":
                return self._send(*s.revoke(tok, r[1]))
            if method == "DELETE" and len(r) == 2 and r[0] == "devices":
                return self._send(*s.delete_device(tok, r[1]))
            if method == "POST" and r == ["device", "state"]:
                return self._send(*s.put_state(tok, body))
            if method == "POST" and r == ["commands"]:
                return self._send(*s.add_command(tok, body))
            if method == "GET" and len(r) == 2 and r[0] == "commands":
                return self._send(*s.command_status(tok, r[1]))
            if method == "GET" and r == ["device", "poll"]:
                return self._send(*s.poll(tok, int(query.get("since", 0)), int(query.get("wait", 25))))
            if method == "POST" and r == ["device", "ack"]:
                return self._send(*s.ack(tok, body))
            if method == "GET" and len(r) == 2 and r[0] == "device" and r[1] in ("state", "journal", "days"):
                return self._send(*s.read(tok, r[1]))
        except ValueError:
            return self._send(400, {"error": "malformed"})
        except sqlite3.Error:
            log.exception("database error")
            return self._send(503, {"error": "unavailable"})
        self._send(404, {"error": "not found"})

    def _handle(self, method):
        self.status = 0
        try:
            self._route(method)
        except (BrokenPipeError, ConnectionResetError):
            return
        except Exception:
            log.exception("unhandled error")
            try:
                self._send(503, {"error": "unavailable"})
            except OSError:
                pass

    def do_GET(self):
        self._handle("GET")

    def do_POST(self):
        self._handle("POST")

    def do_DELETE(self):
        self._handle("DELETE")

    def log_request(self, code="-", size="-"):
        pass

    def log_message(self, fmt, *args):
        pass

    def finish(self):
        # one access line per request: method, path without the query, status; never headers or bodies
        if getattr(self, "status", 0):
            log.info("%s %s %s", self.command, self.path.partition("?")[0], self.status)
        super().finish()


def read_key(path):
    try:
        with open(path) as f:
            return f.read().strip() or None
    except OSError:
        return None


def make_server(db, host="127.0.0.1", port=0, clock=time.time, trust_proxy=False, bot_key=None):
    """Build a server; the store is reachable as httpd.store. Port 0 picks a free one."""
    store = Store(db, clock, bot_key)
    handler = type("BoundHandler", (Handler,), {"store": store, "trust_proxy": trust_proxy})
    httpd = ThreadingServer((host, port), handler)
    httpd.store = store
    return httpd


def purge_forever(store, every=3600):
    while True:
        time.sleep(every)
        try:
            store.purge()
        except Exception:
            log.exception("purge failed")


def main(argv=None):
    env = os.environ.get
    ap = argparse.ArgumentParser(description="MyTV cloud link server (docs/cloud-protocol.md v1)")
    ap.add_argument("--host", default=env("MYTV_CLOUD_HOST", "127.0.0.1"))
    ap.add_argument("--port", type=int, default=int(env("MYTV_CLOUD_PORT", "8791")))
    ap.add_argument("--db", default=env("MYTV_CLOUD_DB", os.path.expanduser("~/.local/share/mytv-cloud/cloud.sqlite")))
    ap.add_argument("--trust-proxy", action="store_true", default=env("MYTV_CLOUD_TRUST_PROXY") == "1",
                    help="take the client address from X-Real-IP (set it only behind a proxy you control)")
    ap.add_argument("--bot-key-file", default=env("MYTV_CLOUD_BOT_KEY_FILE", DEFAULT_BOT_KEY_FILE))
    a = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s", stream=sys.stderr)
    os.makedirs(os.path.dirname(os.path.abspath(a.db)), mode=0o700, exist_ok=True)
    httpd = make_server(a.db, a.host, a.port, trust_proxy=a.trust_proxy, bot_key=read_key(a.bot_key_file))
    httpd.store.purge()
    threading.Thread(target=purge_forever, args=(httpd.store,), daemon=True).start()
    log.info("listening on %s:%d, proxy trusted: %s, bot key: %s", a.host, a.port, a.trust_proxy, "yes" if httpd.store.bot_key else "no")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
