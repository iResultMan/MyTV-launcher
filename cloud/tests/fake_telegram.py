"""A fake Telegram Bot API server: just enough of getMe, getUpdates, sendMessage and setMyCommands."""
import http.server
import json
import threading
import time


class FakeTelegram:
    def __init__(self, token="123456:TEST-token-for-the-fake-server"):
        self.token = token
        self.updates = []         # [{"update_id": n, "message": {...}}]
        self.sent = []            # [(chat_id, text)]
        self.calls = []           # [method]
        self.sent_full = []       # [(chat_id, message_id, text, inline keyboard rows)]
        self.edits = []           # [(chat_id, message_id, text)]
        self.answers = []         # [(text, show_alert)]
        self.commands = {}        # language_code -> [command names]
        self.send_errors = []     # queue of (error_code, parameters) answers for the next sendMessage calls
        self.next_id = 100
        self.lock = threading.Lock()
        fake = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
                status, out = fake.answer(self.path, body)
                data = json.dumps(out).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *a):
                pass

        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = "http://127.0.0.1:%d" % self.httpd.server_address[1]
        threading.Thread(target=self.httpd.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True).start()

    def stop(self):
        self.httpd.shutdown()
        self.httpd.server_close()

    # -- test helpers ------------------------------------------------------------------------------
    def say(self, chat, text, lang="en", first_name="Anna", chat_type="private"):
        with self.lock:
            self.next_id += 1
            self.updates.append({"update_id": self.next_id, "message": {
                "message_id": self.next_id, "chat": {"id": chat, "type": chat_type},
                "from": {"id": chat, "first_name": first_name, "language_code": lang}, "text": text}})

    def press(self, chat, label, lang="en"):
        """The user taps the button whose label contains `label` on the newest message that has one."""
        with self.lock:
            for c, mid, text, rows in reversed(self.sent_full):
                if c == chat and rows:
                    for row in rows:
                        for b in row:
                            if label in b["text"]:
                                self.next_id += 1
                                self.updates.append({"update_id": self.next_id, "callback_query": {
                                    "id": "cq%d" % self.next_id, "from": {"id": chat, "first_name": "X", "language_code": lang},
                                    "message": {"message_id": mid, "chat": {"id": chat, "type": "private"}}, "data": b["callback_data"]}})
                                return
        raise AssertionError("no button %r for chat %s" % (label, chat))

    def buttons(self, chat):
        for c, mid, text, rows in reversed(self.sent_full):
            if c == chat and rows:
                return [b["text"] for row in rows for b in row]
        return []

    def texts(self, chat=None):
        return [t for c, t in self.sent if chat is None or c == chat]

    def last(self, chat=None):
        texts = self.texts(chat)
        return texts[-1] if texts else None

    # -- the API -----------------------------------------------------------------------------------
    def answer(self, path, body):
        prefix = "/bot%s/" % self.token
        if not path.startswith(prefix):
            return 401, {"ok": False, "error_code": 401, "description": "Unauthorized"}
        method = path[len(prefix):]
        with self.lock:
            self.calls.append(method)
        if method == "getMe":
            return 200, {"ok": True, "result": {"id": 123456, "is_bot": True, "username": "mytv_test_bot"}}
        if method == "setMyCommands":
            self.commands[body.get("language_code", "")] = [c["command"] for c in body["commands"]]
            return 200, {"ok": True, "result": True}
        if method == "getUpdates":
            offset, deadline = body.get("offset", 0), time.time() + min(body.get("timeout", 0), 0.3)
            while True:
                with self.lock:
                    self.updates = [u for u in self.updates if u["update_id"] >= offset]
                    got = list(self.updates)
                if got or time.time() >= deadline:
                    return 200, {"ok": True, "result": got}
                time.sleep(0.02)
        if method == "sendMessage":
            with self.lock:
                if self.send_errors:
                    code, params = self.send_errors.pop(0)
                    return code, {"ok": False, "error_code": code, "description": "error", "parameters": params}
                self.sent.append((body["chat_id"], body["text"]))
                mid = len(self.sent)
                self.sent_full.append((body["chat_id"], mid, body["text"], (body.get("reply_markup") or {}).get("inline_keyboard")))
            return 200, {"ok": True, "result": {"message_id": mid}}
        if method == "editMessageText":
            with self.lock:
                self.edits.append((body["chat_id"], body["message_id"], body["text"]))
                self.sent_full = [(c, m, t, None if (c, m) == (body["chat_id"], body["message_id"]) else r) for c, m, t, r in self.sent_full]
            return 200, {"ok": True, "result": True}
        if method == "answerCallbackQuery":
            with self.lock:
                self.answers.append((body.get("text", ""), bool(body.get("show_alert"))))
            return 200, {"ok": True, "result": True}
        return 404, {"ok": False, "error_code": 404, "description": "Not Found"}
