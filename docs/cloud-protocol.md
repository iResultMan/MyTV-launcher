# Cloud link protocol (mode C), version 1

Status: implemented in `cloud/` (server and Telegram bot); the client is in the launcher (Launcher settings > Phone and cloud: `CloudClient`, `CloudSession`, `CloudCore`) and is off until a parent turns it on. `tools/cloud-stub/` stays as a small reference stand-in for client development. The cloud mode ships only after the demand gate in the
project's revenue notes. Modes A (schedule on the TV) and B (phone on the home Wi-Fi) do not depend on this document.

## Principles

- The TV only makes outgoing HTTPS requests. It never listens for the cloud and never needs a port opened at home.
- The launcher uses `HttpURLConnection` and nothing else: no Firebase, no push SDK. A parent's phone, a web page or a
  Telegram bot are all clients of the same API.
- The TV decides. A command is a request; the TV applies it, checks it against its own PIN and schedule rules, and
  answers with an acknowledgement. The server never holds the PIN.
- The server stores only what the parents need: the TV's latest state, a short journal and the day table.
  Everything else stays on the TV. Data older than 30 days is deleted.

## Tokens

Every token is 32 random bytes in hex (64 characters), sent as `Authorization: Bearer <token>`, never in a URL. The
server keeps only the SHA-256 of a token. Two kinds:

- `device_secret`: one per TV, created by `POST /v1/devices`, kept in the launcher's private storage.
- `parent_token`: one per paired phone, web browser or bot; at most 8 per TV (requests waiting for approval included); each can be revoked.

## Pairing

1. The TV registers once: `POST /v1/devices` (no auth) with `{"name":"Living room TV"}` returns
   `{"device_id":"…","device_secret":"…"}`.
2. The TV asks for a code: `POST /v1/pair/code` (device auth) returns `{"code":"123456","expires_in":300}`. The TV shows
   the code (and a QR with the page address and the code) on screen. A new request replaces the old code.
3. A parent client sends `POST /v1/pair` (no auth) with `{"code":"123456","name":"Mama"}` and gets
   `{"parent_token":"…","parent_id":"…","device_id":"…","device_name":"…"}`. A code works once; a wrong or expired code
   is HTTP 403. Five wrong codes from one address in five minutes block that address for five minutes (HTTP 429).
4. Who owns the TV. The first phone to pair is the owner and is `"status": "active"` at once. Every later phone also
   gets a token (the answer says `"status": "pending", "owner": false`), but until the owner approves it every call
   except its own revoke answers 403 `pending approval`. At most 3 requests wait per TV (409 `too many requests`); one
   nobody answers is forgotten after a day.
   - `GET /v1/parents` (parent auth, not pending) answers `{"you": id, "owner": bool, "parents": [{"id","name","owner","status"}]}`;
     only the owner sees the waiting ones. The TV may call it too (device auth) and sees everyone, without `you`.
   - `POST /v1/parents/{id}/approve` (the owner's token) lets a waiting phone in. Anyone else gets 403 `owner only`.
5. Revoke: `DELETE /v1/parents/{parent_id}`: the TV may remove anyone, a phone may remove itself (also a waiting
   request), and the owner may remove any phone of its TV (this is how a request is declined). The next call with that
   token gets 401. If the owner goes (it left, or the TV removed it because its account is lost), the oldest remaining
   active phone becomes the owner; with none left, waiting requests are dropped and the next phone to pair owns the TV.

## What the TV sends

`POST /v1/device/state` (device auth), at start, on every change and at least every 60 seconds:

```json
{"locked": false, "scheduled": false, "service": true,
 "screen": {"on": true, "since": 1793904538672, "pkg": "ru.ivi.client", "label": "Ivi"},
 "journal": [{"t": 1793904538672, "kind": "service_off", "text": "the helper is off"}],
 "days": [{"day": "2026-11-05", "s": 29163, "apps": [{"p": "ru.ivi.client", "l": "Ivi", "s": 13875}]}]}
```

`journal` is the last 10 events (see the launcher's KidsJournal); `days` is optional and sent at most once an hour, only
if the parents turned the cloud screen-time report on. The answer is `{"rev": N}`; the state counts as online for 90
seconds after the last call.

## Commands

A parent client sends `POST /v1/commands` (parent auth):

```json
{"type": "lock", "args": {"note": "Dinner time"}, "ttl_s": 600}
```

| type | args | what the TV does |
|---|---|---|
| `lock` | `note` up to 140 characters (optional) | locks the TV with the note |
| `unlock` | none | unlocks the manual lock (see open question 1) |
| `message` | `text` up to 140 characters | shows it over the running app, pauses the video |
| `pause` | none | pauses the media |
| `bonus` | `minutes` 5 to 120 | adds bonus time to today's schedule |
| `power_off` | `delay_s` 0 to 86400 (default 0), or `cancel`: true | puts the TV in standby now or after the delay (a warning shows a minute before, any remote key adds 15 minutes); `cancel` stops a pending one |

The answer is `{"id": "…", "expires_at": 1793905138672}`. `ttl_s` is 10 to 3600 (default 600). A command not applied
before it expires is dropped and acknowledged as `expired`; this keeps a "lock" sent at noon from firing when the TV
comes online in the evening. A TV queue holds 50 commands; the oldest is dropped first.

The parent reads the result with `GET /v1/commands/{id}` (parent auth): `{"status":"queued|delivered|ok|failed|expired","detail":""}`.

## What the TV polls

`GET /v1/device/poll?since=N&wait=25` (device auth). `since` is the highest command sequence the TV has seen (0 at
first). The server answers at once if there are commands after `since`, else it holds the request up to `wait` seconds
(maximum 30) and then answers `{"commands": [], "seq": N}`. Each command in the answer is
`{"seq": 7, "id": "…", "type": "lock", "args": {…}, "expires_at": …}`. The TV applies each one and acknowledges with
`POST /v1/device/ack` `{"id": "…", "result": "ok|failed|expired", "detail": "short text"}`. The TV polls again at once
after an answer, and after a network error waits 5 seconds, doubling up to 5 minutes.

## What a parent reads

- `GET /v1/device/state` (parent auth): the last state, plus `"name"` (the TV's name), `"rev"`, `"online": true|false`
  and `"last_seen"` (milliseconds, 0 before the first call). The server keeps only the fields listed above.
- `GET /v1/device/journal` (parent auth): the stored journal, newest first, at most 200 events. The server merges
  the events the TV sends into one journal (an event it already has is ignored) and writes a `cloud_unlock` event
  itself for every `unlock`, with the parent's name.
- `GET /v1/device/days` (parent auth): the stored day table, if the TV sends it.

## Errors and limits

JSON body `{"error": "short text"}` with 400 (malformed), 401 (missing or revoked token), 403 (wrong or expired pairing
code; also `pending approval` and `owner only`), 404, 409 (the TV already has 8 phones, or 3 requests wait), 413 (body over 16 KB), 429 (too many attempts), 503. Every request carries `X-Protocol: 1`; a server that
does not know the version answers 400. A token's calls are limited to 60 a minute (the TV's poll counts as one).

## Privacy

The server learns: the TV's name, the apps' package names and labels and the times the screen was on (state and
optional day table), the journal events, the messages sent. It does not learn the PIN, the Wi-Fi name or any
content of what is watched. The launcher shows exactly this list in a prominent disclosure before the cloud mode can be
turned on, and a switch off deletes the TV's data on the server (`DELETE /v1/devices/{device_id}`, device auth).

## Decisions taken for the first implementation

1. No PIN in the cloud. A cloud `unlock` is allowed for any paired parent token, and each unlock is written to the
   journal with the parent's name (the server writes it, so it does not depend on the client).
2. The server is self-hostable (`cloud/README.md`); where a given instance runs does not change the protocol.
3. Journal on, day table off by default (the TV sends `days` only if the parents turn it on).
4. The first phone to pair a TV owns it; later phones need the owner's approval, enforced by the server, so a code
   read off the screen is not enough to take control. An owner who lost the account is removed from the TV. The
   launcher should ask for the TV PIN before it offers that.
