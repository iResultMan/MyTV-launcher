import io
import logging
import os
import sqlite3
import tempfile
import threading
import time
import unittest
from unittest import mock

import bot as botmod
from tests.fake_telegram import FakeTelegram
from tests.fake_tv import CloudHarness, FakeTV

KEY = "shared-test-key"


class BotTest(unittest.TestCase):
    """The bot against a real cloud server, a fake TV and a fake Telegram, all on localhost."""

    bot_key = KEY

    def setUp(self):
        self.h = CloudHarness()
        self.h.start(bot_key=self.bot_key)
        self.tg = FakeTelegram()
        self.dir = tempfile.TemporaryDirectory()
        self.db_path = os.path.join(self.dir.name, "bot.sqlite")
        self.now = 1000.0
        self.make_bot()

    def make_bot(self, cloud_url=None):
        if getattr(self, "bot", None):
            self.bot.db.close()
        self.bot = botmod.Bot(botmod.Telegram(self.tg.token, self.tg.url),
                              botmod.Cloud(cloud_url or "http://127.0.0.1:%d" % self.h.port, self.bot_key),
                              botmod.Db(self.db_path), clock=lambda: self.now, wall=self.h.clock)

    def tearDown(self):
        self.bot.db.close()
        self.tg.stop()
        self.h.stop()
        self.dir.cleanup()

    def say(self, text, chat=1, **kw):
        self.tg.say(chat, text, **kw)
        self.bot.poll_once(timeout=0)
        return self.tg.last(chat)

    def pair(self, chat=1, name="Living room TV", **kw):
        tv = FakeTV(self.h, name)
        self.assertIn("✅", self.say(tv.code(), chat, **kw))
        return tv

    def settle(self, tv=None, result="ok", detail=""):
        """The TV takes whatever is waiting and answers; then the bot looks at its pending commands."""
        if tv:
            for cmd in tv.poll():
                tv.ack(cmd, result, detail)
        self.now += 5
        self.bot.check_pending()


class StartAndPairingTest(BotTest):
    def test_start_explains_in_two_lines_in_the_users_language(self):
        en = self.say("/start")
        ru = self.say("/start", chat=2, lang="ru")
        self.assertEqual(2, len(en.splitlines()))
        self.assertIn("6-digit", en)
        self.assertEqual(2, len(ru.splitlines()))
        self.assertIn("6-значный", ru)
        self.assertIn("6-digit", self.say("/start", chat=3, lang="de"))      # anything but Russian gets English

    def test_help_lists_the_commands(self):
        text = self.say("/help")
        for c in ("/lock", "/unlock", "/message", "/off", "/status", "/tvs", "/rename", "/forget"):
            self.assertIn(c, text)

    def test_pairing_with_the_start_payload_a_bare_code_and_a_spaced_code(self):
        for i, form in enumerate(("/start %s", "%s", "{a} {b}")):
            tv = FakeTV(self.h, "TV%d" % i)
            code = tv.code()
            text = form.replace("{a} {b}", code[:3] + " " + code[3:]) if "{a}" in form else form % code
            self.assertIn("TV%d" % i, self.say(text, chat=10 + i))
        rows = sqlite3.connect(self.db_path).execute("select chat_id, name from tvs order by chat_id").fetchall()
        self.assertEqual([(10, "TV0"), (11, "TV1"), (12, "TV2")], rows)

    def test_the_parent_gets_the_telegram_first_name(self):
        tv = FakeTV(self.h)
        self.say(tv.code(), first_name="Anna")
        parent = self.h.store.db.execute("select name from parents").fetchone()[0]
        self.assertEqual("Anna", parent)

    def test_a_wrong_code_is_explained_and_the_limit_is_per_chat(self):
        FakeTV(self.h).code()
        for _ in range(5):
            self.assertIn("wrong or expired", self.say("000000", chat=1))
        self.assertIn("Too many", self.say("000000", chat=1))
        self.assertIn("wrong or expired", self.say("000000", chat=2))          # another chat is not blocked

    def test_without_the_shared_key_the_limit_is_per_address_for_the_whole_bot(self):
        self.h.stop()
        self.h.start()
        self.make_bot()
        self.bot.cloud.key = None
        for chat in range(1, 6):
            self.say("000000", chat=chat)
        self.assertIn("Too many", self.say("000000", chat=6))

    def test_pairing_again_while_the_token_still_works_changes_nothing(self):
        tv = self.pair(name="Hall")
        token = self.bot.db.tvs(1)[0]["token"]
        self.assertIn("already control Hall", self.say(tv.code()))
        self.assertEqual(token, self.bot.db.tvs(1)[0]["token"])
        self.assertEqual(1, self.h.store.db.execute("select count(*) from parents").fetchone()[0])      # the spare token was cancelled
        self.assertEqual(200, self.h.call("GET", "/v1/device/state", token=token)[0])

    def test_pairing_again_after_the_access_was_lost_keeps_the_name(self):
        tv = self.pair(name="Hall")
        self.say("/rename Kitchen")
        self.h.call("DELETE", "/v1/parents/" + self.bot.db.tvs(1)[0]["parent_id"], token=tv.secret)    # the TV removed the phone
        self.assertIn("Kitchen", self.say(tv.code()))
        self.assertEqual(["Kitchen"], [t["name"] for t in self.bot.db.tvs(1)])
        self.assertEqual(200, self.h.call("GET", "/v1/device/state", token=self.bot.db.tvs(1)[0]["token"])[0])

    def test_a_full_tv_and_a_full_chat_are_explained(self):
        tv = FakeTV(self.h)
        owner = self.h.call("POST", "/v1/pair", {"code": tv.code(), "name": "p0"})[1]["parent_token"]
        for i in range(1, 8):
            pid = self.h.call("POST", "/v1/pair", {"code": tv.code(), "name": "p%d" % i})[1]["parent_id"]
            self.h.call("POST", "/v1/parents/%s/approve" % pid, token=owner)
        self.assertIn("8 phones", self.say(tv.code()))
        for i in range(botmod.MAX_TVS):
            self.pair(name="T%d" % i)
        extra = FakeTV(self.h, "extra")
        self.assertIn("up to 5", self.say(extra.code()))
        self.assertEqual(5, len(self.bot.db.tvs(1)))
        self.assertEqual(0, self.h.store.db.execute("select count(*) from parents where device_id=?", (extra.device_id,)).fetchone()[0])

    def test_groups_are_refused(self):
        self.assertIn("private", self.say("/start", chat=-5, chat_type="group"))
        self.assertEqual([], self.bot.db.tvs(-5))

    def test_commands_before_pairing_ask_for_the_code(self):
        for cmd in ("/lock", "/unlock", "/message hi", "/off", "/status", "/tvs", "/rename X", "/forget"):
            self.assertIn("6-digit code", self.say(cmd), cmd)

    def test_unknown_input(self):
        self.assertIn("/help", self.say("/dance"))
        self.assertIn("6-digit", self.say("hello"))            # nothing paired yet: the explanation
        self.pair()
        self.assertIn("/lock", self.say("hello"))              # paired: the command list
        self.assertIn("/help", self.say("/dance@mytv_test_bot"))

    def test_the_bot_name_suffix_is_accepted(self):
        tv = self.pair()
        self.say("/lock@mytv_test_bot Dinner")
        self.assertEqual("Dinner", tv.poll()[0]["args"]["note"])


class CommandsTest(BotTest):
    def setUp(self):
        super().setUp()
        self.tv = self.pair()

    def cmds(self):
        return self.tv.poll()

    def test_lock_with_a_note_reaches_the_tv_and_the_result_comes_back(self):
        self.say("/lock Dinner time")
        self.assertEqual(1, len(self.tg.texts(1)))             # only the pairing answer so far
        cmd, = self.cmds()
        self.assertEqual(("lock", "Dinner time", "Anna"), (cmd["type"], cmd["args"]["note"], cmd["args"]["by"]))
        self.tv.ack(cmd, "ok")
        self.now += 5
        self.bot.check_pending()
        self.assertEqual("🔒 locked.", self.tg.last(1))
        self.bot.check_pending()
        self.assertEqual(2, len(self.tg.texts(1)))             # said once

    def test_lock_without_a_note_unlock_and_message(self):
        self.say("/lock")
        self.say("/unlock")
        self.say("/message Dinner is ready")
        lock, unlock, message = self.cmds()
        self.assertEqual({"by": "Anna"}, lock["args"])
        self.assertEqual("unlock", unlock["type"])
        self.assertEqual("Dinner is ready", message["args"]["text"])
        for c in (lock, unlock, message):
            self.tv.ack(c)
        self.settle()
        self.assertEqual(["🔒 locked.", "🔓 unlocked.", "💬 message shown."], self.tg.texts(1)[-3:])

    def test_the_unlock_is_journaled_with_the_parents_name(self):
        self.say("/unlock")
        events = self.h.call("GET", "/v1/device/journal", token=self.bot.db.tvs(1)[0]["token"])[1]["events"]
        self.assertEqual(("cloud_unlock", True), (events[0]["kind"], "Anna" in events[0]["text"]))

    def test_message_and_note_length_and_usage(self):
        self.assertIn("Usage", self.say("/message"))
        self.assertIn("Too long", self.say("/message " + "x" * 141))
        self.assertIn("Too long", self.say("/lock " + "x" * 141))
        self.assertEqual([], self.cmds())

    def test_off_in_minutes_now_and_cancel(self):
        self.say("/off 30")
        self.say("/off")
        self.say("/off 0")
        a, b, c = self.cmds()
        self.assertEqual((1800, 0, True), (a["args"]["delay_s"], b["args"]["delay_s"], c["args"]["cancel"]))
        for cmd in (a, b, c):
            self.tv.ack(cmd)
        self.settle()
        self.assertEqual(["⏻ switching off in 30 min.", "⏻ switching off now.", "⏻ switch-off cancelled."], self.tg.texts(1)[-3:])

    def test_off_arguments_are_checked(self):
        for bad in ("abc", "-5", "1441", "5 6", "1.5"):
            self.assertIn("Usage", self.say("/off " + bad), bad)
        self.assertEqual([], self.cmds())
        self.say("/off 1440")
        self.assertEqual(86400, self.cmds()[0]["args"]["delay_s"])

    def test_a_failed_command_says_why(self):
        self.say("/message hi")
        self.settle(self.tv, "failed", "no overlay permission")
        self.assertEqual("❌ the TV could not do it: no overlay permission.", self.tg.last(1))

    def test_an_offline_tv_gets_a_warning_then_the_result(self):
        self.say("/lock")
        self.now += 5
        self.bot.check_pending()
        self.assertEqual(1, len(self.tg.texts(1)))             # not yet worth a message
        self.now += 20
        self.bot.check_pending()
        self.assertIn("offline", self.tg.last(1))
        self.assertIn("10 min", self.tg.last(1))
        n = len(self.tg.texts(1))
        self.now += 20
        self.bot.check_pending()
        self.assertEqual(n, len(self.tg.texts(1)))             # the warning is not repeated
        self.settle(self.tv)                                    # the TV comes back and obeys
        self.assertEqual("🔒 locked.", self.tg.last(1))

    def test_a_command_the_tv_never_picks_up_expires(self):
        self.say("/lock")
        self.h.clock.t += 601
        self.now += 30
        self.bot.check_pending()
        self.assertIn("did not pick it up", self.tg.last(1))
        self.assertEqual([], self.bot.pending)

    def test_pending_commands_are_dropped_after_their_time(self):
        self.say("/lock")
        self.now += 700
        self.bot.check_pending()
        self.assertEqual([], self.bot.pending)

    def test_checks_back_off_so_the_token_budget_is_not_eaten(self):
        self.say("/lock")
        calls = []
        real = self.bot.cloud.request
        self.bot.cloud.request = lambda *a, **kw: (calls.append(a[1]), real(*a, **kw))[1]
        for _ in range(60):                                     # a minute of one-second ticks
            self.now += 1
            self.bot.check_pending()
        self.assertLess(len(calls), 15)

    def test_a_revoked_token_is_noticed_and_the_tv_removed(self):
        self.assertEqual(200, self.h.call("DELETE", "/v1/parents/" + self.bot.db.tvs(1)[0]["parent_id"], token=self.tv.secret)[0])
        self.assertIn("no longer accepts me", self.say("/lock"))
        self.assertEqual([], self.bot.db.tvs(1))
        self.assertIn("6-digit code", self.say("/lock"))

    def test_the_rate_limit_is_explained(self):
        token = self.bot.db.tvs(1)[0]["token"]
        for _ in range(60):
            self.h.call("GET", "/v1/device/state", token=token)
        self.assertIn("Too many requests", self.say("/lock"))
        self.assertIn("Too many requests", self.say("/status"))

    def test_russian_answers(self):
        self.say("/lock", lang="ru")
        self.settle(self.tv)
        self.assertEqual("🔒 заблокирован.", self.tg.last(1))
        self.assertIn("Использование", self.say("/message", lang="ru"))


class StatusTest(BotTest):
    def setUp(self):
        super().setUp()
        self.tv = self.pair(name="Hall")

    def test_no_data_yet(self):
        self.assertEqual("📺 Hall\nOffline\nNo data from the TV yet.", self.say("/status"))

    def test_online_locked_with_the_current_app(self):
        self.tv.state(locked=True, scheduled=False, service=True, screen={"on": True, "pkg": "ru.ivi.client", "label": "Ivi"})
        self.h.clock.t += 12
        self.assertEqual("📺 Hall\nOnline · locked\nApp: Ivi\nLast seen: 12 s ago", self.say("/status"))

    def test_unlocked_screen_off_helper_off_and_schedule(self):
        self.tv.state(locked=False, scheduled=False, service=False, screen={"on": False})
        self.assertEqual("📺 Hall\nOnline · unlocked\nScreen off\nHelper off\nLast seen: just now", self.say("/status"))
        self.tv.state(locked=True, scheduled=True, service=True, screen={"on": True, "pkg": "com.x"})
        self.assertIn("locked by schedule\nApp: com.x", self.say("/status"))

    def test_offline_shows_how_long_ago(self):
        self.tv.state(locked=True, service=True)
        self.h.clock.t += 3 * 3600
        self.assertEqual("📺 Hall\nOffline · locked\nLast seen: 3 h ago", self.say("/status"))
        self.h.clock.t += 2 * 86400
        self.assertIn("2 d ago", self.say("/status"))

    def test_russian(self):
        self.tv.state(locked=True, service=True, screen={"on": True, "label": "Ivi"})
        self.h.clock.t += 65
        self.assertEqual("📺 Hall\nВ сети · заблокирован\nПриложение: Ivi\nБыл в сети: 1 мин назад", self.say("/status", lang="ru"))


class SeveralTvsTest(BotTest):
    def setUp(self):
        super().setUp()
        self.a = self.pair(name="Living room")
        self.b = self.pair(name="Bedroom")

    def test_the_list_marks_the_current_tv_and_the_newest_is_current(self):
        self.assertEqual("Your TVs:\n1. Living room\n2. Bedroom (current)\n/tvs <number> switches.", self.say("/tvs"))

    def test_switching_decides_where_commands_go_and_replies_name_the_tv(self):
        self.say("/lock")                                       # current is the bedroom
        self.assertEqual([], self.a.poll())
        self.assertEqual("lock", self.b.poll()[0]["type"])
        self.assertIn("Now using Living room", self.say("/tvs 1"))
        self.say("/message hi")
        cmd, = self.a.poll()
        self.assertEqual("hi", cmd["args"]["text"])
        self.a.ack(cmd)
        self.settle()
        self.assertEqual("💬 Living room: message shown.", self.tg.last(1))

    def test_bad_numbers(self):
        for bad in ("0", "3", "x", "-1"):
            self.assertIn("no TV with that number", self.say("/tvs " + bad), bad)

    def test_rename_and_forget_act_on_the_current_tv_only(self):
        self.say("/tvs 1")
        self.assertIn("Renamed to Den", self.say("/rename   Den  "))
        self.assertIn("Usage", self.say("/rename"))
        self.assertEqual(["Den", "Bedroom"], [t["name"] for t in self.bot.db.tvs(1)])
        token = self.bot.db.tvs(1)[0]["token"]
        self.assertIn("Forgot Den", self.say("/forget"))
        self.assertEqual(["Bedroom"], [t["name"] for t in self.bot.db.tvs(1)])
        self.assertEqual(401, self.h.call("GET", "/v1/device/state", token=token)[0])      # revoked on the server too
        self.say("/lock")                                       # the only TV left is picked automatically, no prefix needed
        self.assertEqual("lock", self.b.poll()[0]["type"])

    def test_a_long_name_is_clipped(self):
        self.say("/rename " + "n" * 100)
        self.assertEqual(30, len(self.bot.db.tvs(1)[1]["name"]))

    def test_chats_are_independent(self):
        self.assertIn("6-digit code", self.say("/lock", chat=2))
        self.pair(chat=2, name="Other")
        self.assertEqual(1, len(self.bot.db.tvs(2)))
        self.assertEqual(2, len(self.bot.db.tvs(1)))


class OwnershipTest(BotTest):
    """The first phone to pair owns the TV; anyone else asks the owner, in Telegram, and waits for the answer."""

    def setUp(self):
        super().setUp()
        self.tv = self.pair(chat=1, name="Living room")          # chat 1 owns it

    def ask(self, chat, name="Boris", **kw):
        self.assertIn("asked", self.say(self.tv.code(), chat=chat, first_name=name, **kw).replace("I asked", "asked"))

    def test_the_owner_is_told_so_and_a_second_user_asks_the_owner(self):
        self.assertIn("You are its owner", self.tg.texts(1)[0])
        self.say(self.tv.code(), chat=2, first_name="Boris")
        self.assertIn("I asked them to allow you", self.tg.last(2))
        self.assertEqual("🔑 Boris asks to control Living room. Allow?", self.tg.last(1))
        self.assertEqual(["✅ Allow", "❌ Deny"], self.tg.buttons(1))

    def test_a_waiting_user_can_do_nothing_and_sees_it_in_the_list(self):
        self.ask(2)
        for cmd in ("/lock", "/unlock", "/message hi", "/off 5", "/status", "/people"):
            self.assertEqual("⏳ Living room is waiting for the owner's OK.", self.say(cmd, chat=2), cmd)
        self.assertEqual([], self.tv.poll())
        self.assertEqual("Your TVs:\n1. Living room (waiting for approval)", self.say("/tvs", chat=2))
        self.assertEqual(1, len(self.tg.texts(1)) - 1)                         # the owner heard only the one request

    def test_allow_lets_the_user_in_tells_both_and_edits_the_request(self):
        self.ask(2)
        self.tg.press(1, "Allow")
        self.bot.poll_once(timeout=0)
        self.assertEqual("✅ The owner allowed it: you can control Living room.", self.tg.last(2))
        self.assertEqual([(1, self.tg.edits[0][1], "✅ Boris may now control Living room.")], self.tg.edits)
        self.assertEqual([], self.tg.buttons(1))
        self.say("/lock", chat=2)
        self.assertEqual(("lock", "Boris"), (lambda c: (c["type"], c["args"]["by"]))(self.tv.poll()[0]))
        self.assertEqual("Your TVs:\n1. Living room", self.say("/tvs", chat=2))

    def test_deny_removes_the_request_and_tells_the_user(self):
        self.ask(2)
        token = self.bot.db.tvs(2)[0]["token"]
        self.tg.press(1, "Deny")
        self.bot.poll_once(timeout=0)
        self.assertEqual("❌ The owner declined your request for Living room.", self.tg.last(2))
        self.assertEqual("❌ Declined: Boris.", self.tg.edits[0][2])
        self.assertEqual([], self.bot.db.tvs(2))
        self.assertEqual(401, self.h.call("GET", "/v1/device/state", token=token)[0])
        self.assertIn("6-digit code", self.say("/lock", chat=2))

    def test_only_the_owner_decides(self):
        self.ask(2)
        self.tg.press(1, "Allow")
        self.bot.poll_once(timeout=0)                                           # Boris is a member now
        self.ask(3, "Clara")
        data = [b["callback_data"] for c, m, t, rows in self.tg.sent_full if c == 1 and rows for row in rows for b in row][-1]
        for chat in (2, 99):                                                    # a member, and someone with no TV at all
            self.tg.next_id += 1
            self.tg.updates.append({"update_id": self.tg.next_id, "callback_query": {"id": "x%d" % chat, "from": {"id": chat}, "data": data,
                                    "message": {"message_id": 1, "chat": {"id": chat}}}})
        self.bot.poll_once(timeout=0)
        self.assertEqual([("Only the owner can do that.", True), ("Only the owner can do that.", False)], self.tg.answers[-2:])
        self.assertEqual("⏳ Living room is waiting for the owner's OK.", self.say("/lock", chat=3))

    def test_a_request_that_is_gone_is_said_so(self):
        self.ask(2)
        pid = self.bot.db.tvs(2)[0]["parent_id"]
        self.h.call("DELETE", "/v1/parents/" + pid, token=self.tv.secret)         # the TV withdrew it
        self.tg.press(1, "Allow")
        self.bot.poll_once(timeout=0)
        self.assertEqual("This request is gone: it was answered or expired.", self.tg.edits[0][2])

    def test_an_owner_outside_telegram_is_unreachable_so_the_user_is_told_to_ask(self):
        other = FakeTV(self.h, "Den")
        self.h.call("POST", "/v1/pair", {"code": other.code(), "name": "Web owner"})      # paired through the API, not the bot
        self.assertIn("cannot reach them here", self.say(other.code(), chat=2))
        self.assertEqual(1, len(self.tg.texts(1)))                                    # nothing reached the first TV's owner

    def test_the_request_comes_in_the_owners_language(self):
        self.say("/status", chat=1, lang="ru")
        self.say(self.tv.code(), chat=2, first_name="Борис", lang="ru")
        self.assertEqual("🔑 Борис просит управлять: Living room. Разрешить?", self.tg.last(1))
        self.assertEqual(["✅ Разрешить", "❌ Отказать"], self.tg.buttons(1))
        self.assertIn("Я спросил его", self.tg.last(2))

    def test_at_most_three_requests_wait(self):
        for chat in (2, 3, 4):
            self.ask(chat)
        self.assertIn("Too many requests", self.say(self.tv.code(), chat=5))
        self.assertEqual([], self.bot.db.tvs(5))

    def test_asking_twice_does_not_stack_requests(self):
        self.ask(2)
        self.assertEqual("⏳ Living room is waiting for the owner's OK.", self.say(self.tv.code(), chat=2))
        self.assertEqual(2, self.h.store.db.execute("select count(*) from parents").fetchone()[0])

    def test_people_shows_everyone_to_the_owner_and_only_members_to_a_member(self):
        self.ask(2)
        self.tg.press(1, "Allow")
        self.bot.poll_once(timeout=0)
        self.ask(3, "Clara")
        owner = self.say("/people", chat=1)
        self.assertEqual("People with access to Living room:\n1. Anna (owner, you)\n2. Boris\n3. Clara (waiting)\n/remove <number> takes someone's access away.",
                         self.tg.texts(1)[-2])
        self.assertEqual("🔑 Clara asks to control Living room. Allow?", self.tg.last(1))      # the waiting one gets its buttons again
        self.assertEqual("People with access to Living room:\n1. Anna (owner)\n2. Boris (you)", self.say("/people", chat=2))

    def test_remove_takes_access_away_and_tells_the_person(self):
        self.ask(2)
        self.tg.press(1, "Allow")
        self.bot.poll_once(timeout=0)
        token = self.bot.db.tvs(2)[0]["token"]
        self.assertEqual("Only the owner can do that.", self.say("/remove 1", chat=2))
        for bad in ("", "x", "0", "9"):
            self.assertIn("Usage", self.say("/remove " + bad, chat=1), bad)
        self.assertIn("/forget", self.say("/remove 1", chat=1))
        self.assertEqual("Removed Boris.", self.say("/remove 2", chat=1))
        self.assertEqual("You no longer control Living room.", self.tg.last(2))
        self.assertEqual([], self.bot.db.tvs(2))
        self.assertEqual(401, self.h.call("GET", "/v1/device/state", token=token)[0])

    def test_when_the_owner_forgets_the_tv_the_oldest_member_owns_it_and_gets_the_requests(self):
        self.ask(2)
        self.tg.press(1, "Allow")
        self.bot.poll_once(timeout=0)
        self.assertEqual("Forgot Living room. Boris is its owner now.", self.say("/forget", chat=1))
        self.assertIn("(owner, you)", self.say("/people", chat=2))
        self.ask(3, "Clara")                                                      # the request goes to the new owner, found through the server
        self.assertEqual("🔑 Clara asks to control Living room. Allow?", self.tg.last(2))
        self.tg.press(2, "Allow")
        self.bot.poll_once(timeout=0)
        self.assertIn("allowed it", self.tg.last(3))

    def test_the_only_owner_leaving_makes_the_next_user_the_owner(self):
        self.assertIn("Forgot Living room.", self.say("/forget", chat=1))
        self.assertIn("You are its owner", self.say(self.tv.code(), chat=2))

    def test_a_member_forgetting_keeps_the_owner(self):
        self.ask(2)
        self.tg.press(1, "Allow")
        self.bot.poll_once(timeout=0)
        self.assertEqual("Forgot Living room.", self.say("/forget", chat=2))
        self.assertIn("(owner, you)", self.say("/people", chat=1))


class RobustnessTest(BotTest):
    def test_the_cloud_being_down_is_explained(self):
        tv = self.pair()
        self.make_bot("http://127.0.0.1:1")                      # nothing listens there
        for cmd in ("/lock", "/status", "/tvs 1"):
            self.say(cmd)
        self.assertIn("cannot reach the cloud", self.tg.texts(1)[-2])
        self.assertIn("cannot reach the cloud", self.say("123456"))
        self.assertEqual(1, len(self.bot.db.tvs(1)))             # nothing was forgotten

    def test_updates_are_confirmed_so_a_restart_does_not_replay_them(self):
        self.pair()
        self.say("/lock")
        self.assertGreater(self.bot.db.offset(), 0)
        self.make_bot()                                          # a new process with the same database
        self.assertEqual(0, self.bot.poll_once(timeout=0))
        self.assertEqual(1, len(self.h.store.db.execute("select 1 from commands").fetchall()))

    def test_a_failing_handler_does_not_stop_the_updates(self):
        self.tg.say(1, "/start")
        self.tg.say(1, "/help")
        with mock.patch.object(self.bot, "pair", side_effect=RuntimeError("boom")), self.assertLogs("mytv-bot", "ERROR"):
            self.tg.say(1, "/start 123456")
            self.assertEqual(3, self.bot.poll_once(timeout=0))
        self.assertEqual(2, len(self.tg.texts(1)))
        self.assertEqual(0, self.bot.poll_once(timeout=0))

    def test_non_text_and_unknown_updates_are_ignored(self):
        self.tg.updates.append({"update_id": 500, "message": {"chat": {"id": 1, "type": "private"}, "photo": []}})
        self.tg.updates.append({"update_id": 501, "edited_message": {}})
        self.assertEqual(2, self.bot.poll_once(timeout=0))
        self.assertEqual([], self.tg.sent)

    def test_telegram_asking_to_slow_down_is_obeyed_once(self):
        self.tg.send_errors = [(429, {"retry_after": 2})]
        with mock.patch.object(botmod.time, "sleep") as sleep:
            self.say("/start")
        sleep.assert_called_once_with(2)
        self.assertEqual(1, len(self.tg.sent))

    def test_a_blocked_bot_is_not_an_error(self):
        self.tg.send_errors = [(403, {})]
        with self.assertLogs("mytv-bot", "WARNING"):
            self.say("/start")
        self.assertEqual([], self.tg.sent)

    def test_setup_registers_the_commands_in_both_languages(self):
        me = self.bot.setup()
        self.assertEqual("mytv_test_bot", me["username"])
        self.assertIn("lock", self.tg.commands[""])
        self.assertIn("forget", self.tg.commands["ru"])

    def test_the_run_loop_answers_and_stops(self):
        stop = threading.Event()
        t = threading.Thread(target=self.bot.run, args=(stop,), daemon=True)
        t.start()
        self.tg.say(1, "/start")
        deadline = time.time() + 10
        while not self.tg.sent and time.time() < deadline:
            time.sleep(0.05)
        stop.set()
        t.join(10)
        self.assertFalse(t.is_alive())
        self.assertIn("6-digit", self.tg.last(1))

    def test_a_rejected_bot_token_stops_the_run_loop(self):
        self.bot.tg = botmod.Telegram("999:wrong-token-wrong-token-wrong", self.tg.url)
        with self.assertRaises(SystemExit), self.assertLogs("mytv-bot", "WARNING"):
            self.bot.run(threading.Event())

    def test_the_database_is_private(self):
        self.assertEqual(0o600, os.stat(self.db_path).st_mode & 0o777)


class SecretsTest(BotTest):
    def test_no_token_reaches_the_log(self):
        tv = self.pair()
        parent = self.bot.db.tvs(1)[0]["token"]
        stream = io.StringIO()
        handler = logging.StreamHandler(stream)
        handler.setFormatter(botmod.RedactingFormatter([self.tg.token], "%(levelname)s %(message)s"))
        logger = logging.getLogger("mytv-bot")
        logger.addHandler(handler)
        logger.setLevel(logging.INFO)
        try:
            self.say("/lock")
            self.tg.send_errors = [(500, {})]
            self.say("/status")                                  # a failed send is logged
            with mock.patch.object(self.bot, "status", side_effect=RuntimeError("url was /bot%s/getMe" % self.tg.token)):
                self.say("/status")                              # a crash with the token inside its message is logged with a traceback
        finally:
            logger.removeHandler(handler)
        out = stream.getvalue()
        self.assertIn("update", out)
        self.assertIn("***", out)
        for secret in (self.tg.token, parent, tv.secret, KEY):
            self.assertNotIn(secret, out)

    def test_the_telegram_error_text_has_no_url(self):
        tg = botmod.Telegram(self.tg.token, "http://127.0.0.1:1")
        with self.assertRaises(botmod.TelegramError) as cm:
            tg.call("getMe")
        self.assertNotIn(self.tg.token, str(cm.exception))

    def test_token_loading(self):
        good = "123456:" + "A" * 30
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "token")
            self.assertRaises(SystemExit, botmod.load_token, {"MYTV_BOT_TOKEN_FILE": path})         # missing
            with open(path, "w") as f:
                f.write(good + "\n")
            os.chmod(path, 0o600)
            self.assertEqual(good, botmod.load_token({"MYTV_BOT_TOKEN_FILE": path}))
            self.assertEqual("1:" + "b" * 25, botmod.load_token({"MYTV_BOT_TOKEN": "1:" + "b" * 25, "MYTV_BOT_TOKEN_FILE": path}))
            with open(path, "w") as f:
                f.write("not a token")
            self.assertRaises(SystemExit, botmod.load_token, {"MYTV_BOT_TOKEN_FILE": path})
            with open(path, "w") as f:
                f.write(good)
            os.chmod(path, 0o644)
            with self.assertLogs("mytv-bot", "WARNING") as logs:
                botmod.load_token({"MYTV_BOT_TOKEN_FILE": path})
            self.assertNotIn(good, "".join(logs.output))


if __name__ == "__main__":
    unittest.main()
