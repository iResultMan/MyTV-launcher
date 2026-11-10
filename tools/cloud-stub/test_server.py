import http.client
import json
import os
import tempfile
import threading
import unittest

import server


class Clock:
    def __init__(self):
        self.t = 1_000_000.0

    def __call__(self):
        return self.t


class CloudStubTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.clock = Clock()
        self.httpd = server.make_server(os.path.join(self.dir.name, "db.sqlite"), 0, self.clock)
        self.port = self.httpd.server_address[1]
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.dir.cleanup()

    def call(self, method, path, body=None, token=None, protocol="1"):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=15)
        headers = {"X-Protocol": protocol} if protocol else {}
        if token:
            headers["Authorization"] = "Bearer " + token
        data = json.dumps(body) if body is not None else None
        if data:
            headers["Content-Type"] = "application/json"
        c.request(method, path, body=data, headers=headers)
        r = c.getresponse()
        out = json.loads(r.read() or b"{}")
        c.close()
        return r.status, out

    def tv(self):
        s, d = self.call("POST", "/v1/devices", {"name": "TV"})
        self.assertEqual(200, s)
        return d["device_id"], d["device_secret"]

    def paired(self, name="Mama"):
        did, secret = self.tv()
        s, c = self.call("POST", "/v1/pair/code", token=secret)
        self.assertEqual(200, s)
        s, p = self.call("POST", "/v1/pair", {"code": c["code"], "name": name})
        self.assertEqual(200, s)
        return did, secret, p["parent_token"], p["parent_id"]

    def test_protocol_header_is_required(self):
        self.assertEqual(400, self.call("POST", "/v1/devices", {"name": "x"}, protocol=None)[0])
        self.assertEqual(400, self.call("POST", "/v1/devices", {"name": "x"}, protocol="2")[0])

    def test_pairing_gives_a_token_and_the_code_works_once(self):
        did, secret = self.tv()
        code = self.call("POST", "/v1/pair/code", token=secret)[1]["code"]
        s, p = self.call("POST", "/v1/pair", {"code": code, "name": "Mama"})
        self.assertEqual((200, did), (s, p["device_id"]))
        self.assertEqual(64, len(p["parent_token"]))
        self.assertEqual(403, self.call("POST", "/v1/pair", {"code": code, "name": "Papa"})[0])

    def test_an_old_code_is_refused(self):
        did, secret = self.tv()
        code = self.call("POST", "/v1/pair/code", token=secret)[1]["code"]
        self.clock.t += server.PAIR_TTL_S + 1
        self.assertEqual(403, self.call("POST", "/v1/pair", {"code": code, "name": "Mama"})[0])

    def test_five_wrong_codes_block_the_address(self):
        self.tv()
        for _ in range(5):
            self.assertEqual(403, self.call("POST", "/v1/pair", {"code": "000000", "name": "x"})[0])
        self.assertEqual(429, self.call("POST", "/v1/pair", {"code": "000000", "name": "x"})[0])
        self.clock.t += server.FAIL_WINDOW_S + 1
        self.assertEqual(403, self.call("POST", "/v1/pair", {"code": "000000", "name": "x"})[0])

    def test_calls_without_the_right_token_get_401(self):
        did, secret, parent, pid = self.paired()
        self.assertEqual(401, self.call("POST", "/v1/device/state", {}, token=None)[0])
        self.assertEqual(401, self.call("POST", "/v1/device/state", {}, token=parent)[0])      # a parent is not the TV
        self.assertEqual(401, self.call("POST", "/v1/commands", {"type": "pause"}, token=secret)[0])   # nor the TV a parent
        self.assertEqual(401, self.call("GET", "/v1/device/state", token="deadbeef")[0])
        self.assertEqual(401, self.call("GET", "/v1/device/poll?since=0&wait=0", token=parent)[0])

    def test_state_goes_to_the_parent_and_goes_offline(self):
        did, secret, parent, pid = self.paired()
        s, r = self.call("POST", "/v1/device/state", {"locked": True, "service": False,
                                                      "journal": [{"t": 1, "kind": "service_off", "text": "off"}],
                                                      "days": [{"day": "2026-11-05", "s": 60, "apps": []}]}, token=secret)
        self.assertEqual((200, 1), (s, r["rev"]))
        s, st = self.call("GET", "/v1/device/state", token=parent)
        self.assertEqual((200, True, True, False), (s, st["locked"], st["online"], st["service"]))
        self.assertNotIn("journal", st)
        self.assertEqual("service_off", self.call("GET", "/v1/device/journal", token=parent)[1]["events"][0]["kind"])
        self.assertEqual(60, self.call("GET", "/v1/device/days", token=parent)[1]["days"][0]["s"])
        self.clock.t += server.ONLINE_S + 1
        self.assertFalse(self.call("GET", "/v1/device/state", token=parent)[1]["online"])

    def test_a_command_reaches_the_tv_and_is_acknowledged(self):
        did, secret, parent, pid = self.paired()
        s, c = self.call("POST", "/v1/commands", {"type": "message", "args": {"text": "Dinner"}, "ttl_s": 600}, token=parent)
        self.assertEqual(200, s)
        s, p = self.call("GET", "/v1/device/poll?since=0&wait=0", token=secret)
        self.assertEqual(1, len(p["commands"]))
        cmd = p["commands"][0]
        self.assertEqual(("message", "Dinner", "Mama"), (cmd["type"], cmd["args"]["text"], cmd["args"]["by"]))
        self.assertEqual("delivered", self.call("GET", "/v1/commands/" + c["id"], token=parent)[1]["status"])
        self.assertEqual(200, self.call("POST", "/v1/device/ack", {"id": c["id"], "result": "ok"}, token=secret)[0])
        self.assertEqual("ok", self.call("GET", "/v1/commands/" + c["id"], token=parent)[1]["status"])
        # nothing new after the seq the TV has seen
        self.assertEqual([], self.call("GET", "/v1/device/poll?since=%d&wait=0" % p["seq"], token=secret)[1]["commands"])

    def test_a_long_poll_wakes_when_a_command_arrives(self):
        did, secret, parent, pid = self.paired()
        got = []
        t = threading.Thread(target=lambda: got.append(self.call("GET", "/v1/device/poll?since=0&wait=10", token=secret)))
        t.start()
        import time
        time.sleep(0.5)
        self.call("POST", "/v1/commands", {"type": "pause"}, token=parent)
        t.join(8)
        self.assertFalse(t.is_alive())
        self.assertEqual("pause", got[0][1]["commands"][0]["type"])

    def test_an_expired_command_is_never_delivered(self):
        did, secret, parent, pid = self.paired()
        c = self.call("POST", "/v1/commands", {"type": "lock", "ttl_s": 60}, token=parent)[1]
        self.clock.t += 61
        self.assertEqual([], self.call("GET", "/v1/device/poll?since=0&wait=0", token=secret)[1]["commands"])
        self.assertEqual("expired", self.call("GET", "/v1/commands/" + c["id"], token=parent)[1]["status"])

    def test_commands_wait_for_an_offline_tv(self):
        did, secret, parent, pid = self.paired()
        for kind in ("lock", "pause"):
            self.call("POST", "/v1/commands", {"type": kind}, token=parent)
        self.clock.t += 1800                    # the TV was away for half an hour: both outlived the default 10 minutes
        self.assertEqual([], self.call("GET", "/v1/device/poll?since=0&wait=0", token=secret)[1]["commands"])
        self.call("POST", "/v1/commands", {"type": "lock"}, token=parent)
        self.clock.t += 120                     # a short absence: the command is still waiting
        self.assertEqual(1, len(self.call("GET", "/v1/device/poll?since=0&wait=0", token=secret)[1]["commands"]))

    def test_bad_commands_are_refused(self):
        did, secret, parent, pid = self.paired()
        bad = [{"type": "reboot"}, {"type": "message", "args": {"text": ""}}, {"type": "message", "args": {"text": "x" * 141}},
               {"type": "bonus", "args": {"minutes": 500}}, {"type": "lock", "ttl_s": 5}, {"type": "lock", "ttl_s": 99999}, {"type": "lock", "args": []},
               {"type": "power_off", "args": {"delay_s": 86401}}, {"type": "power_off", "args": {"delay_s": -1}}, {"type": "power_off", "args": {"delay_s": "5"}}]
        for b in bad:
            self.assertEqual(400, self.call("POST", "/v1/commands", b, token=parent)[0], b)

    def test_power_off_carries_the_parent_and_a_delay(self):
        did, secret, parent, pid = self.paired()
        self.assertEqual(200, self.call("POST", "/v1/commands", {"type": "power_off", "args": {"delay_s": 1800}}, token=parent)[0])
        self.assertEqual(200, self.call("POST", "/v1/commands", {"type": "power_off", "args": {"cancel": True}}, token=parent)[0])
        cmds = self.call("GET", "/v1/device/poll?since=0&wait=0", token=secret)[1]["commands"]
        self.assertEqual(["power_off", "power_off"], [c["type"] for c in cmds])
        self.assertEqual(1800, cmds[0]["args"]["delay_s"])
        self.assertTrue(cmds[0]["args"]["by"])

    def test_the_queue_keeps_fifty(self):
        did, secret, parent, pid = self.paired()
        for i in range(55):
            self.clock.t += 0.01
            self.call("POST", "/v1/commands", {"type": "message", "args": {"text": "m%d" % i}, "ttl_s": 3600}, token=parent)
            self.call("GET", "/v1/device/state", token=parent)       # keep under the 60 a minute limit
            self.clock.t += 2
        cmds = self.call("GET", "/v1/device/poll?since=0&wait=0", token=secret)[1]["commands"]
        self.assertEqual(50, len(cmds))
        self.assertEqual("m5", cmds[0]["args"]["text"])

    def test_a_revoked_parent_loses_access(self):
        did, secret, parent, pid = self.paired()
        self.assertEqual(200, self.call("DELETE", "/v1/parents/" + pid, token=secret)[0])
        self.assertEqual(401, self.call("GET", "/v1/device/state", token=parent)[0])

    def test_a_parent_can_revoke_itself_but_not_another_tv_parent(self):
        did, secret, parent, pid = self.paired("Mama")
        did2, secret2, parent2, pid2 = self.paired("Other")
        self.assertEqual(404, self.call("DELETE", "/v1/parents/" + pid2, token=parent)[0])
        self.assertEqual(200, self.call("DELETE", "/v1/parents/" + pid, token=parent)[0])

    def test_at_most_eight_phones(self):
        did, secret = self.tv()
        for i in range(8):
            code = self.call("POST", "/v1/pair/code", token=secret)[1]["code"]
            self.assertEqual(200, self.call("POST", "/v1/pair", {"code": code, "name": "p%d" % i})[0])
        code = self.call("POST", "/v1/pair/code", token=secret)[1]["code"]
        self.assertEqual(409, self.call("POST", "/v1/pair", {"code": code, "name": "ninth"})[0])

    def test_deleting_the_device_removes_everything(self):
        did, secret, parent, pid = self.paired()
        self.call("POST", "/v1/device/state", {"locked": False}, token=secret)
        self.assertEqual(200, self.call("DELETE", "/v1/devices/" + did, token=secret)[0])
        self.assertEqual(401, self.call("GET", "/v1/device/state", token=parent)[0])
        self.assertEqual(401, self.call("POST", "/v1/device/state", {}, token=secret)[0])

    def test_oversized_and_malformed_bodies(self):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        c.request("POST", "/v1/devices", body=b"{not json", headers={"X-Protocol": "1"})
        self.assertEqual(400, c.getresponse().status)
        c.close()
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        c.request("POST", "/v1/devices", body=b"x" * (server.MAX_BODY + 1), headers={"X-Protocol": "1"})
        self.assertEqual(413, c.getresponse().status)
        c.close()

    def test_tokens_are_stored_hashed(self):
        did, secret, parent, pid = self.paired()
        db = os.path.join(self.dir.name, "db.sqlite")
        import sqlite3
        raw = "".join(str(r) for t in ("devices", "parents") for r in sqlite3.connect(db).execute("select * from " + t))
        self.assertNotIn(secret, raw)
        self.assertNotIn(parent, raw)


if __name__ == "__main__":
    unittest.main()
