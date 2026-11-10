# Privacy

*Russian version: [PRIVACY.ru.md](PRIVACY.ru.md).*

This launcher has no accounts, no analytics, no advertising identifiers and no crash reporting to anyone. What it does,
all of it on your TV unless stated:

| What | Where the data goes |
|---|---|
| The accessibility service ("Home button service") sees the name of the app that came to the front and, while the TV is locked, the remote's key presses. It reads no screen content, text or passwords. | Stays on the TV. |
| Children's mode: the schedule, the PIN (stored as a salted PBKDF2 hash), paired phones (stored as token hashes), the journal and the daily screen-time table (30 days). | Stays on the TV. Erased by "Erase the children's mode data" (Check and set up) or by uninstalling. |
| Phone control over Wi-Fi: the TV serves a page on port 8787 to phones on your home network that were paired with a code shown on the TV. Tokens travel in a header, never in an address; other networks are refused. | Between the TV and your phone on your Wi-Fi. |
| The family message service (optional `message_url` in a theme) and a weather relay (optional `weather_url`) | Plain http only to private addresses on your home network; anything else must be https. |
| Weather (only if a theme sets coordinates) | The coordinates and an IP address go to api.open-meteo.com. Leave the coordinates out of the theme to turn it off. |
| Watch next (off by default): reads the cards other apps publish to the TV, after you allow it. Poster pictures are downloaded from the addresses those apps publish. | The poster hosts see the TV's IP address. Pictures are cached on the TV (at most 20 MB). |
| The language list (Launcher settings > Language) reads one public file, `languages.json`, from raw.githubusercontent.com when you open the dialog; set `languages_url` to empty in a theme to never do this. | A plain HTTPS request to GitHub, which sees the TV's IP address; no identifiers are sent. |
| About > Support the project shows QR codes that lead to Ko-fi and CloudTips (the links are in `res/values/support.xml`, a theme can change them or leave them empty). | Nothing: the launcher draws the codes and never contacts those services; a phone that reads one does. |
| Phone and cloud (Launcher settings), **off by default**: when a parent turns it on, the TV registers with the cloud server and talks to it over HTTPS. | The server learns the name of the TV, the package names and labels of the apps in front and when the screen was on, the last journal events and the messages sent; not the parents' code, the Wi-Fi name or anything watched. Data older than 30 days is deleted, and turning the switch off deletes the TV's data on the server. See [cloud-protocol.md](cloud-protocol.md). |
| Licence keys are checked on the TV with no network. | Nothing is sent. |
| The cloud link (mode C) is a draft and is not in the launcher. | — |

Deleting data: use "Erase the children's mode data", clear the app's data in Android settings, or uninstall.
