"""A fake TV and a small HTTP client built straight from docs/cloud-protocol.md, shared by the tests."""
import http.client
import json
import os
import tempfile
import threading

import server


class Clock:
    def __init__(self, t=1_800_000_000.0):
        self.t = t

    def __call__(self):
        return self.t


def call(port, method, path, body=None, token=None, protocol="1", headers=None):
    c = http.client.HTTPConnection("127.0.0.1", port, timeout=20)
    h = {"X-Protocol": protocol} if protocol else {}
    h.update(headers or {})
    if token:
        h["Authorization"] = "Bearer " + token
    data = json.dumps(body) if body is not None else None
    if data:
        h["Content-Type"] = "application/json"
    c.request(method, path, body=data, headers=h)
    r = c.getresponse()
    out = json.loads(r.read() or b"{}")
    c.close()
    return r.status, out


class CloudHarness:
    """A real server on a free local port with a controllable clock."""

    def start(self, **kw):
        self.dir = tempfile.TemporaryDirectory()
        self.db_path = os.path.join(self.dir.name, "db.sqlite")
        self.clock = Clock()
        self.httpd = server.make_server(self.db_path, "127.0.0.1", 0, self.clock, **kw)
        self.port = self.httpd.server_address[1]
        self.store = self.httpd.store
        threading.Thread(target=self.httpd.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True).start()

    def stop(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        try:
            self.store.db.close()
        except Exception:
            pass
        self.dir.cleanup()

    def call(self, method, path, body=None, token=None, **kw):
        return call(self.port, method, path, body, token, **kw)


class FakeTV:
    """What the launcher does: register, show a code, report state, poll and acknowledge."""

    def __init__(self, harness, name="Living room TV"):
        self.h = harness
        s, d = harness.call("POST", "/v1/devices", {"name": name})
        assert s == 200, d
        self.device_id, self.secret = d["device_id"], d["device_secret"]
        self.seq = 0

    def code(self):
        s, d = self.h.call("POST", "/v1/pair/code", token=self.secret)
        assert s == 200, d
        return d["code"]

    def state(self, **fields):
        return self.h.call("POST", "/v1/device/state", fields, token=self.secret)

    def poll(self, wait=0):
        s, d = self.h.call("GET", "/v1/device/poll?since=%d&wait=%d" % (self.seq, wait), token=self.secret)
        assert s == 200, d
        self.seq = d["seq"]
        return d["commands"]

    def ack(self, command, result="ok", detail=""):
        return self.h.call("POST", "/v1/device/ack", {"id": command["id"], "result": result, "detail": detail}, token=self.secret)
