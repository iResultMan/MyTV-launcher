# MyTV cloud link: server and Telegram bot

This directory holds a server for the [cloud link protocol](../docs/cloud-protocol.md) (version 1) and a Telegram bot
that is one client of it. One hosted bot controls many families' TVs: a parent starts the bot, pairs a TV with the
6-digit code shown on the TV screen, and then sends commands.

```
 TV launcher ──HTTPS, outgoing only──▶ nginx ──▶ cloud/server (127.0.0.1:8791, SQLite)
                                                      ▲
 Telegram ◀──long polling──▶ cloud/bot ──HTTPS────────┘
```

The TV never listens and needs no open port. The bot never talks to the TV; it only talks to the server, like any other
parent client. Both programs use the Python standard library only (tested with Python 3.14; SQLite 3.24 or newer).

| Path | What |
|---|---|
| `server/server.py` | the protocol server: pairing, device state, poll/ack long-poll, commands, journal, revoke, limits |
| `bot/bot.py` | the Telegram bot |
| `tests/` | unit and integration tests, offline |
| `systemd/` | example systemd user units |

## Tests

```bash
cd cloud && python3 -m unittest
```

Everything runs on localhost: the server tests use a fake TV built from the protocol, the bot tests run the real server
with a fake TV and a fake Telegram Bot API, and two tests start both programs as processes the way systemd does.

## Server

```bash
python3 cloud/server/server.py            # 127.0.0.1:8791, database ~/.local/share/mytv-cloud/cloud.sqlite
```

| Option / environment | Default | Meaning |
|---|---|---|
| `--host` / `MYTV_CLOUD_HOST` | `127.0.0.1` | bind address; keep it on loopback and put a TLS proxy in front |
| `--port` / `MYTV_CLOUD_PORT` | `8791` | port |
| `--db` / `MYTV_CLOUD_DB` | `~/.local/share/mytv-cloud/cloud.sqlite` | SQLite file (created with mode 0600) |
| `--trust-proxy` / `MYTV_CLOUD_TRUST_PROXY=1` | off | take the client address from `X-Real-IP`; set it only behind your own proxy |
| `--bot-key-file` / `MYTV_CLOUD_BOT_KEY_FILE` | `~/.config/mytv-bot/cloud-key` | optional shared key with the bot, see below |

What it does, beyond the wording of the protocol:

- Tokens are 32 random bytes in hex; only their SHA-256 is stored. 60 calls a minute per token on every authenticated
  endpoint (HTTP 429 with `Retry-After`). Pairing codes are unique among live codes, work once, and expire after 5 minutes.
  Five wrong codes in five minutes from one client block that client for five minutes. Creating TVs is limited to
  10 an hour per address.
- Commands are checked and rebuilt from a whitelist per type; the sender's name is always the paired parent's name and
  cannot be forged. The TV decides: an unknown or expired command is simply not applied.
- The state is reduced to the fields listed in the protocol; anything else the client sends is dropped.
- The journal is merged from what the TV sends (up to 200 events, duplicates ignored). Every `unlock` is also journaled
  by the server as `cloud_unlock` with the parent's name. There is no PIN in the cloud.
- Retention: commands, journal events and day-table entries older than 30 days are deleted (at start and every hour).
  A TV silent for 30 days keeps its pairing but loses its reported state. A registration nobody paired and that never
  reported is forgotten after 30 days.
- `GET /v1/health` answers `{"ok": true}` without the `X-Protocol` header, for monitoring.
- Additions to the protocol, all backward compatible: `device_name` in the pairing answer, `name` in the state read,
  HTTP 403 for a wrong or expired code, and the optional bot key below.
- Logs have one line per request (method, path without query, status) and never a header or a body.

### Behind nginx

The server speaks plain HTTP. Terminate TLS in a reverse proxy and forward `/v1/`:

```nginx
location /v1/ {
    proxy_pass http://127.0.0.1:8791;
    proxy_set_header X-Real-IP $remote_addr;    # required with --trust-proxy; it replaces anything the client sent
    proxy_read_timeout 60s;                      # long-poll holds a request for up to 30 s
    client_max_body_size 16k;
}
```

If nginx runs on another machine than the server, reach the server through a tunnel or a private network and bind the
server to that interface with `--host`; never expose the plain HTTP port to the internet. Without `X-Real-IP` every
client looks like the proxy and the per-address limits (pairing, creating TVs) are shared by all of them.

### The shared key (optional, recommended)

The bot pairs on behalf of every family from one address, so a per-address pairing limit would let one person lock all
families out of pairing. If the server and the bot share a key, the server counts wrong codes per Telegram chat
instead (`X-Bot-Key` and `X-Subject: tg-<chat id>`):

```bash
mkdir -p ~/.config/mytv-bot && chmod 700 ~/.config/mytv-bot
(umask 077; openssl rand -hex 24 > ~/.config/mytv-bot/cloud-key)
```

On one machine both programs read this same file by default. If they run on different machines, copy the file to both.
Restart both after creating it. Without the file everything still works, with the per-address limit only.

### Run as a service

```bash
mkdir -p ~/.config/systemd/user
sed "s#%h/mytv-public#$(git rev-parse --show-toplevel)#" cloud/systemd/mytv-cloud.service > ~/.config/systemd/user/mytv-cloud.service
systemctl --user daemon-reload && systemctl --user enable --now mytv-cloud
curl -s http://127.0.0.1:8791/v1/health
journalctl --user -u mytv-cloud -f
```

Both commands are run inside the clone: the `sed` puts the clone's real path into the unit. Run `loginctl enable-linger $USER` once so the user services keep running after you log out.

Backup: `sqlite3 ~/.local/share/mytv-cloud/cloud.sqlite ".backup cloud-backup.sqlite"`.

## Bot

### Create the bot (once, about two minutes)

1. In Telegram open [@BotFather](https://t.me/BotFather) and send `/newbot`.
2. Give it a name (shown to users) and a username that ends in `bot`. BotFather answers with a token like
   `123456789:AA...`. Treat it like a password.
3. Save the token to a file only you can read (the command asks for it without echoing it):

   ```bash
   mkdir -p ~/.config/mytv-bot && chmod 700 ~/.config/mytv-bot
   read -rsp 'Paste the bot token: ' T && (umask 077; printf '%s\n' "$T" > ~/.config/mytv-bot/token); unset T
   ```

   The token is read from `MYTV_BOT_TOKEN`, or from `MYTV_BOT_TOKEN_FILE`, or from `~/.config/mytv-bot/token`. It is never
   read from the repository and never logged.
4. Optional in BotFather: `/setdescription`, `/setabouttext` and `/setuserpic` for what users see before pressing Start. The
   command menu is registered by the bot itself, in English and Russian.
5. Start it:

   ```bash
   sed "s#%h/mytv-public#$(git rev-parse --show-toplevel)#" cloud/systemd/mytv-bot.service > ~/.config/systemd/user/mytv-bot.service
   systemctl --user daemon-reload && systemctl --user enable --now mytv-bot
   journalctl --user -u mytv-bot -f                              # expect: "bot @yourname ready"
   ```

The unit points the bot at `http://127.0.0.1:8791`, because the bot runs on the same machine as the server and the public proxy limits requests per address (all the bot's users share one). If the bot runs on another machine, set `MYTV_CLOUD_URL` to the public address, `https://tv.secondoffice.ru` for this project. The QR code on the TV can point to
`https://t.me/<bot username>?start=<code>`: opening it sends `/start <code>` and pairs in one tap.

### What users see

| Message | Does |
|---|---|
| `/start` | two lines of explanation |
| `/start 123456`, or just `123456` | pairs the TV that shows that code |
| `/lock [note]` | locks the TV, the note (up to 140 characters) is shown on it |
| `/unlock` | unlocks the manual lock |
| `/message <text>` | shows the text over the running app (up to 140 characters) |
| `/off [minutes]` | switches the TV off now, or after the minutes (up to 1440); `/off 0` cancels a pending one |
| `/status` | online or not, locked or not, the current app, when it was last seen |
| `/tvs`, `/tvs 2` | lists the chat's TVs and switches the current one (up to 5 per chat) |
| `/people` | who has access to the current TV; the owner also sees waiting requests, with buttons |
| `/remove <number>` | the owner takes someone's access away |
| `/rename <name>`, `/forget` | rename or unpair the current TV |

**Who may control a TV.** The first person to pair a TV is its owner. Anyone else who sends the code (the code can be
read off the screen by anyone standing at the TV) is told that the owner was asked, and the owner gets a message with
Allow and Deny buttons. Until the owner allows it, the newcomer can do nothing. The server enforces this, so
going around the bot with the API does not help. Once allowed, a person can use every command, `/unlock` included.
At most 3 requests wait per TV and an unanswered one is forgotten after a day. If the owner uses `/forget`, the oldest
remaining person becomes the owner.

**Lost the owner's Telegram account?** The TV can remove any phone, the owner included (the launcher lists them with
`GET /v1/parents`). After that the oldest remaining person owns the TV; if nobody is left, the next person who pairs
becomes the owner. The launcher should ask for the TV PIN before it offers this.

With one TV every command goes to it. With several, commands go to the current one and replies name the TV. The bot
answers in Russian when the user's Telegram language is Russian, otherwise in English. It reports the outcome of each
command (done, failed, or "the TV is offline, I will keep trying") a few seconds later. It works in private chats only,
because a code or a lock command in a group would be seen and used by everyone there.

### What the bot stores

`~/.local/share/mytv-bot/bot.sqlite` (mode 0600): per Telegram chat id, the paired TVs with the parent token the server
issued, the TV name and the position in the update stream. No message text is stored. Unpairing (`/forget`) revokes the
token on the server and deletes it here. Anyone who reads this file can control the paired TVs, so keep the machine and
backups private.

## Privacy

From the protocol: the server learns the TV's name, the apps' package names and labels and the times the screen was on
(state and optional day table), the journal events, and the messages sent. It does not learn the PIN, the Wi-Fi name or
any content of what is watched. The launcher shows exactly this list before the cloud mode can be turned on, and turning
it off deletes the TV's data on the server (`DELETE /v1/devices/{device_id}`).

The bot adds Telegram to the path: Telegram sees the messages users send to the bot and the bot's answers. The server
keeps the parent's name (the Telegram first name the user has) next to each paired parent, and the bot's host keeps the
chat id of each user.

## Limits and known gaps

- One process, one SQLite file, one lock: fine for hundreds of families, not for millions.
- The bot handles updates one by one and keeps its pending-command watchers in memory; a restart forgets which commands
  it was going to report on (the commands themselves are not lost).
- `pause` and `bonus` exist in the protocol and the server, but the bot has no command for them yet.
