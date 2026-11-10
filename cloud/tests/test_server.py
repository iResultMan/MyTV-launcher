import http.client
import json
import sqlite3
import threading
import time
import unittest

import server
from tests.fake_tv import CloudHarness, FakeTV


class ServerTest(unittest.TestCase):
    bot_key = None

    def setUp(self):
        self.h = CloudHarness()
        self.h.start(bot_key=self.bot_key)
        self.clock = self.h.clock
        self.call = self.h.call

    def tearDown(self):
        self.h.stop()

    def paired(self, name="Mama", tv_name="TV"):
        tv = FakeTV(self.h, tv_name)
        s, p = self.call("POST", "/v1/pair", {"code": tv.code(), "name": name})
        self.assertEqual(200, s)
        return tv, p["parent_token"], p["parent_id"]


    def join(self, tv, owner_token, name, approve=False):
        """A further phone pairs with a TV that already has an owner; returns (token, id)."""
        s, p = self.call("POST", "/v1/pair", {"code": tv.code(), "name": name})
        self.assertEqual((200, "pending", False), (s, p["status"], p["owner"]))
        if approve:
            self.assertEqual(200, self.call("POST", "/v1/parents/%s/approve" % p["parent_id"], token=owner_token)[0])
        return p["parent_token"], p["parent_id"]

    def pair_status(self, tv, name):
        s, p = self.call("POST", "/v1/pair", {"code": tv.code(), "name": name})
        return s, p.get("error")


class ProtocolTest(ServerTest):
    def test_protocol_header_is_required(self):
        self.assertEqual(400, self.call("POST", "/v1/devices", {"name": "x"}, protocol=None)[0])
        self.assertEqual(400, self.call("POST", "/v1/devices", {"name": "x"}, protocol="2")[0])

    def test_health_needs_no_header_and_unknown_paths_are_404(self):
        self.assertEqual((200, {"ok": True}), self.call("GET", "/v1/health", protocol=None))
        self.assertEqual(404, self.call("GET", "/v1/nothing")[0])
        self.assertEqual(404, self.call("GET", "/other", protocol=None)[0])

    def test_oversized_and_malformed_bodies(self):
        for body, want in ((b"{not json", 400), (b"x" * (server.MAX_BODY + 1), 413), (b"[1]", 400)):
            c = http.client.HTTPConnection("127.0.0.1", self.h.port, timeout=10)
            c.request("POST", "/v1/pair", body=body, headers={"X-Protocol": "1"})
            self.assertEqual(want, c.getresponse().status, body[:10])
            c.close()
        c = http.client.HTTPConnection("127.0.0.1", self.h.port, timeout=10)
        c.request("POST", "/v1/devices", headers={"X-Protocol": "1", "Content-Length": "abc"})
        self.assertEqual(400, c.getresponse().status)
        c.close()

    def test_bad_query_values_are_400(self):
        tv = FakeTV(self.h)
        self.assertEqual(400, self.call("GET", "/v1/device/poll?since=x", token=tv.secret)[0])

    def test_calls_without_the_right_token_get_401(self):
        tv, parent, pid = self.paired()
        self.assertEqual(401, self.call("POST", "/v1/device/state", {}, token=None)[0])
        self.assertEqual(401, self.call("POST", "/v1/device/state", {}, token=parent)[0])      # a parent is not the TV
        self.assertEqual(401, self.call("POST", "/v1/commands", {"type": "pause"}, token=tv.secret)[0])   # nor the TV a parent
        self.assertEqual(401, self.call("GET", "/v1/device/state", token="deadbeef")[0])
        self.assertEqual(401, self.call("GET", "/v1/device/poll?since=0&wait=0", token=parent)[0])
        self.assertEqual(401, self.call("POST", "/v1/device/ack", {"id": "x", "result": "ok"}, token=parent)[0])

    def test_a_database_failure_is_503(self):
        tv = FakeTV(self.h)
        self.h.store.db.close()
        with self.assertLogs("mytv-cloud", "ERROR"):
            self.assertEqual(503, self.call("POST", "/v1/device/state", {}, token=tv.secret)[0])


class PairingTest(ServerTest):
    def test_pairing_gives_a_token_and_the_code_works_once(self):
        tv = FakeTV(self.h, "Kitchen")
        code = tv.code()
        s, p = self.call("POST", "/v1/pair", {"code": code, "name": "Mama"})
        self.assertEqual((200, tv.device_id, "Kitchen"), (s, p["device_id"], p["device_name"]))
        self.assertEqual(64, len(p["parent_token"]))
        self.assertEqual(403, self.call("POST", "/v1/pair", {"code": code, "name": "Papa"})[0])

    def test_an_old_code_is_refused_and_a_new_code_replaces_the_old(self):
        tv = FakeTV(self.h)
        old = tv.code()
        new = tv.code()
        if old != new:
            self.assertEqual(403, self.call("POST", "/v1/pair", {"code": old, "name": "x"})[0])
        self.clock.t += server.PAIR_TTL_S + 1
        self.assertEqual(403, self.call("POST", "/v1/pair", {"code": new, "name": "Mama"})[0])

    def test_two_tvs_never_share_a_live_code(self):
        a, b = FakeTV(self.h, "A"), FakeTV(self.h, "B")
        codes = {tv.device_id: tv.code() for tv in (a, b)}
        self.assertEqual(2, len(set(codes.values())))
        for tv in (a, b):
            self.assertEqual(tv.device_id, self.call("POST", "/v1/pair", {"code": codes[tv.device_id], "name": "x"})[1]["device_id"])

    def test_codes_must_be_six_digits(self):
        FakeTV(self.h).code()
        for bad in (None, 123456, "12345", "1234567", "abcdef", "", ["123456"]):
            self.clock.t += server.FAIL_WINDOW_S + 1
            self.assertEqual(403, self.call("POST", "/v1/pair", {"code": bad, "name": "x"})[0], bad)

    def test_five_wrong_codes_block_the_address(self):
        FakeTV(self.h).code()
        for _ in range(5):
            self.assertEqual(403, self.call("POST", "/v1/pair", {"code": "000000", "name": "x"})[0])
        self.assertEqual(429, self.call("POST", "/v1/pair", {"code": "000000", "name": "x"})[0])
        self.clock.t += server.FAIL_WINDOW_S + 1
        self.assertEqual(403, self.call("POST", "/v1/pair", {"code": "000000", "name": "x"})[0])

    def test_a_blocked_address_cannot_pair_even_with_the_right_code(self):
        tv = FakeTV(self.h)
        code = tv.code()
        wrong = "000000" if code != "000000" else "000001"
        for _ in range(5):
            self.call("POST", "/v1/pair", {"code": wrong, "name": "x"})
        self.assertEqual(429, self.call("POST", "/v1/pair", {"code": code, "name": "x"})[0])

    def test_at_most_eight_phones_waiting_requests_included(self):
        tv, owner, _ = self.paired("p0")
        for i in range(1, 8):
            self.join(tv, owner, "p%d" % i, approve=True)
        self.assertEqual((409, "too many phones"), self.pair_status(tv, "ninth"))

    def test_names_are_clipped_and_defaulted(self):
        tv = FakeTV(self.h, "N" * 200)
        p = self.call("POST", "/v1/pair", {"code": tv.code(), "name": ""})[1]
        self.assertEqual(60, len(p["device_name"]))
        self.call("POST", "/v1/commands", {"type": "unlock"}, token=p["parent_token"])
        self.assertEqual("Parent", tv.poll()[0]["args"]["by"])

    def test_creating_devices_is_limited_per_address(self):
        for _ in range(server.NEW_DEVICES_PER_HOUR + 1):
            self.call("POST", "/v1/devices", {"name": "x"})
        self.assertEqual(429, self.call("POST", "/v1/devices", {"name": "x"})[0])
        self.clock.t += 3601
        self.assertEqual(200, self.call("POST", "/v1/devices", {"name": "x"})[0])

    def test_a_revoked_parent_loses_access(self):
        tv, parent, pid = self.paired()
        self.assertEqual(200, self.call("DELETE", "/v1/parents/" + pid, token=tv.secret)[0])
        self.assertEqual(401, self.call("GET", "/v1/device/state", token=parent)[0])

    def test_a_parent_can_revoke_itself_but_not_a_parent_of_another_tv(self):
        tv, parent, pid = self.paired("Mama")
        tv2, parent2, pid2 = self.paired("Other")
        self.assertEqual(404, self.call("DELETE", "/v1/parents/" + pid2, token=parent)[0])
        self.assertEqual(404, self.call("DELETE", "/v1/parents/" + pid2, token=tv.secret)[0])
        self.assertEqual(200, self.call("DELETE", "/v1/parents/" + pid, token=parent)[0])
        self.assertEqual(401, self.call("DELETE", "/v1/parents/" + pid2, token="nope")[0])

    def test_deleting_the_device_removes_everything(self):
        tv, parent, pid = self.paired()
        self.call("POST", "/v1/commands", {"type": "unlock"}, token=parent)
        tv.state(locked=False, journal=[{"t": 1_800_000_000_000, "kind": "k", "text": "t"}])
        self.assertEqual(404, self.call("DELETE", "/v1/devices/other", token=tv.secret)[0])
        self.assertEqual(200, self.call("DELETE", "/v1/devices/" + tv.device_id, token=tv.secret)[0])
        self.assertEqual(401, self.call("GET", "/v1/device/state", token=parent)[0])
        self.assertEqual(401, self.call("POST", "/v1/device/state", {}, token=tv.secret)[0])
        db = sqlite3.connect(self.h.db_path)
        for table in ("devices", "parents", "commands", "events"):
            self.assertEqual(0, db.execute("select count(*) from " + table).fetchone()[0], table)

    def test_tokens_are_stored_hashed(self):
        tv, parent, pid = self.paired()
        db = sqlite3.connect(self.h.db_path)
        raw = "".join(str(r) for t in ("devices", "parents") for r in db.execute("select * from " + t))
        self.assertNotIn(tv.secret, raw)
        self.assertNotIn(parent, raw)

    def test_a_proxy_address_is_used_only_when_trusted(self):
        for trusted in (False, True):
            self.h.stop()
            self.h.start(trust_proxy=trusted)
            self.call = self.h.call
            for i in range(5):
                self.call("POST", "/v1/pair", {"code": "000000", "name": "x"}, headers={"X-Real-IP": "10.0.0.1"})
            # trusted: the sixth call comes from another client and is not blocked; untrusted: the header is ignored
            other = self.call("POST", "/v1/pair", {"code": "000000", "name": "x"}, headers={"X-Real-IP": "10.0.0.2"})[0]
            self.assertEqual(403 if trusted else 429, other)
            same = self.call("POST", "/v1/pair", {"code": "000000", "name": "x"}, headers={"X-Real-IP": "10.0.0.1"})[0]
            self.assertEqual(429, same)


class OwnerTest(ServerTest):
    """The first phone to pair owns the TV; every later one waits for the owner's yes."""

    def setUp(self):
        super().setUp()
        self.tv, self.owner, self.owner_id = self.paired("Mama")

    def test_the_first_phone_is_the_active_owner_and_later_ones_wait(self):
        tv = FakeTV(self.h)
        first = self.call("POST", "/v1/pair", {"code": tv.code(), "name": "A"})[1]
        second = self.call("POST", "/v1/pair", {"code": tv.code(), "name": "B"})[1]
        self.assertEqual(("active", True, "pending", False), (first["status"], first["owner"], second["status"], second["owner"]))

    def test_a_waiting_phone_can_do_nothing_but_withdraw(self):
        token, pid = self.join(self.tv, self.owner, "Boris")
        for method, path, body in (("GET", "/v1/device/state", None), ("GET", "/v1/device/journal", None), ("GET", "/v1/device/days", None),
                                   ("POST", "/v1/commands", {"type": "unlock"}), ("GET", "/v1/commands/x", None), ("GET", "/v1/parents", None),
                                   ("POST", "/v1/parents/%s/approve" % pid, None)):
            s, d = self.call(method, path, body, token=token)
            self.assertEqual((403, "pending approval"), (s, d["error"]), path)
        self.assertEqual([], self.tv.poll())                                           # nothing was queued
        self.assertEqual(403, self.call("DELETE", "/v1/parents/" + self.owner_id, token=token)[0])
        self.assertEqual(200, self.call("DELETE", "/v1/parents/" + pid, token=token)[0])
        self.assertEqual(401, self.call("GET", "/v1/device/state", token=token)[0])

    def test_the_owner_sees_waiting_requests_others_do_not(self):
        boris, bid = self.join(self.tv, self.owner, "Boris", approve=True)
        clara, cid = self.join(self.tv, self.owner, "Clara")
        owner_view = self.call("GET", "/v1/parents", token=self.owner)[1]
        self.assertEqual((self.owner_id, True), (owner_view["you"], owner_view["owner"]))
        self.assertEqual([("Mama", True, "active"), ("Boris", False, "active"), ("Clara", False, "pending")],
                         [(p["name"], p["owner"], p["status"]) for p in owner_view["parents"]])
        boris_view = self.call("GET", "/v1/parents", token=boris)[1]
        self.assertEqual((bid, False, ["Mama", "Boris"]), (boris_view["you"], boris_view["owner"], [p["name"] for p in boris_view["parents"]]))

    def test_the_owner_allows_and_then_the_phone_works_like_any_parent(self):
        token, pid = self.join(self.tv, self.owner, "Boris")
        self.assertEqual(403, self.call("GET", "/v1/device/state", token=token)[0])
        self.assertEqual(200, self.call("POST", "/v1/parents/%s/approve" % pid, token=self.owner)[0])
        self.assertEqual(200, self.call("GET", "/v1/device/state", token=token)[0])
        self.assertEqual(200, self.call("POST", "/v1/commands", {"type": "unlock"}, token=token)[0])      # unlock for any paired parent
        self.assertEqual("Boris", self.tv.poll()[0]["args"]["by"])

    def test_only_the_owner_approves_and_only_its_own_tvs_requests(self):
        boris, bid = self.join(self.tv, self.owner, "Boris", approve=True)
        clara, cid = self.join(self.tv, self.owner, "Clara")
        self.assertEqual((403, "owner only"), (lambda r: (r[0], r[1]["error"]))(self.call("POST", "/v1/parents/%s/approve" % cid, token=boris)))
        other, otoken, _ = self.paired("Other")
        self.assertEqual(404, self.call("POST", "/v1/parents/%s/approve" % cid, token=otoken)[0])
        self.assertEqual(404, self.call("POST", "/v1/parents/nope/approve", token=self.owner)[0])
        self.assertEqual(404, self.call("POST", "/v1/parents/%s/approve" % bid, token=self.owner)[0])     # already active
        self.assertEqual(401, self.call("POST", "/v1/parents/%s/approve" % cid, token="bad")[0])

    def test_the_owner_denies_by_removing_the_request(self):
        token, pid = self.join(self.tv, self.owner, "Boris")
        self.assertEqual(200, self.call("DELETE", "/v1/parents/" + pid, token=self.owner)[0])
        self.assertEqual(401, self.call("GET", "/v1/device/state", token=token)[0])
        self.assertEqual(404, self.call("POST", "/v1/parents/%s/approve" % pid, token=self.owner)[0])

    def test_a_member_cannot_remove_others_but_the_owner_and_the_tv_can(self):
        boris, bid = self.join(self.tv, self.owner, "Boris", approve=True)
        clara, cid = self.join(self.tv, self.owner, "Clara", approve=True)
        self.assertEqual((403, "owner only"), (lambda r: (r[0], r[1]["error"]))(self.call("DELETE", "/v1/parents/" + cid, token=boris)))
        self.assertEqual(200, self.call("DELETE", "/v1/parents/" + bid, token=self.owner)[0])
        self.assertEqual(200, self.call("DELETE", "/v1/parents/" + cid, token=self.tv.secret)[0])
        self.assertEqual(200, self.call("GET", "/v1/device/state", token=self.owner)[0])

    def test_at_most_three_requests_wait_and_answering_frees_a_place(self):
        pending = [self.join(self.tv, self.owner, "w%d" % i) for i in range(3)]
        self.assertEqual((409, "too many requests"), self.pair_status(self.tv, "fourth"))
        self.call("DELETE", "/v1/parents/" + pending[0][1], token=self.owner)
        self.join(self.tv, self.owner, "fourth")

    def test_an_unanswered_request_is_forgotten_after_a_day(self):
        token, pid = self.join(self.tv, self.owner, "Boris")
        self.clock.t += server.PENDING_TTL_S + 1
        self.assertEqual(401, self.call("GET", "/v1/device/state", token=token)[0])
        self.assertEqual(404, self.call("POST", "/v1/parents/%s/approve" % pid, token=self.owner)[0])
        self.assertEqual(["Mama"], [p["name"] for p in self.call("GET", "/v1/parents", token=self.owner)[1]["parents"]])
        self.join(self.tv, self.owner, "again")           # the slot is free again
        self.clock.t += server.PENDING_TTL_S + 1
        self.h.store.purge()
        self.assertEqual(0, sqlite3.connect(self.h.db_path).execute("select count(*) from parents where status='pending'").fetchone()[0])

    def test_when_the_owner_leaves_the_oldest_member_takes_over(self):
        boris, bid = self.join(self.tv, self.owner, "Boris", approve=True)
        clara, cid = self.join(self.tv, self.owner, "Clara", approve=True)
        dina, did_ = self.join(self.tv, self.owner, "Dina")
        self.assertEqual(200, self.call("DELETE", "/v1/parents/" + self.owner_id, token=self.owner)[0])
        view = self.call("GET", "/v1/parents", token=boris)[1]
        self.assertEqual((True, ["Boris", "Clara", "Dina"]), (view["owner"], [p["name"] for p in view["parents"]]))   # Boris owns now and sees Dina waiting
        self.assertEqual(200, self.call("POST", "/v1/parents/%s/approve" % did_, token=boris)[0])
        self.assertEqual(False, self.call("GET", "/v1/parents", token=clara)[1]["owner"])

    def test_the_tv_lists_everyone_so_a_lost_owner_can_be_removed(self):
        boris, bid = self.join(self.tv, self.owner, "Boris", approve=True)
        self.join(self.tv, self.owner, "Clara")
        s, d = self.call("GET", "/v1/parents", token=self.tv.secret)
        self.assertEqual(200, s)
        self.assertEqual([("Mama", True, "active"), ("Boris", False, "active"), ("Clara", False, "pending")],
                         [(p["name"], p["owner"], p["status"]) for p in d["parents"]])
        self.assertNotIn("you", d)
        self.assertEqual(401, self.call("GET", "/v1/parents", token="nope")[0])
        # the owner's phone is gone for good: the TV removes it, the oldest member owns the TV from now on
        self.assertEqual(200, self.call("DELETE", "/v1/parents/" + self.owner_id, token=self.tv.secret)[0])
        self.assertEqual(401, self.call("GET", "/v1/device/state", token=self.owner)[0])
        view = self.call("GET", "/v1/parents", token=boris)[1]
        self.assertEqual((True, ["Boris", "Clara"]), (view["owner"], [p["name"] for p in view["parents"]]))

    def test_the_tv_removing_the_owner_also_hands_the_tv_on(self):
        boris, bid = self.join(self.tv, self.owner, "Boris", approve=True)
        self.call("DELETE", "/v1/parents/" + self.owner_id, token=self.tv.secret)
        self.assertTrue(self.call("GET", "/v1/parents", token=boris)[1]["owner"])

    def test_when_nobody_is_left_waiting_requests_go_and_the_next_phone_owns_the_tv(self):
        token, pid = self.join(self.tv, self.owner, "Boris")
        self.call("DELETE", "/v1/parents/" + self.owner_id, token=self.owner)
        self.assertEqual(401, self.call("GET", "/v1/device/state", token=token)[0])
        p = self.call("POST", "/v1/pair", {"code": self.tv.code(), "name": "New"})[1]
        self.assertEqual(("active", True), (p["status"], p["owner"]))

    def test_a_database_from_before_owners_is_migrated(self):
        import os
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "old.sqlite")
            db = sqlite3.connect(path)
            db.executescript("""create table parents(id text primary key, device_id text not null, name text not null,
                token_hash text not null unique, created real not null);
                insert into parents values('p1','d1','Old','h1',1),('p2','d1','Newer','h2',2),('p3','d2','Solo','h3',3);""")
            db.commit()
            db.close()
            store = server.Store(path)
            rows = store.db.execute("select name, owner, status from parents order by rowid").fetchall()
            store.db.close()
        self.assertEqual([("Old", 1, "active"), ("Newer", 0, "active"), ("Solo", 1, "active")], rows)


class BotKeyTest(ServerTest):
    bot_key = "secret-bot-key"

    def test_a_trusted_bot_is_limited_per_chat(self):
        FakeTV(self.h).code()
        hdr = lambda subject, key="secret-bot-key": {"X-Bot-Key": key, "X-Subject": subject}
        for _ in range(5):
            self.assertEqual(403, self.call("POST", "/v1/pair", {"code": "000000", "name": "x"}, headers=hdr("tg-1"))[0])
        self.assertEqual(429, self.call("POST", "/v1/pair", {"code": "000000", "name": "x"}, headers=hdr("tg-1"))[0])
        self.assertEqual(403, self.call("POST", "/v1/pair", {"code": "000000", "name": "x"}, headers=hdr("tg-2"))[0])

    def test_a_wrong_key_falls_back_to_the_address(self):
        FakeTV(self.h).code()
        for i in range(5):
            self.call("POST", "/v1/pair", {"code": "000000", "name": "x"}, headers={"X-Bot-Key": "guess", "X-Subject": "tg-%d" % i})
        self.assertEqual(429, self.call("POST", "/v1/pair", {"code": "000000", "name": "x"}, headers={"X-Bot-Key": "guess", "X-Subject": "tg-9"})[0])


class StateTest(ServerTest):
    def test_state_goes_to_the_parent_and_goes_offline(self):
        tv, parent, pid = self.paired(tv_name="Hall")
        s, r = tv.state(locked=True, service=False, scheduled=False, screen={"on": True, "since": 5, "pkg": "ru.ivi.client", "label": "Ivi"},
                        journal=[{"t": 1_800_000_000_000, "kind": "service_off", "text": "off"}],
                        days=[{"day": "2026-11-05", "s": 60, "apps": []}])
        self.assertEqual((200, 1), (s, r["rev"]))
        s, st = self.call("GET", "/v1/device/state", token=parent)
        self.assertEqual((200, True, True, False, "Hall"), (s, st["locked"], st["online"], st["service"], st["name"]))
        self.assertEqual("Ivi", st["screen"]["label"])
        self.assertNotIn("journal", st)
        self.assertNotIn("days", st)
        self.assertEqual("service_off", self.call("GET", "/v1/device/journal", token=parent)[1]["events"][0]["kind"])
        self.assertEqual(60, self.call("GET", "/v1/device/days", token=parent)[1]["days"][0]["s"])
        self.clock.t += server.ONLINE_S + 1
        self.assertFalse(self.call("GET", "/v1/device/state", token=parent)[1]["online"])

    def test_before_the_first_report_the_tv_is_offline_with_no_data(self):
        tv, parent, pid = self.paired()
        s, st = self.call("GET", "/v1/device/state", token=parent)
        self.assertEqual((200, False, 0), (s, st["online"], st["last_seen"]))
        self.assertNotIn("locked", st)

    def test_unknown_fields_are_dropped_and_bad_types_ignored(self):
        tv, parent, pid = self.paired()
        tv.state(locked="yes", service=True, wifi="home-net", screen={"on": True, "pkg": "p" * 500, "ssid": "x"})
        st = self.call("GET", "/v1/device/state", token=parent)[1]
        self.assertNotIn("locked", st)
        self.assertNotIn("wifi", st)
        self.assertEqual(120, len(st["screen"]["pkg"]))
        self.assertNotIn("ssid", st["screen"])
        self.assertEqual(400, self.call("POST", "/v1/device/state", [1], token=tv.secret)[0])

    def test_a_poll_counts_as_a_sign_of_life(self):
        tv, parent, pid = self.paired()
        tv.poll()
        self.assertTrue(self.call("GET", "/v1/device/state", token=parent)[1]["online"])

    def test_the_journal_accumulates_without_duplicates_newest_first(self):
        tv, parent, pid = self.paired()
        base = 1_800_000_000_000
        tv.state(journal=[{"t": base + i, "kind": "e", "text": str(i)} for i in range(3)])
        tv.state(journal=[{"t": base + i, "kind": "e", "text": str(i)} for i in range(2, 5)])
        events = self.call("GET", "/v1/device/journal", token=parent)[1]["events"]
        self.assertEqual(["4", "3", "2", "1", "0"], [e["text"] for e in events])

    def test_bad_journal_events_are_skipped_and_the_journal_is_capped(self):
        tv, parent, pid = self.paired()
        base = 1_800_000_000_000
        tv.state(journal=["x", {"t": "1", "kind": "e"}, {"t": base, "kind": 5}, {"t": base * 10, "kind": "future"}, {"t": base, "kind": "ok", "text": "fine"}])
        self.assertEqual(["ok"], [e["kind"] for e in self.call("GET", "/v1/device/journal", token=parent)[1]["events"]])
        for i in range(15):
            self.clock.t += 61
            tv.state(journal=[{"t": base + 1000 + i * 20 + j, "kind": "e", "text": "x"} for j in range(20)])
        self.assertEqual(server.JOURNAL_MAX, len(self.call("GET", "/v1/device/journal", token=parent)[1]["events"]))

    def test_each_unlock_is_journaled_with_the_parent_name(self):
        tv, parent, pid = self.paired("Mama")
        self.call("POST", "/v1/commands", {"type": "unlock"}, token=parent)
        events = self.call("GET", "/v1/device/journal", token=parent)[1]["events"]
        self.assertEqual("cloud_unlock", events[0]["kind"])
        self.assertIn("Mama", events[0]["text"])
        self.assertEqual("Mama", tv.poll()[0]["args"]["by"])

    def test_a_parent_sees_only_its_own_tv(self):
        tv1, parent1, _ = self.paired("A", "one")
        tv2, parent2, _ = self.paired("B", "two")
        tv1.state(locked=True)
        tv2.state(locked=False)
        self.assertTrue(self.call("GET", "/v1/device/state", token=parent1)[1]["locked"])
        self.assertEqual("two", self.call("GET", "/v1/device/state", token=parent2)[1]["name"])


class CommandTest(ServerTest):
    def test_a_command_reaches_the_tv_and_is_acknowledged(self):
        tv, parent, pid = self.paired()
        s, c = self.call("POST", "/v1/commands", {"type": "message", "args": {"text": "Dinner"}, "ttl_s": 600}, token=parent)
        self.assertEqual(200, s)
        self.assertEqual("queued", self.call("GET", "/v1/commands/" + c["id"], token=parent)[1]["status"])
        cmds = tv.poll()
        self.assertEqual(1, len(cmds))
        self.assertEqual(("message", "Dinner", "Mama"), (cmds[0]["type"], cmds[0]["args"]["text"], cmds[0]["args"]["by"]))
        self.assertEqual("delivered", self.call("GET", "/v1/commands/" + c["id"], token=parent)[1]["status"])
        self.assertEqual(200, tv.ack(cmds[0], "failed", "no overlay permission")[0])
        self.assertEqual({"status": "failed", "detail": "no overlay permission"}, self.call("GET", "/v1/commands/" + c["id"], token=parent)[1])
        self.assertEqual([], tv.poll())          # nothing new after the seq the TV has seen

    def test_the_first_final_answer_stands_but_an_expired_one_can_be_corrected(self):
        tv, parent, pid = self.paired()
        c = self.call("POST", "/v1/commands", {"type": "pause"}, token=parent)[1]
        cmd = tv.poll()[0]
        self.clock.t += 700
        self.assertEqual("expired", self.call("GET", "/v1/commands/" + c["id"], token=parent)[1]["status"])
        tv.ack(cmd, "ok")
        self.assertEqual("ok", self.call("GET", "/v1/commands/" + c["id"], token=parent)[1]["status"])
        tv.ack(cmd, "failed")
        self.assertEqual("ok", self.call("GET", "/v1/commands/" + c["id"], token=parent)[1]["status"])

    def test_ack_errors(self):
        tv, parent, pid = self.paired()
        other, op, _ = self.paired("B")
        c = self.call("POST", "/v1/commands", {"type": "pause"}, token=parent)[1]
        self.assertEqual(404, self.call("POST", "/v1/device/ack", {"id": "nope", "result": "ok"}, token=tv.secret)[0])
        self.assertEqual(404, self.call("POST", "/v1/device/ack", {"id": c["id"], "result": "ok"}, token=other.secret)[0])
        for bad in ({"id": c["id"], "result": "done"}, {"id": 5, "result": "ok"}, {"result": "ok"}, [1]):
            self.assertEqual(400, self.call("POST", "/v1/device/ack", bad, token=tv.secret)[0], bad)

    def test_a_parent_reads_only_its_own_tvs_commands(self):
        tv, parent, pid = self.paired()
        other, op, _ = self.paired("B")
        c = self.call("POST", "/v1/commands", {"type": "pause"}, token=parent)[1]
        self.assertEqual(404, self.call("GET", "/v1/commands/" + c["id"], token=op)[0])

    def test_a_long_poll_wakes_when_a_command_arrives(self):
        tv, parent, pid = self.paired()
        got = []
        t = threading.Thread(target=lambda: got.append(self.call("GET", "/v1/device/poll?since=0&wait=10", token=tv.secret)))
        t.start()
        time.sleep(0.5)
        self.call("POST", "/v1/commands", {"type": "pause"}, token=parent)
        t.join(8)
        self.assertFalse(t.is_alive())
        self.assertEqual("pause", got[0][1]["commands"][0]["type"])

    def test_a_poll_without_commands_waits_then_answers_empty(self):
        tv, parent, pid = self.paired()
        self.h.store.clock = time.time          # the fake clock never advances, a real wait needs a real one
        started = time.time()
        s, d = self.call("GET", "/v1/device/poll?since=7&wait=1", token=tv.secret)
        self.assertEqual((200, [], 7), (s, d["commands"], d["seq"]))
        self.assertLess(time.time() - started, 10)

    def test_an_expired_command_is_never_delivered(self):
        tv, parent, pid = self.paired()
        c = self.call("POST", "/v1/commands", {"type": "lock", "ttl_s": 60}, token=parent)[1]
        self.clock.t += 61
        self.assertEqual([], tv.poll())
        self.assertEqual("expired", self.call("GET", "/v1/commands/" + c["id"], token=parent)[1]["status"])

    def test_commands_wait_for_an_offline_tv(self):
        tv, parent, pid = self.paired()
        for kind in ("lock", "pause"):
            self.call("POST", "/v1/commands", {"type": kind}, token=parent)
        self.clock.t += 1800                    # away for half an hour: both outlived the default 10 minutes
        self.assertEqual([], tv.poll())
        self.call("POST", "/v1/commands", {"type": "lock"}, token=parent)
        self.clock.t += 120                     # a short absence: still waiting
        self.assertEqual(1, len(tv.poll()))

    def test_bad_commands_are_refused(self):
        tv, parent, pid = self.paired()
        bad = [{"type": "reboot"}, {"type": "message", "args": {"text": ""}}, {"type": "message", "args": {"text": "x" * 141}},
               {"type": "message", "args": {"text": 5}}, {"type": "message"}, {"type": "lock", "args": {"note": "x" * 141}},
               {"type": "bonus", "args": {"minutes": 500}}, {"type": "bonus", "args": {"minutes": 4}}, {"type": "bonus", "args": {"minutes": True}},
               {"type": "lock", "ttl_s": 5}, {"type": "lock", "ttl_s": 99999}, {"type": "lock", "ttl_s": "60"}, {"type": "lock", "args": []},
               {"type": "power_off", "args": {"delay_s": 86401}}, {"type": "power_off", "args": {"delay_s": -1}},
               {"type": "power_off", "args": {"delay_s": "5"}}, {"type": "power_off", "args": {"cancel": "yes"}}, [1]]
        for b in bad:
            self.assertEqual(400, self.call("POST", "/v1/commands", b, token=parent)[0], b)

    def test_arguments_are_whitelisted_and_the_sender_cannot_be_forged(self):
        tv, parent, pid = self.paired("Mama")
        self.call("POST", "/v1/commands", {"type": "lock", "args": {"note": "Dinner", "by": "Papa", "pin": "1234"}}, token=parent)
        self.call("POST", "/v1/commands", {"type": "lock"}, token=parent)
        self.call("POST", "/v1/commands", {"type": "pause", "args": {"anything": 1}}, token=parent)
        cmds = tv.poll()
        self.assertEqual({"note": "Dinner", "by": "Mama"}, cmds[0]["args"])
        self.assertEqual({"by": "Mama"}, cmds[1]["args"])
        self.assertEqual({"by": "Mama"}, cmds[2]["args"])

    def test_power_off_carries_the_parent_a_delay_or_a_cancel(self):
        tv, parent, pid = self.paired()
        self.call("POST", "/v1/commands", {"type": "power_off", "args": {"delay_s": 1800}}, token=parent)
        self.call("POST", "/v1/commands", {"type": "power_off", "args": {"cancel": True}}, token=parent)
        self.call("POST", "/v1/commands", {"type": "power_off"}, token=parent)
        cmds = tv.poll()
        self.assertEqual([1800, True, 0], [cmds[0]["args"].get("delay_s"), cmds[1]["args"].get("cancel"), cmds[2]["args"]["delay_s"]])
        self.assertTrue(cmds[0]["args"]["by"])

    def test_bonus_is_accepted(self):
        tv, parent, pid = self.paired()
        self.assertEqual(200, self.call("POST", "/v1/commands", {"type": "bonus", "args": {"minutes": 30}}, token=parent)[0])
        self.assertEqual(30, tv.poll()[0]["args"]["minutes"])

    def test_the_queue_keeps_fifty(self):
        tv, parent, pid = self.paired()
        for i in range(55):
            self.call("POST", "/v1/commands", {"type": "message", "args": {"text": "m%d" % i}, "ttl_s": 3600}, token=parent)
            self.clock.t += 2
        cmds = tv.poll()
        self.assertEqual(50, len(cmds))
        self.assertEqual("m5", cmds[0]["args"]["text"])


class RateLimitTest(ServerTest):
    def test_sixty_calls_a_minute_per_token_on_every_endpoint(self):
        tv, parent, pid = self.paired()
        for i in range(server.RATE_PER_MIN):
            self.assertEqual(200, self.call("GET", "/v1/device/state", token=parent)[0], i)
        s, _ = self.call("GET", "/v1/device/state", token=parent)
        self.assertEqual(429, s)
        self.assertEqual(429, self.call("GET", "/v1/device/journal", token=parent)[0])
        self.assertEqual(429, self.call("POST", "/v1/commands", {"type": "pause"}, token=parent)[0])
        self.assertEqual(200, tv.state(locked=False)[0])         # the TV has its own budget
        self.clock.t += 61
        self.assertEqual(200, self.call("GET", "/v1/device/state", token=parent)[0])

    def test_the_tv_is_limited_too(self):
        tv, parent, pid = self.paired()
        self.clock.t += 61                      # pairing used some of the TV's budget
        for _ in range(server.RATE_PER_MIN):
            self.assertEqual(200, tv.state(locked=False)[0])
        self.assertEqual(429, tv.state(locked=False)[0])
        self.assertEqual(429, self.call("GET", "/v1/device/poll?since=0&wait=0", token=tv.secret)[0])
        self.assertEqual(429, self.call("POST", "/v1/pair/code", token=tv.secret)[0])

    def test_a_429_says_when_to_retry(self):
        tv, parent, pid = self.paired()
        for _ in range(server.RATE_PER_MIN):
            self.call("GET", "/v1/device/state", token=parent)
        c = http.client.HTTPConnection("127.0.0.1", self.h.port, timeout=10)
        c.request("GET", "/v1/device/state", headers={"X-Protocol": "1", "Authorization": "Bearer " + parent})
        r = c.getresponse()
        self.assertEqual((429, "60"), (r.status, r.getheader("Retry-After")))
        c.close()


class PurgeTest(ServerTest):
    def test_data_older_than_thirty_days_is_deleted(self):
        tv, parent, pid = self.paired()
        now_ms = int(self.clock.t * 1000)
        day = lambda ago: time.strftime("%Y-%m-%d", time.gmtime(self.clock.t - ago * 86400))
        tv.state(locked=True, journal=[{"t": now_ms, "kind": "new", "text": ""}],
                 days=[{"day": day(40), "s": 1}, {"day": day(5), "s": 2}])
        self.call("POST", "/v1/commands", {"type": "pause"}, token=parent)
        self.clock.t += 20 * 86400
        self.h.store.purge()
        self.assertEqual(1, len(self.call("GET", "/v1/device/journal", token=parent)[1]["events"]))
        self.assertEqual([2], [d["s"] for d in self.call("GET", "/v1/device/days", token=parent)[1]["days"]])
        self.clock.t += 11 * 86400      # now the journal event, the command and the state are 31 days old
        self.h.store.purge()
        self.assertEqual([], self.call("GET", "/v1/device/journal", token=parent)[1]["events"])
        self.assertEqual([], self.call("GET", "/v1/device/days", token=parent)[1]["days"])
        st = self.call("GET", "/v1/device/state", token=parent)[1]
        self.assertNotIn("locked", st)
        self.assertEqual(0, sqlite3.connect(self.h.db_path).execute("select count(*) from commands").fetchone()[0])
        self.assertEqual(200, self.call("GET", "/v1/device/state", token=parent)[0])    # the pairing itself survives

    def test_abandoned_registrations_are_forgotten_but_paired_ones_are_not(self):
        lonely = FakeTV(self.h)
        tv, parent, pid = self.paired()
        self.clock.t += 31 * 86400
        self.h.store.purge()
        self.assertEqual(401, self.call("POST", "/v1/pair/code", token=lonely.secret)[0])
        self.assertEqual(200, self.call("POST", "/v1/pair/code", token=tv.secret)[0])

    def test_the_limiter_tables_are_pruned(self):
        FakeTV(self.h)
        self.call("POST", "/v1/pair", {"code": "000000", "name": "x"})
        self.clock.t += 7200
        self.h.store.purge()
        self.assertEqual(({}, {}, {}), (self.h.store.calls, self.h.store.fails, self.h.store.creations))


class MainTest(unittest.TestCase):
    def test_main_builds_a_server_from_arguments(self):
        import os
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            keyfile = os.path.join(d, "key")
            with open(keyfile, "w") as f:
                f.write("abc\n")
            self.assertEqual("abc", server.read_key(keyfile))
            self.assertIsNone(server.read_key(os.path.join(d, "missing")))
            httpd = server.make_server(os.path.join(d, "x.sqlite"), "127.0.0.1", 0, bot_key=server.read_key(keyfile))
            self.assertEqual("abc", httpd.store.bot_key)
            self.assertEqual(0o600, os.stat(os.path.join(d, "x.sqlite")).st_mode & 0o777)
            httpd.server_close()


if __name__ == "__main__":
    unittest.main()
