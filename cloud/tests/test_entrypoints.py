"""The two programs started the way systemd starts them: as separate processes, configured by environment."""
import http.client
import os
import signal
import socket
import subprocess
import sys
import tempfile
import time
import unittest

from tests.fake_telegram import FakeTelegram

CLOUD = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def stop(proc):
    proc.send_signal(signal.SIGINT)
    try:
        return proc.communicate(timeout=10)[1]
    except subprocess.TimeoutExpired:
        proc.kill()
        return proc.communicate()[1]


def get(port, path, headers):
    c = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
    c.request("GET", path, headers=headers)
    r = c.getresponse()
    out = (r.status, r.read())
    c.close()
    return out


class EntryPointTest(unittest.TestCase):
    def test_the_server_starts_from_environment_and_answers(self):
        with tempfile.TemporaryDirectory() as d:
            port = free_port()
            env = dict(os.environ, MYTV_CLOUD_PORT=str(port), MYTV_CLOUD_DB=os.path.join(d, "sub", "cloud.sqlite"),
                       MYTV_CLOUD_BOT_KEY_FILE=os.path.join(d, "none"))
            p = subprocess.Popen([sys.executable, "-I", os.path.join(CLOUD, "server", "server.py")], env=env,
                                 stderr=subprocess.PIPE, text=True)
            try:
                for _ in range(100):
                    try:
                        self.assertEqual((200, b'{"ok": true}'), get(port, "/v1/health", {}))
                        break
                    except OSError:
                        time.sleep(0.1)
                else:
                    self.fail("the server did not come up")
                self.assertEqual(400, get(port, "/v1/device/state", {})[0])
                self.assertEqual(401, get(port, "/v1/device/state", {"X-Protocol": "1"})[0])
            finally:
                err = stop(p)
            self.assertIn("listening on 127.0.0.1:%d" % port, err)
            self.assertTrue(os.path.exists(os.path.join(d, "sub", "cloud.sqlite")))

    def test_the_bot_starts_from_environment_answers_and_logs_no_token(self):
        tg = FakeTelegram()
        try:
            with tempfile.TemporaryDirectory() as d:
                key = os.path.join(d, "key")
                with open(key, "w") as f:
                    f.write("a-shared-key-for-this-test")
                env = dict(os.environ, MYTV_BOT_TOKEN=tg.token, MYTV_TELEGRAM_API=tg.url, MYTV_BOT_DB=os.path.join(d, "bot.sqlite"),
                           MYTV_CLOUD_URL="http://127.0.0.1:1", MYTV_CLOUD_KEY_FILE=key)
                p = subprocess.Popen([sys.executable, "-I", os.path.join(CLOUD, "bot", "bot.py")], env=env, stderr=subprocess.PIPE, text=True)
                try:
                    tg.say(7, "/start")
                    tg.say(7, "123456")                          # the cloud is not there: the bot says so and carries on
                    deadline = time.time() + 15
                    while len(tg.sent) < 2 and time.time() < deadline:
                        time.sleep(0.05)
                finally:
                    err = stop(p)
            self.assertIn("6-digit", tg.texts(7)[0])
            self.assertIn("cannot reach the cloud", tg.texts(7)[1])
            self.assertIn("ready", err)
            self.assertNotIn(tg.token, err)
            self.assertNotIn("a-shared-key-for-this-test", err)
            self.assertIn("setMyCommands", tg.calls)
        finally:
            tg.stop()

    def test_the_bot_refuses_to_start_without_a_valid_token(self):
        with tempfile.TemporaryDirectory() as d:
            env = dict(os.environ, MYTV_BOT_TOKEN_FILE=os.path.join(d, "nothing"), MYTV_BOT_DB=os.path.join(d, "b.sqlite"))
            env.pop("MYTV_BOT_TOKEN", None)
            r = subprocess.run([sys.executable, "-I", os.path.join(CLOUD, "bot", "bot.py")], env=env, capture_output=True, text=True, timeout=20)
            self.assertNotEqual(0, r.returncode)
            self.assertIn("BotFather", r.stderr)


if __name__ == "__main__":
    unittest.main()
