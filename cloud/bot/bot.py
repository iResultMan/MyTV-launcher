#!/usr/bin/env python3
"""MyTV Telegram bot: one bot, many families. A client of the cloud link (docs/cloud-protocol.md v1).

Long polling against the Telegram Bot API, standard library only. A user pairs a TV with the 6-digit code shown
on its screen; the bot keeps the parent token per Telegram chat and turns commands into cloud commands.

Configuration (environment, or the files named below):
  MYTV_BOT_TOKEN / MYTV_BOT_TOKEN_FILE   bot token from @BotFather (default file ~/.config/mytv-bot/token)
  MYTV_CLOUD_URL                         default https://tv.secondoffice.ru
  MYTV_CLOUD_KEY_FILE                    optional shared key with the server (default ~/.config/mytv-bot/cloud-key)
  MYTV_BOT_DB                            default ~/.local/share/mytv-bot/bot.sqlite
  MYTV_TELEGRAM_API                      default https://api.telegram.org (only tests change it)
"""
import argparse
import json
import logging
import os
import re
import sqlite3
import stat
import sys
import threading
import time
import urllib.error
import urllib.request

log = logging.getLogger("mytv-bot")

CODE_RE = re.compile(r"\s*(\d{3})[\s-]?(\d{3})\s*")
MAX_TVS = 5
NOTE_MAX = 140
OFF_MAX_MIN = 1440
OFFLINE_NOTICE_S = 15
POLL_DELAYS = (1, 2, 3, 5, 8, 13, 15)     # seconds between checks of a pending command; the last one repeats
DEFAULT_TTL_S = 600

TEXT = {
    "en": {
        "start": "I lock your family's TV, show it a message or switch it off, right from Telegram.\n"
                 "On the TV open the cloud link in the MyTV launcher and send me the 6-digit code it shows.",
        "help": "/lock [note]: lock the TV\n/unlock: unlock it\n/message <text>: show a message\n/off [minutes]: switch off (0 cancels)\n"
                "/status: what the TV is doing\n/people: who has access, /remove <number>\n/tvs: your TVs, /rename <name>, /forget",
        "no_tv": "No TV paired yet. Send me the 6-digit code from the TV screen.",
        "paired": "✅ Paired: {tv}. Try /status or /lock.",
        "bad_code": "That code is wrong or expired. Look at the TV screen for a fresh one.",
        "too_many": "Too many tries. Wait 5 minutes and try again.",
        "full": "This TV already has 8 phones paired. Remove one first.",
        "tv_limit": "You can pair up to 5 TVs. /forget one first.",
        "down": "I cannot reach the cloud right now. Try again in a minute.",
        "rate": "Too many requests. Wait a minute.",
        "revoked": "{tv} no longer accepts me (it was unpaired). Pair again with a new code.",
        "private": "Please write to me in a private chat.",
        "unknown": "I do not know that command. /help",
        "usage_message": "Usage: /message <text>, up to 140 characters.",
        "too_long": "Too long: up to 140 characters.",
        "usage_off": "Usage: /off [minutes], up to 1440; 0 cancels.",
        "usage_rename": "Usage: /rename <new name>",
        "renamed": "Renamed to {tv}.",
        "forgot": "Forgot {tv}.",
        "list_head": "Your TVs:",
        "current": "current",
        "list_hint": "/tvs <number> switches.",
        "switched": "Now using {tv}.",
        "bad_number": "There is no TV with that number. /tvs",
        "refused": "The cloud refused that command.",
        "ok_lock": "🔒 {p}locked.",
        "ok_unlock": "🔓 {p}unlocked.",
        "ok_message": "💬 {p}message shown.",
        "ok_off": "⏻ {p}switching off {when}.",
        "ok_off_cancel": "⏻ {p}switch-off cancelled.",
        "off_now": "now",
        "off_in": "in {n} min",
        "failed": "❌ {p}the TV could not do it{detail}.",
        "expired": "⌛ {p}the TV did not pick it up in time, nothing was done.",
        "waiting": "📴 {p}the TV is offline. I will keep trying for {n} min.",
        "st_online": "Online",
        "st_offline": "Offline",
        "st_locked": "locked",
        "st_unlocked": "unlocked",
        "st_schedule": "locked by schedule",
        "st_app": "App: {app}",
        "st_screen_off": "Screen off",
        "st_helper_off": "Helper off",
        "st_seen": "Last seen: {ago}",
        "st_none": "No data from the TV yet.",
        "ago_now": "just now",
        "ago_s": "{n} s ago",
        "ago_min": "{n} min ago",
        "ago_h": "{n} h ago",
        "ago_d": "{n} d ago",
        "default_name": "TV",
        "paired_owner": "✅ Paired: {tv}. You are its owner: anyone else who wants control will ask you first. Try /status or /lock.",
        "already": "You already control {tv}.",
        "asked": "⏳ {tv} already has an owner. I asked them to allow you and will tell you the answer.",
        "asked_unreachable": "⏳ {tv} already has an owner, but I cannot reach them here. Ask them to allow you.",
        "too_many_requests": "Too many requests are waiting for this TV. Ask the owner to answer them first.",
        "request": "🔑 {who} asks to control {tv}. Allow?",
        "btn_allow": "✅ Allow",
        "btn_deny": "❌ Deny",
        "approved_owner": "✅ {who} may now control {tv}.",
        "denied_owner": "❌ Declined: {who}.",
        "approved_user": "✅ The owner allowed it: you can control {tv}.",
        "denied_user": "❌ The owner declined your request for {tv}.",
        "request_gone": "This request is gone: it was answered or expired.",
        "owner_only": "Only the owner can do that.",
        "pending": "⏳ {tv} is waiting for the owner's OK.",
        "people_head": "People with access to {tv}:",
        "tag_owner": "owner",
        "tag_you": "you",
        "tag_waiting": "waiting",
        "people_hint": "/remove <number> takes someone's access away.",
        "usage_remove": "Usage: /remove <number>, see /people.",
        "remove_self": "That is you. Use /forget to leave.",
        "removed": "Removed {who}.",
        "removed_user": "You no longer control {tv}.",
        "forgot_owner": "Forgot {tv}. {who} is its owner now.",
        "tag_pending_tv": "waiting for approval",
    },
    "ru": {
        "start": "Я блокирую телевизор вашей семьи, показываю на нём сообщение или выключаю его прямо из Telegram.\n"
                 "На ТВ откройте облачную связь в лаунчере MyTV и пришлите мне 6-значный код с экрана.",
        "help": "/lock [заметка]: заблокировать ТВ\n/unlock: разблокировать\n/message <текст>: показать сообщение\n"
                "/off [минуты]: выключить (0 отменяет)\n/status: что делает ТВ\n/people: у кого есть доступ, /remove <номер>\n/tvs: ваши ТВ, /rename <имя>, /forget",
        "no_tv": "ТВ ещё не подключён. Пришлите мне 6-значный код с экрана ТВ.",
        "paired": "✅ Подключено: {tv}. Попробуйте /status или /lock.",
        "bad_code": "Код неверный или устарел. Посмотрите свежий на экране ТВ.",
        "too_many": "Слишком много попыток. Подождите 5 минут.",
        "full": "К этому ТВ уже подключено 8 телефонов. Сначала удалите один.",
        "tv_limit": "Можно подключить до 5 ТВ. Сначала /forget одного.",
        "down": "Сейчас не достучаться до облака. Повторите через минуту.",
        "rate": "Слишком много запросов. Подождите минуту.",
        "revoked": "{tv} больше меня не принимает (подключение удалено). Подключите заново новым кодом.",
        "private": "Пожалуйста, напишите мне в личные сообщения.",
        "unknown": "Не знаю такой команды. /help",
        "usage_message": "Использование: /message <текст>, до 140 символов.",
        "too_long": "Слишком длинно: до 140 символов.",
        "usage_off": "Использование: /off [минуты], до 1440; 0 отменяет.",
        "usage_rename": "Использование: /rename <новое имя>",
        "renamed": "Теперь это {tv}.",
        "forgot": "{tv} забыт.",
        "list_head": "Ваши ТВ:",
        "current": "текущий",
        "list_hint": "/tvs <номер> переключает.",
        "switched": "Теперь работаю с {tv}.",
        "bad_number": "ТВ с таким номером нет. /tvs",
        "refused": "Облако отклонило команду.",
        "ok_lock": "🔒 {p}заблокирован.",
        "ok_unlock": "🔓 {p}разблокирован.",
        "ok_message": "💬 {p}сообщение показано.",
        "ok_off": "⏻ {p}выключится {when}.",
        "ok_off_cancel": "⏻ {p}выключение отменено.",
        "off_now": "сейчас",
        "off_in": "через {n} мин",
        "failed": "❌ {p}ТВ не смог выполнить{detail}.",
        "expired": "⌛ {p}ТВ не успел принять команду, ничего не сделано.",
        "waiting": "📴 {p}ТВ не в сети. Буду пробовать ещё {n} мин.",
        "st_online": "В сети",
        "st_offline": "Не в сети",
        "st_locked": "заблокирован",
        "st_unlocked": "не заблокирован",
        "st_schedule": "заблокирован по расписанию",
        "st_app": "Приложение: {app}",
        "st_screen_off": "Экран выключен",
        "st_helper_off": "Помощник выключен",
        "st_seen": "Был в сети: {ago}",
        "st_none": "От ТВ пока нет данных.",
        "ago_now": "только что",
        "ago_s": "{n} с назад",
        "ago_min": "{n} мин назад",
        "ago_h": "{n} ч назад",
        "ago_d": "{n} дн. назад",
        "default_name": "ТВ",
        "paired_owner": "✅ Подключено: {tv}. Вы его владелец: всем остальным, кто захочет управлять, понадобится ваше разрешение. Попробуйте /status или /lock.",
        "already": "Вы уже управляете: {tv}.",
        "asked": "⏳ У {tv} уже есть владелец. Я спросил его, можно ли вас пустить, и сообщу ответ.",
        "asked_unreachable": "⏳ У {tv} уже есть владелец, но я не могу до него достучаться. Попросите его разрешить вам доступ.",
        "too_many_requests": "К этому ТВ уже ждёт много запросов. Пусть владелец сначала ответит на них.",
        "request": "🔑 {who} просит управлять: {tv}. Разрешить?",
        "btn_allow": "✅ Разрешить",
        "btn_deny": "❌ Отказать",
        "approved_owner": "✅ {who} теперь может управлять: {tv}.",
        "denied_owner": "❌ Отказано: {who}.",
        "approved_user": "✅ Владелец разрешил: вы можете управлять {tv}.",
        "denied_user": "❌ Владелец отказал в доступе к {tv}.",
        "request_gone": "Этого запроса уже нет: на него ответили или он устарел.",
        "owner_only": "Это может сделать только владелец.",
        "pending": "⏳ {tv} ждёт разрешения владельца.",
        "people_head": "Кто имеет доступ к {tv}:",
        "tag_owner": "владелец",
        "tag_you": "вы",
        "tag_waiting": "ждёт",
        "people_hint": "/remove <номер> забирает доступ.",
        "usage_remove": "Использование: /remove <номер>, см. /people.",
        "remove_self": "Это вы. Чтобы выйти, используйте /forget.",
        "removed": "{who} удалён.",
        "removed_user": "Вы больше не управляете: {tv}.",
        "forgot_owner": "{tv} забыт. Теперь владелец: {who}.",
        "tag_pending_tv": "ждёт разрешения",
    },
}


def lang_of(user):
    code = ((user or {}).get("language_code") or "").lower()
    return "ru" if code.startswith("ru") else "en"


def tr(lang, key, **kw):
    return TEXT[lang][key].format(**kw)


def ago(lang, seconds):
    s = max(0, int(seconds))
    if s < 10:
        return tr(lang, "ago_now")
    if s < 60:
        return tr(lang, "ago_s", n=s)
    if s < 3600:
        return tr(lang, "ago_min", n=s // 60)
    if s < 86400:
        return tr(lang, "ago_h", n=s // 3600)
    return tr(lang, "ago_d", n=s // 86400)


class RedactingFormatter(logging.Formatter):
    """Removes secrets from every log line, tracebacks included."""

    def __init__(self, secrets, *a, **kw):
        super().__init__(*a, **kw)
        self.secrets = [s for s in secrets if s]

    def format(self, record):
        text = super().format(record)
        for s in self.secrets:
            text = text.replace(s, "***")
        return text


class CloudDown(Exception):
    pass


class TelegramError(Exception):
    def __init__(self, code, description, retry_after=0):
        super().__init__("%s %s" % (code, description))
        self.code, self.retry_after = code, retry_after


class Telegram:
    def __init__(self, token, base="https://api.telegram.org"):
        self.url = "%s/bot%s/" % (base.rstrip("/"), token)

    def call(self, method, params=None, timeout=15):
        req = urllib.request.Request(self.url + method, json.dumps(params or {}).encode(), {"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                data = json.loads(r.read())
        except urllib.error.HTTPError as e:
            with e:
                raw = e.read()
            try:
                data = json.loads(raw)
            except ValueError:
                raise TelegramError(e.code, "http error")
        except (urllib.error.URLError, OSError, ValueError) as e:
            raise TelegramError(0, "network: %s" % type(e).__name__)
        if not data.get("ok"):
            raise TelegramError(data.get("error_code", 0), str(data.get("description", ""))[:100],
                                (data.get("parameters") or {}).get("retry_after", 0))
        return data.get("result")


class Cloud:
    def __init__(self, base, key=None, timeout=12):
        self.base, self.key, self.timeout = base.rstrip("/"), key, timeout

    def request(self, method, path, body=None, token=None, subject=None):
        headers = {"X-Protocol": "1", "Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        if subject and self.key:
            headers["X-Bot-Key"], headers["X-Subject"] = self.key, subject
        req = urllib.request.Request(self.base + path, json.dumps(body).encode() if body is not None else None, headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                status, raw = r.status, r.read()
        except urllib.error.HTTPError as e:
            with e:
                status, raw = e.code, e.read()
        except (urllib.error.URLError, OSError) as e:
            raise CloudDown(type(e).__name__)
        try:
            return status, json.loads(raw or b"{}")
        except ValueError:
            raise CloudDown("bad answer %d" % status)


class Db:
    def __init__(self, path):
        self.lock = threading.RLock()
        self.db = sqlite3.connect(path, check_same_thread=False)
        with self.lock:
            self.db.executescript("""
            create table if not exists tvs(
                id integer primary key autoincrement, chat_id integer not null, device_id text not null, name text not null,
                parent_id text not null, parent_token text not null, pending integer not null default 0, unique(chat_id, device_id));
            create table if not exists chats(chat_id integer primary key, current_device text, lang text);
            create table if not exists meta(k text primary key, v text);
            """)
            self.db.commit()
        if os.path.exists(path):
            os.chmod(path, 0o600)

    def close(self):
        with self.lock:
            self.db.close()

    def tvs(self, chat):
        with self.lock:
            rows = self.db.execute("select device_id, name, parent_id, parent_token, pending from tvs where chat_id=? order by id", (chat,)).fetchall()
        return [dict(device_id=r[0], name=r[1], parent_id=r[2], token=r[3], pending=bool(r[4])) for r in rows]

    def holders(self, device_id, exclude_chat=None):
        """Every chat's entry for one TV (for finding its owner and the people to tell)."""
        with self.lock:
            rows = self.db.execute("select chat_id, name, parent_id, parent_token, pending from tvs where device_id=? and chat_id!=? order by id",
                                   (device_id, exclude_chat or 0)).fetchall()
        return [dict(chat=r[0], name=r[1], parent_id=r[2], token=r[3], pending=bool(r[4])) for r in rows]

    def set_pending(self, chat, device_id, pending):
        with self.lock:
            self.db.execute("update tvs set pending=? where chat_id=? and device_id=?", (int(pending), chat, device_id))
            self.db.commit()

    def set_lang(self, chat, lang):
        with self.lock:
            self.db.execute("insert into chats(chat_id, lang) values(?,?) on conflict(chat_id) do update set lang=excluded.lang", (chat, lang))
            self.db.commit()

    def lang(self, chat):
        with self.lock:
            row = self.db.execute("select lang from chats where chat_id=?", (chat,)).fetchone()
        return row[0] if row and row[0] else "en"

    def current(self, chat):
        """The TV commands go to: the only one, or the one picked with /tvs."""
        tvs = self.tvs(chat)
        if len(tvs) <= 1:
            return tvs[0] if tvs else None
        with self.lock:
            row = self.db.execute("select current_device from chats where chat_id=?", (chat,)).fetchone()
        for tv in tvs:
            if row and tv["device_id"] == row[0]:
                return tv
        return tvs[0]

    def set_current(self, chat, device_id):
        with self.lock:
            self.db.execute("insert into chats(chat_id, current_device) values(?,?) on conflict(chat_id) do update set current_device=excluded.current_device",
                            (chat, device_id))
            self.db.commit()

    def add_tv(self, chat, device_id, name, parent_id, token, pending=False):
        with self.lock:
            self.db.execute("delete from tvs where chat_id=? and device_id=?", (chat, device_id))
            self.db.execute("insert into tvs(chat_id, device_id, name, parent_id, parent_token, pending) values(?,?,?,?,?,?)",
                            (chat, device_id, name, parent_id, token, int(pending)))
            self.db.commit()
        self.set_current(chat, device_id)

    def rename(self, chat, device_id, name):
        with self.lock:
            self.db.execute("update tvs set name=? where chat_id=? and device_id=?", (name, chat, device_id))
            self.db.commit()

    def remove(self, chat, device_id):
        with self.lock:
            self.db.execute("delete from tvs where chat_id=? and device_id=?", (chat, device_id))
            self.db.commit()

    def offset(self):
        with self.lock:
            row = self.db.execute("select v from meta where k='offset'").fetchone()
        return int(row[0]) if row else 0

    def set_offset(self, value):
        with self.lock:
            self.db.execute("insert into meta(k, v) values('offset', ?) on conflict(k) do update set v=excluded.v", (str(value),))
            self.db.commit()


class Pending:
    def __init__(self, chat, lang, tv, cid, kind, args, deadline, now):
        self.chat, self.lang, self.tv, self.cid, self.kind, self.args = chat, lang, tv, cid, kind, args
        self.deadline, self.started, self.tries, self.warned = deadline, now, 0, False
        self.next = now + POLL_DELAYS[0]


class Bot:
    def __init__(self, telegram, cloud, db, clock=time.monotonic, wall=time.time):
        self.tg, self.cloud, self.db, self.clock, self.wall = telegram, cloud, db, clock, wall
        self.pending = []
        self.pending_lock = threading.Lock()
        self.username = None

    # -- Telegram plumbing -------------------------------------------------------------------------
    def say(self, chat, text, buttons=None):
        params = {"chat_id": chat, "text": text, "disable_web_page_preview": True}
        if buttons:
            params["reply_markup"] = {"inline_keyboard": [[{"text": label, "callback_data": data} for label, data in buttons]]}
        for attempt in (0, 1):
            try:
                self.tg.call("sendMessage", params)
                return
            except TelegramError as e:
                if e.code == 429 and attempt == 0:
                    time.sleep(min(e.retry_after or 1, 30))
                    continue
                log.warning("sendMessage failed: %s", e)
                return

    def quietly(self, method, params):
        try:
            self.tg.call(method, params)
        except TelegramError as e:
            log.warning("%s failed: %s", method, e)

    def setup(self):
        me = self.tg.call("getMe")
        self.username = me.get("username")
        rows = [("lock", "Lock the TV", "Заблокировать ТВ"), ("unlock", "Unlock the TV", "Разблокировать ТВ"),
                ("message", "Show a message", "Показать сообщение"), ("off", "Switch off", "Выключить"),
                ("status", "What the TV is doing", "Что делает ТВ"), ("people", "Who has access", "У кого есть доступ"),
                ("remove", "Take someone's access away", "Забрать чей-то доступ"), ("tvs", "Your TVs", "Ваши ТВ"),
                ("rename", "Rename the TV", "Переименовать ТВ"), ("forget", "Forget the TV", "Забыть ТВ")]
        for lang_code, i in (("", 1), ("ru", 2)):
            params = {"commands": [{"command": r[0], "description": r[i]} for r in rows]}
            if lang_code:
                params["language_code"] = lang_code
            self.quietly("setMyCommands", params)
        return me

    def poll_once(self, timeout=50):
        """One getUpdates round; returns the number of updates handled."""
        updates = self.tg.call("getUpdates", {"offset": self.db.offset(), "timeout": timeout, "allowed_updates": ["message", "callback_query"]},
                               timeout=timeout + 15)
        for u in updates:
            try:
                self.handle(u)
            except Exception:
                log.exception("update %s failed", u.get("update_id"))
            self.db.set_offset(u["update_id"] + 1)    # confirmed even when it failed: a replayed /lock is worse than a lost one
        return len(updates)

    def run(self, stop):
        threading.Thread(target=self.watch, args=(stop,), daemon=True).start()
        delay = 1
        while not stop.is_set():
            try:
                self.poll_once()
                delay = 1
            except TelegramError as e:
                log.warning("getUpdates failed: %s", e)
                if e.code in (401, 404):
                    raise SystemExit("Telegram rejected the bot token: check the token file")
                stop.wait(min(max(delay, e.retry_after), 60))
                delay = min(delay * 2, 60)

    def watch(self, stop):
        while not stop.wait(1):
            try:
                self.check_pending()
            except Exception:
                log.exception("watching commands failed")

    # -- messages ----------------------------------------------------------------------------------
    def handle(self, update):
        if update.get("callback_query"):
            return self.callback(update["callback_query"])
        msg = update.get("message")
        if not msg or not isinstance(msg.get("text"), str):
            return
        chat, lang = msg["chat"]["id"], lang_of(msg.get("from"))
        if msg["chat"].get("type") != "private":
            return self.say(chat, tr(lang, "private"))
        self.db.set_lang(chat, lang)
        text = msg["text"].strip()
        name = ((msg.get("from") or {}).get("first_name") or "").strip()[:30] or "Telegram"
        m = re.match(r"/(\w+)(?:@\w+)?(?:\s+(.*))?$", text, re.S)
        if not m:
            code = CODE_RE.fullmatch(text)
            return self.pair(chat, lang, code.group(1) + code.group(2), name) if code else self.say(chat, tr(lang, "start" if not self.db.tvs(chat) else "help"))
        cmd, arg = m.group(1).lower(), (m.group(2) or "").strip()
        if cmd == "start":
            code = CODE_RE.fullmatch(arg)
            return self.pair(chat, lang, code.group(1) + code.group(2), name) if code else self.say(chat, tr(lang, "start"))
        if cmd == "help":
            return self.say(chat, tr(lang, "start") + "\n\n" + tr(lang, "help"))
        handler = {"lock": self.lock, "unlock": self.unlock, "message": self.message, "off": self.off, "status": self.status,
                   "people": self.people, "remove": self.remove, "tvs": self.tvs, "tv": self.tvs, "rename": self.rename,
                   "forget": self.forget}.get(cmd)
        if not handler:
            return self.say(chat, tr(lang, "unknown"))
        handler(chat, lang, arg)

    def pair(self, chat, lang, code, name):
        tvs = self.db.tvs(chat)
        try:
            status, body = self.cloud.request("POST", "/v1/pair", {"code": code, "name": name}, subject="tg-%d" % chat)
        except CloudDown:
            return self.say(chat, tr(lang, "down"))
        if status == 403:
            return self.say(chat, tr(lang, "bad_code"))
        if status == 429:
            return self.say(chat, tr(lang, "too_many"))
        if status == 409:
            return self.say(chat, tr(lang, "too_many_requests" if body.get("error") == "too many requests" else "full"))
        if status != 200:
            return self.say(chat, tr(lang, "refused"))
        did, pid, token = body["device_id"], body["parent_id"], body["parent_token"]
        label = (body.get("device_name") or "").strip()[:30] or tr(lang, "default_name")
        old = next((t for t in tvs if t["device_id"] == did), None)
        if old:
            try:
                known = self.cloud.request("GET", "/v1/parents", token=old["token"])[0]
            except CloudDown:
                known = 200
            if known in (200, 403):       # already paired (or already asked): the new token is not needed
                self.forget_remote(pid, token)
                return self.say(chat, tr(lang, "already" if known == 200 else "pending", tv=old["name"]))
            label = old["name"]
        elif len(tvs) >= MAX_TVS:
            self.forget_remote(pid, token)
            return self.say(chat, tr(lang, "tv_limit"))
        waiting = body.get("status", "active") != "active"
        self.db.add_tv(chat, did, label, pid, token, pending=waiting)
        if not waiting:
            return self.say(chat, tr(lang, "paired_owner" if body.get("owner", True) else "paired", tv=label))
        owner = self.find_owner(did, chat)
        if not owner:
            return self.say(chat, tr(lang, "asked_unreachable", tv=label))
        self.send_request(owner["chat"], owner["name"], name, did, pid)
        self.say(chat, tr(lang, "asked", tv=label))

    def find_owner(self, device_id, exclude_chat):
        """The bot user who owns this TV, according to the server; None when the owner is not reachable through this bot."""
        for h in self.db.holders(device_id, exclude_chat):
            if h["pending"]:
                continue
            try:
                code, body = self.cloud.request("GET", "/v1/parents", token=h["token"])
            except CloudDown:
                return None
            if code == 200 and body.get("owner"):
                return h
        return None

    def send_request(self, owner_chat, tv_name, who, device_id, parent_id):
        lang = self.db.lang(owner_chat)
        self.say(owner_chat, tr(lang, "request", who=who, tv=tv_name),
                 [(tr(lang, "btn_allow"), "a:%s:%s" % (device_id, parent_id)), (tr(lang, "btn_deny"), "d:%s:%s" % (device_id, parent_id))])

    def callback(self, cq):
        """The owner pressed Allow or Deny under a request."""
        msg = cq.get("message") or {}
        chat, lang = (msg.get("chat") or {}).get("id"), lang_of(cq.get("from"))
        m = re.fullmatch(r"([ad]):([0-9a-f]{16}):([0-9a-f]{16})", cq.get("data") or "")
        owner_tv = next((t for t in self.db.tvs(chat) if m and t["device_id"] == m.group(2)), None) if chat else None
        if not owner_tv:
            return self.quietly("answerCallbackQuery", {"callback_query_id": cq["id"], "text": tr(lang, "owner_only")})
        self.db.set_lang(chat, lang)
        act, did, pid = m.groups()

        def finish(text, toast=None):
            self.quietly("editMessageText", {"chat_id": chat, "message_id": msg["message_id"], "text": text, "reply_markup": {"inline_keyboard": []}})
            self.quietly("answerCallbackQuery", {"callback_query_id": cq["id"], "text": toast or ""})

        try:
            code, body = self.cloud.request("GET", "/v1/parents", token=owner_tv["token"])
            who = next((p["name"] for p in body.get("parents", []) if p["id"] == pid and p["status"] == "pending"), None) if code == 200 else None
            if code == 200 and not body.get("owner"):
                return self.quietly("answerCallbackQuery", {"callback_query_id": cq["id"], "text": tr(lang, "owner_only"), "show_alert": True})
            if code == 401:
                self.db.remove(chat, did)
                return finish(tr(lang, "revoked", tv=owner_tv["name"]))
            if code != 200 or not who:
                return finish(tr(lang, "request_gone"))
            if act == "a":
                code, _ = self.cloud.request("POST", "/v1/parents/%s/approve" % pid, token=owner_tv["token"])
            else:
                code, _ = self.cloud.request("DELETE", "/v1/parents/" + pid, token=owner_tv["token"])
        except CloudDown:
            return self.quietly("answerCallbackQuery", {"callback_query_id": cq["id"], "text": tr(lang, "down"), "show_alert": True})
        if code != 200:
            return finish(tr(lang, "request_gone"))
        finish(tr(lang, "approved_owner" if act == "a" else "denied_owner", who=who, tv=owner_tv["name"]))
        for h in self.db.holders(did, chat):
            if h["parent_id"] != pid:
                continue
            their = self.db.lang(h["chat"])
            if act == "a":
                self.db.set_pending(h["chat"], did, False)
                self.say(h["chat"], tr(their, "approved_user", tv=h["name"]))
            else:
                self.db.remove(h["chat"], did)
                self.say(h["chat"], tr(their, "denied_user", tv=h["name"]))

    def forget_remote(self, parent_id, token):
        try:
            self.cloud.request("DELETE", "/v1/parents/" + parent_id, token=token)
        except CloudDown:
            pass

    def current_or_say(self, chat, lang):
        tv = self.db.current(chat)
        if not tv:
            self.say(chat, tr(lang, "no_tv"))
        return tv

    def prefix(self, chat, tv):
        return tv["name"] + ": " if len(self.db.tvs(chat)) > 1 else ""

    def lost(self, chat, lang, tv):
        """The cloud no longer knows this token."""
        self.db.remove(chat, tv["device_id"])
        self.say(chat, tr(lang, "revoked", tv=tv["name"]))

    def api(self, chat, lang, tv, method, path, body=None):
        """Call the cloud as this chat's parent. On any failure the user is told and None comes back."""
        try:
            code, out = self.cloud.request(method, path, body, token=tv["token"])
        except CloudDown:
            return self.say(chat, tr(lang, "down"))
        if code == 200:
            if tv["pending"]:
                self.db.set_pending(chat, tv["device_id"], False)
            return out
        if code == 401:
            self.lost(chat, lang, tv)
        elif code == 403 and out.get("error") == "pending approval":
            self.say(chat, tr(lang, "pending", tv=tv["name"]))
        elif code == 403:
            self.say(chat, tr(lang, "owner_only"))
        elif code == 429:
            self.say(chat, tr(lang, "rate"))
        else:
            self.say(chat, tr(lang, "refused"))
        return None

    def send_command(self, chat, lang, tv, kind, args=None, ttl=DEFAULT_TTL_S):
        body = self.api(chat, lang, tv, "POST", "/v1/commands", {"type": kind, "args": args or {}, "ttl_s": ttl})
        if body is None:
            return
        now = self.clock()
        with self.pending_lock:
            self.pending.append(Pending(chat, lang, tv, body["id"], kind, args or {}, now + ttl + 5, now))

    def lock(self, chat, lang, arg):
        tv = self.current_or_say(chat, lang)
        if tv and len(arg) > NOTE_MAX:
            return self.say(chat, tr(lang, "too_long"))
        if tv:
            self.send_command(chat, lang, tv, "lock", {"note": arg} if arg else {})

    def unlock(self, chat, lang, arg):
        tv = self.current_or_say(chat, lang)
        if tv:
            self.send_command(chat, lang, tv, "unlock")

    def message(self, chat, lang, arg):
        tv = self.current_or_say(chat, lang)
        if not tv:
            return
        if not arg:
            return self.say(chat, tr(lang, "usage_message"))
        if len(arg) > NOTE_MAX:
            return self.say(chat, tr(lang, "too_long"))
        self.send_command(chat, lang, tv, "message", {"text": arg})

    def off(self, chat, lang, arg):
        tv = self.current_or_say(chat, lang)
        if not tv:
            return
        if arg and not re.fullmatch(r"\d{1,4}", arg):
            return self.say(chat, tr(lang, "usage_off"))
        minutes = int(arg) if arg else None
        if minutes is not None and minutes > OFF_MAX_MIN:
            return self.say(chat, tr(lang, "usage_off"))
        if minutes == 0:
            return self.send_command(chat, lang, tv, "power_off", {"cancel": True})
        self.send_command(chat, lang, tv, "power_off", {"delay_s": (minutes or 0) * 60})

    def status(self, chat, lang, arg):
        tv = self.current_or_say(chat, lang)
        st = self.api(chat, lang, tv, "GET", "/v1/device/state") if tv else None
        if st is None:
            return
        lines = ["📺 " + tv["name"]]
        if not st.get("last_seen") or not any(k in st for k in ("locked", "scheduled", "service", "screen")):
            lines.append(tr(lang, "st_online" if st.get("online") else "st_offline"))
            lines.append(tr(lang, "st_none"))
            return self.say(chat, "\n".join(lines))
        locked = st.get("locked")
        lock = tr(lang, "st_schedule" if locked and st.get("scheduled") else "st_locked" if locked else "st_unlocked")
        lines.append("%s · %s" % (tr(lang, "st_online" if st.get("online") else "st_offline"), lock))
        screen = st.get("screen") or {}
        if screen.get("on") is False:
            lines.append(tr(lang, "st_screen_off"))
        elif screen.get("label") or screen.get("pkg"):
            lines.append(tr(lang, "st_app", app=screen.get("label") or screen.get("pkg")))
        if st.get("service") is False:
            lines.append(tr(lang, "st_helper_off"))
        lines.append(tr(lang, "st_seen", ago=ago(lang, self.wall() - st["last_seen"] / 1000)))
        self.say(chat, "\n".join(lines))

    def people(self, chat, lang, arg):
        tv = self.current_or_say(chat, lang)
        out = self.api(chat, lang, tv, "GET", "/v1/parents") if tv else None
        if out is None:
            return
        lines = [tr(lang, "people_head", tv=tv["name"])]
        for i, p in enumerate(out["parents"], 1):
            tags = [tr(lang, t) for t, on in (("tag_owner", p["owner"]), ("tag_you", p["id"] == out["you"]), ("tag_waiting", p["status"] != "active")) if on]
            lines.append("%d. %s%s" % (i, p["name"], " (%s)" % ", ".join(tags) if tags else ""))
        if out["owner"]:
            lines.append(tr(lang, "people_hint"))
        self.say(chat, "\n".join(lines))
        if out["owner"]:      # waiting requests come again with their buttons
            for p in out["parents"]:
                if p["status"] != "active":
                    self.send_request(chat, tv["name"], p["name"], tv["device_id"], p["id"])

    def remove(self, chat, lang, arg):
        tv = self.current_or_say(chat, lang)
        if not tv:
            return
        if not arg.isdigit():
            return self.say(chat, tr(lang, "usage_remove"))
        out = self.api(chat, lang, tv, "GET", "/v1/parents")
        if out is None:
            return
        if not out["owner"]:
            return self.say(chat, tr(lang, "owner_only"))
        if not 1 <= int(arg) <= len(out["parents"]):
            return self.say(chat, tr(lang, "usage_remove"))
        target = out["parents"][int(arg) - 1]
        if target["id"] == out["you"]:
            return self.say(chat, tr(lang, "remove_self"))
        if self.api(chat, lang, tv, "DELETE", "/v1/parents/" + target["id"]) is None:
            return
        self.say(chat, tr(lang, "removed", who=target["name"]))
        for h in self.db.holders(tv["device_id"], chat):
            if h["parent_id"] == target["id"]:
                self.db.remove(h["chat"], tv["device_id"])
                self.say(h["chat"], tr(self.db.lang(h["chat"]), "removed_user", tv=h["name"]))

    def tvs(self, chat, lang, arg):
        tvs = self.db.tvs(chat)
        if not tvs:
            return self.say(chat, tr(lang, "no_tv"))
        if arg:
            if not arg.isdigit() or not 1 <= int(arg) <= len(tvs):
                return self.say(chat, tr(lang, "bad_number"))
            self.db.set_current(chat, tvs[int(arg) - 1]["device_id"])
            return self.say(chat, tr(lang, "switched", tv=tvs[int(arg) - 1]["name"]))
        cur = self.db.current(chat)
        lines = [tr(lang, "list_head")]
        for i, tv in enumerate(tvs, 1):
            tags = [tr(lang, t) for t, on in (("current", len(tvs) > 1 and tv["device_id"] == cur["device_id"]), ("tag_pending_tv", tv["pending"])) if on]
            lines.append("%d. %s%s" % (i, tv["name"], " (%s)" % ", ".join(tags) if tags else ""))
        if len(tvs) > 1:
            lines.append(tr(lang, "list_hint"))
        self.say(chat, "\n".join(lines))

    def rename(self, chat, lang, arg):
        tv = self.current_or_say(chat, lang)
        if not tv:
            return
        if not arg:
            return self.say(chat, tr(lang, "usage_rename"))
        label = " ".join(arg.split())[:30]
        self.db.rename(chat, tv["device_id"], label)
        self.say(chat, tr(lang, "renamed", tv=label))

    def forget(self, chat, lang, arg):
        tv = self.current_or_say(chat, lang)
        if not tv:
            return
        heir = None
        try:       # an owner who leaves hands the TV to the oldest member: say who
            code, out = self.cloud.request("GET", "/v1/parents", token=tv["token"])
            if code == 200 and out.get("owner"):
                heir = next((p["name"] for p in out["parents"] if p["id"] != out["you"] and p["status"] == "active"), None)
        except CloudDown:
            pass
        self.forget_remote(tv["parent_id"], tv["token"])
        self.db.remove(chat, tv["device_id"])
        self.say(chat, tr(lang, "forgot_owner", tv=tv["name"], who=heir) if heir else tr(lang, "forgot", tv=tv["name"]))

    # -- results of commands -----------------------------------------------------------------------
    def check_pending(self):
        """Look at every command whose time has come and tell its chat the outcome. Cheap when nothing is pending."""
        now = self.clock()
        with self.pending_lock:
            due = [p for p in self.pending if p.next <= now]
        for p in due:
            if self.check_one(p, now):
                with self.pending_lock:
                    if p in self.pending:
                        self.pending.remove(p)

    def check_one(self, p, now):
        """True when the command is finished with and can be forgotten."""
        if now > p.deadline:
            return True
        p.tries += 1
        p.next = now + POLL_DELAYS[min(p.tries, len(POLL_DELAYS) - 1)]
        try:
            code, body = self.cloud.request("GET", "/v1/commands/" + p.cid, token=p.tv["token"])
        except CloudDown:
            return False
        if code == 429:
            return False
        if code != 200:
            return True       # revoked or gone: nothing sensible to say
        status, prefix = body.get("status"), self.prefix(p.chat, p.tv)
        if status == "ok":
            self.say(p.chat, self.done_text(p, prefix))
            return True
        if status == "failed":
            detail = ": " + body["detail"] if body.get("detail") else ""
            self.say(p.chat, tr(p.lang, "failed", p=prefix, detail=detail))
            return True
        if status == "expired":
            self.say(p.chat, tr(p.lang, "expired", p=prefix))
            return True
        if status == "queued" and not p.warned and now - p.started >= OFFLINE_NOTICE_S:
            p.warned = True
            self.say(p.chat, tr(p.lang, "waiting", p=prefix, n=max(1, -(-int(p.deadline - 5 - now) // 60))))
        return False

    @staticmethod
    def done_text(p, prefix):
        if p.kind == "power_off":
            if p.args.get("cancel"):
                return tr(p.lang, "ok_off_cancel", p=prefix)
            delay = p.args.get("delay_s", 0)
            when = tr(p.lang, "off_in", n=delay // 60) if delay else tr(p.lang, "off_now")
            return tr(p.lang, "ok_off", p=prefix, when=when)
        return tr(p.lang, "ok_" + p.kind, p=prefix)


def read_secret_file(path):
    try:
        with open(path) as f:
            return f.read().strip() or None
    except OSError:
        return None


def load_token(env=os.environ):
    token = env.get("MYTV_BOT_TOKEN")
    path = env.get("MYTV_BOT_TOKEN_FILE") or os.path.expanduser("~/.config/mytv-bot/token")
    if not token:
        token = read_secret_file(path)
        if token and os.stat(path).st_mode & (stat.S_IRWXG | stat.S_IRWXO):
            log.warning("%s is readable by other users: chmod 600 it", path)
    if not token or not re.fullmatch(r"\d+:[A-Za-z0-9_-]{20,}", token):
        raise SystemExit("No valid bot token. Put the token from @BotFather into %s (chmod 600) or set MYTV_BOT_TOKEN." % path)
    return token


def main(argv=None):
    env = os.environ.get
    ap = argparse.ArgumentParser(description="MyTV Telegram bot")
    ap.add_argument("--cloud-url", default=env("MYTV_CLOUD_URL", "https://tv.secondoffice.ru"))
    ap.add_argument("--db", default=env("MYTV_BOT_DB", os.path.expanduser("~/.local/share/mytv-bot/bot.sqlite")))
    ap.add_argument("--key-file", default=env("MYTV_CLOUD_KEY_FILE", os.path.expanduser("~/.config/mytv-bot/cloud-key")))
    a = ap.parse_args(argv)
    token = load_token()
    key = read_secret_file(a.key_file)
    handler = logging.StreamHandler(sys.stderr)
    handler.setFormatter(RedactingFormatter([token, key], "%(levelname)s %(message)s"))
    logging.basicConfig(level=logging.INFO, handlers=[handler])
    os.makedirs(os.path.dirname(os.path.abspath(a.db)), mode=0o700, exist_ok=True)
    bot = Bot(Telegram(token, env("MYTV_TELEGRAM_API", "https://api.telegram.org")), Cloud(a.cloud_url, key), Db(a.db))
    for delay in (1, 2, 4, 8, 16, 32, 60, 60, 60, 60):     # tolerate a Telegram or network outage at start-up
        try:
            me = bot.setup()
            break
        except TelegramError as e:
            if e.code in (401, 404):
                raise SystemExit("Telegram rejected the bot token: check the token file")
            log.warning("start-up failed: %s; retrying in %ds", e, delay)
            time.sleep(delay)
    else:
        raise SystemExit("Cannot reach Telegram")
    log.info("bot @%s ready, cloud %s, shared key: %s", me.get("username"), a.cloud_url, "yes" if key else "no")
    stop = threading.Event()
    try:
        bot.run(stop)
    except KeyboardInterrupt:
        stop.set()


if __name__ == "__main__":
    main()
