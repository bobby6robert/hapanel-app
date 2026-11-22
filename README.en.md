<img src="images/logo.png" width="96" align="right" alt="RB IT Academy">

# HA Panel

🇵🇱 [Polski](README.md) · 🇬🇧 English

A light, free Android app that turns a tablet into a wall panel for [Home Assistant](https://www.home-assistant.io/). An alternative to Fully Kiosk Browser with no licence and no third-party dependencies. Made with older tablets in mind; runs on Android 8 and newer.

Full control from HA comes with the integration: **[hapanel-integration](https://github.com/bobby6robert/hapanel-integration)**.

The app speaks **Polish and English** – choose *System / Polski / English* at the top of the settings.

## Features

- Full-screen WebView with your HA dashboard, screen always on, automatic retry when the connection drops.
- Autostart after reboot and exit lock (app pinning). **Quick menu:** 5 taps in any screen corner or hold two fingers for 2 s.
- Settings split into categories (Start, Screen, Aurora, Announcements, Tablet) with a **checklist** of missing permissions and one-tap fixes.
- Automatic discovery by HA (mDNS), remote configuration from the integration, pairing with a 6-digit code.
- **Night mode:** schedule, dark clock with date and temperatures, low brightness.
- **Screensaver:** clock and weather, family photos (folder in HA `/media`), up to 4 cameras, or a black screen.
- **Popup window** (`/popup`) for the doorbell: shows a camera or page on top and returns to the previous view.
- **Fallback addresses** when the main URL is down.
- **Live events** (motion, touch, low battery, screen, voice) sent straight to an HA webhook.
- **Aurora voice assistant:** wake word (offline, Vosk), conversation through HA (STT, agent, TTS), quiet hours, silence when nobody is home, “Quiet for an hour”.
- **Record Aurora:** household members record the wake word; the tablet compares what it hears with the recordings (MFCC + DTW, on-device), rejects the TV and unknown voices, and knows who is speaking.
- **Watchdog:** a frozen screen or a page without an HA connection is reloaded or the app restarts.
- Updates from GitHub Releases, settings backup (JSON), PIN for the settings, motion detection with the front camera, auto brightness.

## Installation

1. Install the APK from [Releases](https://github.com/bobby6robert/hapanel-app/releases) (allow unknown sources) and start it.
2. The tablet shows a **pairing code** and announces itself on the local network.
3. In HA a discovered *HA Panel* device appears (Settings → Devices & services). Click **Configure**, enter the code, confirm. The integration sends the dashboard address and all settings.
4. Android permissions that cannot be granted remotely (microphone, autostart, battery) are listed in the checklist with “Fix” buttons.

A fixed IP for the tablet (DHCP reservation) is recommended.

## HTTP API (GET, port 8765)

Add `token=...` to every request when an API token is set.

| Endpoint | Action |
|---|---|
| `/popup?url=...&seconds=30&wake=1&title=...` | popup on top, `/closepopup` closes it |
| `/clock?out=...&in=...&info=...` | temperatures and info on the night clock |
| `/saver?photos=...&cams=...&weather=...` | screensaver data (photo and camera URLs, one per line; weather text) |
| `/screensaver?on=1\|0` | show or close the screensaver |
| `/quiet?minutes=60` | Aurora quiet for N minutes (`0` ends it) |
| `/settings` | open the settings on the tablet |
| `/screenshot?scale=0.5&quality=70` | screenshot (JPEG) |
| `/export` | settings backup as JSON (no PIN or tokens) |
| `/update?url=...` | download an APK from `https://github.com/...` and install it |
| `/info`, `/status` | device info / state as JSON |
| `/config?...` | remote configuration (used by the integration) |
| `/wake`, `/sleep` | wake / sleep the screen |
| `/brightness?value=0-255`, `/auto?on=1\|0` | brightness, auto brightness |
| `/load?url=...`, `/reload`, `/restart` | load URL, reload, restart panel |
| `/tts?text=...&wake=1&volume=0-100` | voice announcement |
| `/playsound?url=...`, `/stopsound`, `/volume?value=0-100` | sound and volume |
| `/toast?text=...&wake=1` | short on-screen message |

## Building

GitHub Actions builds the APK on every commit; publishing a release attaches `hapanel.apk`. Release builds need the repository secret `HAPANEL_KEYSTORE` (PKCS12 key in base64).

## Licence

See [LICENSE](LICENSE).
