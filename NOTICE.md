# Notice

This launcher is a fork of **cleanyourtv** (package `com.gathin.tvhome`), copyright (c) 2026 Gathin, released under the
MIT licence (see `LICENSE`). It is also inspired by the home-screen ideas of Projectivy Launcher and AT4K Launcher; no code
from either of them is used.

## What we added

Children's mode with a schedule and phone control without a home server, the setup and health screen, the Watch next
shelf, screen-time tables, offline licence keys, settings backup, and translations of those parts. The added code lives
mostly in `launcher/app/src/main/java/com/gathin/tvhome/family/` and the `Kids*`, `Family*`, `Setup*`, `WatchNext*`,
`Poster*`, `Entitlements` and `SettingsFiles` classes. Our changes are released under the same MIT licence.

## Third-party components

| Component | Licence | Where |
|---|---|---|
| cleanyourtv (upstream launcher) | MIT, (c) 2026 Gathin | the whole code base, `LICENSE` |
| Outfit font | SIL Open Font Licence 1.1 | `launcher/FONT-LICENSE-OFL.txt` |
| AndroidX RecyclerView, DynamicAnimation | Apache-2.0 | linked libraries |
| Open-Meteo weather data (only if a theme sets coordinates) | CC BY 4.0 | fetched at run time |
| The "Sweden" theme's artwork | see `themes/sweden/CREDITS.md` | `themes/sweden/` |

The ISO/IEC 18004 QR encoder, the Ed25519 signature check and the PBKDF2 PIN hash are written for this project (the
Ed25519 check follows RFC 8032 and is tested against its vectors).

## Names

"CleanYourTV" and its mascot belong to the upstream project and are not used for this fork's product name.
