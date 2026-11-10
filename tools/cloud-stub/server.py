#!/usr/bin/env python3
"""Reference stand-in for the cloud link (docs/cloud-protocol.md): standard library and SQLite only.

  ./server.py [--port 8790] [--db cloud-stub.sqlite]

Not a production server: no TLS (put a reverse proxy in front), one process.
"""
import argparse
import hashlib
import http.server
import json
import os
import secrets
import sqlite3
import threading
import time

PAIR_TTL_S = 300
ONLINE_S = 90
MAX_PARENTS = 8
MAX_QUEUE = 50
MAX_BODY = 16 * 1024
KEEP_S = 30 * 24 * 3600
WAIT_MAX_S = 30
FAIL_LIMIT, FAIL_WINDOW_S = 5, 300
RATE_PER_MIN = 60


def sha(token):
    return hashlib.sha256(token.encode()).hexdigest()


class Store:
    def __init__(self, path, clock=time.time):
        self.clock = clock
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.lock = threading.RLock()
        self.cond = threading.Condition(self.lock)
        self.fails = {}       # address -> [times]
        self.calls = {}       # token hash -> [times]
        with self.lock:
            self.db.executescript("""
            create table if not exists devices(id text primary key, name text, secret_hash text, code text, code_exp real,
                state text, rev integer default 0, last_seen real default 0, journal text default '[]', days text default '[]');
            create table if not exists parents(id text primary key, device_id text, name text, token_hash text);
            create table if not exists commands(seq integer primary key autoincrement, id text, device_id text, type text,
                args text, expires_at real, status text, detail text default '', created real);
            """)

    # -- helpers ---------------------------------------------------------------------------------
    def now(self):
        return self.clock()

    def _limited(self, key):
        t = self.now()
        hits = [x for x in self.calls.get(key, []) if t - x < 60]
        hits.append(t)
        self.calls[key] = hits
        return len(hits) > RATE_PER_MIN

    def device_of(self, token):
        if not token:
            return None
        row = self.db.execute("select id from devices where secret_hash=?", (sha(token),)).fetchone()
        return row[0] if row else None

    def parent_of(self, token):
        if not token:
            return None
        row = self.db.execute("select id, device_id, name from parents where token_hash=?", (sha(token),)).fetchone()
        return row

    def purge(self):
        cutoff = self.now() - KEEP_S
        self.db.execute("delete from commands where created < ?", (cutoff,))
        self.db.commit()

    # -- operations (each returns (status, body)) --------------------------------------------------
    def create_device(self, name):
        with self.lock:
            did, secret = secrets.token_hex(8), secrets.token_hex(32)
            self.db.execute("insert into devices(id, name, secret_hash) values(?,?,?)", (did, str(name)[:60], sha(secret)))
            self.db.commit()
            return 200, {"device_id": did, "device_secret": secret}

    def new_code(self, token):
        with self.lock:
            did = self.device_of(token)
            if not did:
                return 401, {"error": "unauthorized"}
            code = "%06d" % secrets.randbelow(1000000)
            self.db.execute("update devices set code=?, code_exp=? where id=?", (code, self.now() + PAIR_TTL_S, did))
            self.db.commit()
            return 200, {"code": code, "expires_in": PAIR_TTL_S}

    def pair(self, code, name, address):
        with self.lock:
            t = self.now()
            fails = [x for x in self.fails.get(address, []) if t - x < FAIL_WINDOW_S]
            if len(fails) >= FAIL_LIMIT:
                return 429, {"error": "too many attempts"}
            row = self.db.execute("select id from devices where code=? and code_exp>?", (str(code), t)).fetchone() if code else None
            if not row:
                fails.append(t)
                self.fails[address] = fails
                return 403, {"error": "wrong code"}
            did = row[0]
            count = self.db.execute("select count(*) from parents where device_id=?", (did,)).fetchone()[0]
            if count >= MAX_PARENTS:
                return 409, {"error": "too many phones"}
            token, pid = secrets.token_hex(32), secrets.token_hex(8)
            self.db.execute("update devices set code=null, code_exp=null where id=?", (did,))
            self.db.execute("insert into parents(id, device_id, name, token_hash) values(?,?,?,?)", (pid, did, str(name or "Parent")[:30], sha(token)))
            self.db.commit()
            self.fails.pop(address, None)
            return 200, {"parent_token": token, "parent_id": pid, "device_id": did}

    def revoke(self, token, parent_id):
        with self.lock:
            did, p = self.device_of(token), self.parent_of(token)
            row = self.db.execute("select device_id from parents where id=?", (parent_id,)).fetchone()
            if not row or not ((did and row[0] == did) or (p and p[0] == parent_id)):
                return (401, {"error": "unauthorized"}) if not (did or p) else (404, {"error": "not found"})
            self.db.execute("delete from parents where id=?", (parent_id,))
            self.db.commit()
            return 200, {}

    def delete_device(self, token, device_id):
        with self.lock:
            did = self.device_of(token)
            if not did:
                return 401, {"error": "unauthorized"}
            if did != device_id:
                return 404, {"error": "not found"}
            for table in ("parents", "commands"):
                self.db.execute("delete from %s where device_id=?" % table, (did,))
            self.db.execute("delete from devices where id=?", (did,))
            self.db.commit()
            return 200, {}

    def put_state(self, token, body):
        with self.lock:
            did = self.device_of(token)
            if not did:
                return 401, {"error": "unauthorized"}
            if self._limited(sha(token)):
                return 429, {"error": "rate limit"}
            if not isinstance(body, dict):
                return 400, {"error": "malformed"}
            journal = body.pop("journal", None)
            days = body.pop("days", None)
            self.db.execute("update devices set state=?, rev=rev+1, last_seen=? where id=?", (json.dumps(body), self.now(), did))
            if isinstance(journal, list):
                self.db.execute("update devices set journal=? where id=?", (json.dumps(journal[:200]), did))
            if isinstance(days, list):
                self.db.execute("update devices set days=? where id=?", (json.dumps(days[:30]), did))
            self.db.commit()
            return 200, {"rev": self.db.execute("select rev from devices where id=?", (did,)).fetchone()[0]}

    def add_command(self, token, body):
        with self.lock:
            p = self.parent_of(token)
            if not p:
                return 401, {"error": "unauthorized"}
            if self._limited(sha(token)):
                return 429, {"error": "rate limit"}
            kind = body.get("type") if isinstance(body, dict) else None
            args = body.get("args", {}) if isinstance(body, dict) else {}
            ttl = body.get("ttl_s", 600) if isinstance(body, dict) else 600
            if kind not in ("lock", "unlock", "message", "pause", "bonus", "power_off") or not isinstance(args, dict) or not isinstance(ttl, int) or not 10 <= ttl <= 3600:
                return 400, {"error": "malformed"}
            if kind == "message":
                text = args.get("text")
                if not isinstance(text, str) or not text.strip() or len(text) > 140:
                    return 400, {"error": "text"}
            if kind == "lock" and (not isinstance(args.get("note", ""), str) or len(args.get("note", "")) > 140):
                return 400, {"error": "note"}
            if kind == "bonus" and not (isinstance(args.get("minutes"), int) and 5 <= args["minutes"] <= 120):
                return 400, {"error": "minutes"}
            if kind == "power_off":
                delay = args.get("delay_s", 0)
                if not isinstance(delay, int) or isinstance(delay, bool) or not 0 <= delay <= 86400 or not isinstance(args.get("cancel", False), bool):
                    return 400, {"error": "delay_s"}
            if kind == "unlock":
                args = {"by": p[2]}
            elif kind in ("lock", "message", "bonus", "power_off"):
                args = dict(args, by=p[2])
            did, now = p[1], self.now()
            cid = secrets.token_hex(8)
            self.db.execute("insert into commands(id, device_id, type, args, expires_at, status, created) values(?,?,?,?,?,?,?)",
                            (cid, did, kind, json.dumps(args), now + ttl, "queued", now))
            queued = [r[0] for r in self.db.execute("select seq from commands where device_id=? and status in ('queued','delivered') order by seq", (did,))]
            for seq in queued[:-MAX_QUEUE]:
                self.db.execute("update commands set status='expired', detail='queue full' where seq=?", (seq,))
            self.db.commit()
            self.cond.notify_all()
            return 200, {"id": cid, "expires_at": int((now + ttl) * 1000)}

    def command_status(self, token, cid):
        with self.lock:
            p = self.parent_of(token)
            if not p:
                return 401, {"error": "unauthorized"}
            self._expire()
            row = self.db.execute("select status, detail from commands where id=? and device_id=?", (cid, p[1])).fetchone()
            return (200, {"status": row[0], "detail": row[1]}) if row else (404, {"error": "not found"})

    def _expire(self):
        self.db.execute("update commands set status='expired' where status in ('queued','delivered') and expires_at<=?", (self.now(),))

    def poll(self, token, since, wait):
        deadline = self.now() + min(max(wait, 0), WAIT_MAX_S)
        with self.cond:
            did = self.device_of(token)
            if not did:
                return 401, {"error": "unauthorized"}
            if self._limited(sha(token)):
                return 429, {"error": "rate limit"}
            while True:
                self._expire()
                self.db.execute("update devices set last_seen=? where id=?", (self.now(), did))
                rows = self.db.execute("select seq, id, type, args, expires_at from commands where device_id=? and seq>? and status in ('queued','delivered') order by seq",
                                       (did, since)).fetchall()
                if rows:
                    for r in rows:
                        self.db.execute("update commands set status='delivered' where seq=? and status='queued'", (r[0],))
                    self.db.commit()
                    return 200, {"commands": [{"seq": r[0], "id": r[1], "type": r[2], "args": json.loads(r[3]), "expires_at": int(r[4] * 1000)} for r in rows],
                                 "seq": rows[-1][0]}
                left = deadline - self.now()
                if left <= 0:
                    self.db.commit()
                    return 200, {"commands": [], "seq": since}
                self.cond.wait(min(left, 1.0))

    def ack(self, token, body):
        with self.lock:
            did = self.device_of(token)
            if not did:
                return 401, {"error": "unauthorized"}
            result = body.get("result") if isinstance(body, dict) else None
            if result not in ("ok", "failed", "expired") or not isinstance(body.get("id"), str):
                return 400, {"error": "malformed"}
            cur = self.db.execute("update commands set status=?, detail=? where id=? and device_id=?",
                                  (result, str(body.get("detail", ""))[:80], body["id"], did))
            self.db.commit()
            return (200, {}) if cur.rowcount else (404, {"error": "not found"})

    def read(self, token, what):
        with self.lock:
            p = self.parent_of(token)
            if not p:
                return 401, {"error": "unauthorized"}
            row = self.db.execute("select state, rev, last_seen, journal, days from devices where id=?", (p[1],)).fetchone()
            if not row:
                return 404, {"error": "not found"}
            if what == "state":
                state = json.loads(row[0]) if row[0] else {}
                return 200, dict(state, rev=row[1], last_seen=int(row[2] * 1000), online=self.now() - row[2] <= ONLINE_S and row[2] > 0)
            if what == "journal":
                return 200, {"events": json.loads(row[3])}
            return 200, {"days": json.loads(row[4])}


class Handler(http.server.BaseHTTPRequestHandler):
    store = None

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
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
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _route(self, method):
        if self.headers.get("X-Protocol") != "1":
            return self._send(400, {"error": "X-Protocol: 1 required"})
        path, _, query = self.path.partition("?")
        args = dict(p.split("=", 1) for p in query.split("&") if "=" in p)
        s, tok = self.store, self._token()
        body = {}
        if method == "POST":
            body, err = self._body()
            if err:
                return self._send(*err)
        parts = path.strip("/").split("/")
        if parts[:1] != ["v1"]:
            return self._send(404, {"error": "not found"})
        r = parts[1:]
        try:
            if method == "POST" and r == ["devices"]:
                return self._send(*s.create_device(body.get("name", "TV")))
            if method == "POST" and r == ["pair", "code"]:
                return self._send(*s.new_code(tok))
            if method == "POST" and r == ["pair"]:
                return self._send(*s.pair(body.get("code"), body.get("name"), self.client_address[0]))
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
                return self._send(*s.poll(tok, int(args.get("since", 0)), int(args.get("wait", 25))))
            if method == "POST" and r == ["device", "ack"]:
                return self._send(*s.ack(tok, body))
            if method == "GET" and len(r) == 2 and r[0] == "device" and r[1] in ("state", "journal", "days"):
                return self._send(*s.read(tok, r[1]))
        except ValueError:
            return self._send(400, {"error": "malformed"})
        self._send(404, {"error": "not found"})

    def do_GET(self):
        self._route("GET")

    def do_POST(self):
        self._route("POST")

    def do_DELETE(self):
        self._route("DELETE")

    def log_message(self, *a):
        pass


def make_server(db, port=0, clock=time.time):
    Handler.store = Store(db, clock)
    return http.server.ThreadingHTTPServer(("127.0.0.1" if port == 0 else "0.0.0.0", port), Handler)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8790)
    ap.add_argument("--db", default="cloud-stub.sqlite")
    a = ap.parse_args()
    make_server(a.db, a.port).serve_forever()
