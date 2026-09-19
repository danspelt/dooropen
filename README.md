# Buddy

An accessibility-first Android app that opens an apartment door via a SwitchBot
Bot — designed for hands-free use with voice control, switch access, and
proximity auto-open.

## What it does

- **One-tap door open** — a single large `OPEN DOOR` button, focus-ordered for
  switch access.
- **Buddy voice control** — say "Buddy, open door" hands-free.
- **Proximity auto-open** — walks up to the door and opens automatically when
  the Bot's BLE signal is close enough (RSSI-based distance estimate).
- **Headset switching** — move a Bluetooth headset between the phone and a
  Windows computer from the app, via the `buddy-windows` companion server.
- **Tecla / app-shortcut support** — open the door from a switch or a pinned
  Android shortcut with a configurable trigger key.
- **Direct BLE mode** — control the Bot without a SwitchBot Hub (needs the
  Bot's MAC address; phone must be in range).

## Safety gates

Opening can be blocked until conditions are met:

- Home Wi-Fi SSID check (only opens when connected to your home network)
- Bluetooth-must-be-on check
- Cooldown between opens
- Keyguard lock check

Failure states speak the reason aloud (TTS) and show it on screen.

## Feedback

- Spoken status via Text-to-Speech
- Sound cues for opening / success / failure (toggleable)
- Vibration for open, success, and failure (toggleable)
- Live-region announcements for screen readers
- Bot battery level bar and connection status indicator
- Optional BLE debug panel showing raw advertisement bytes

## Requirements

- Android 7.0+ (`minSdk 24`, `targetSdk 35`)
- A [SwitchBot Bot](https://www.switch-bot.com/products/switchbot-bot)
  mounted on the door's push-bar / lock
- Either SwitchBot cloud API credentials (Open Token, Secret, Device ID)
  **or** the Bot's BLE MAC address for hub-free control
- Optional: `buddy-windows` companion app for headset switching

## Setup

1. Install the APK (or build from source, below).
2. Open **Settings** (gear icon, bottom of the main screen).
3. Enter SwitchBot credentials — tap **❓** in the app for step-by-step
   instructions on finding them in the SwitchBot app.
4. Or enable **Use Bluetooth (no hub)** and enter the Bot's MAC address.
5. Configure safety options and Buddy voice mode as desired.

## Building

```bash
# Windows
.\gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

Output APK: `app/build/outputs/apk/debug/app-debug.apk`

## Verification

```bash
.\gradlew.bat test            # unit tests
.\gradlew.bat lint            # Android lint
.\gradlew.bat assembleDebug   # build
```

## Companion app

`buddy-windows/` contains a Node.js server that pairs with the app to move a
Bluetooth headset between the phone and the Windows machine it runs on.
