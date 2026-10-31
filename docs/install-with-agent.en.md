# Installing Mika with an AI agent on your computer

[简体中文](install-with-agent.md) | English

This document is written for **an AI agent on your computer that can run commands**. With the phone connected over USB, the agent follows the steps below and uses adb to install and set up Mika. People can follow it too: every command can be copied as is.

If you are the user: connect the phone to the computer with a USB cable, turn on USB debugging on the phone, and send this to the AI agent on your computer:

```
Follow https://github.com/wangzan101/Mika/blob/main/docs/install-with-agent.en.md to install Mika on the Android phone connected over USB.
```

If you are the AI agent: read the "Rules" first, then work through the steps one at a time, starting at step 1.

## Rules

1. **Only install and set up Mika.** Do what this document describes and nothing else.
2. **Leave unrelated system settings alone.** Do not change the lock screen, screen timeout, display, sound, network, other developer options, or any setting this document does not mention.
3. **Never uninstall an app or clear any data**, including Mika itself. If uninstalling is needed, explain why and let the user decide and do it.
4. **Never handle the user's model API key.** Do not ask for it, type it, or send it over adb. The user enters it in Mika.
5. **Stop and tell the user whenever a step needs a tap on the phone.** Say what will appear on screen and which button to tap, and wait until the user says it's done.
6. **Before each step, briefly tell the user what you are doing and why, in the user's language.** When a command fails, show the user the exact error, then the likely causes.
7. The commands below are written for a Unix-style shell (macOS, Linux, or WSL / Git Bash on Windows). In Windows PowerShell, rewrite them to do the same thing.

The package name is `com.wangzan101.mika`.

## Step 1: Check the prerequisites

### 1.1 adb

```
adb version
```

Success: it prints `Android Debug Bridge version ...`.

If adb is missing, tell the user to download "SDK Platform-Tools" from the Android developer site (there are builds for Windows, macOS and Linux), unzip it and add the folder to PATH, or install platform-tools with their system's package manager. Check again afterwards.

### 1.2 The phone is connected and authorized

```
adb devices
```

Success: **exactly one** device is listed, with state `device`.

- State is `unauthorized`: ask the user to look at the phone and tap "Allow" on the "Allow USB debugging?" prompt (ticking "Always allow from this computer" is fine), then run `adb devices` again.
- The list is empty: ask the user to check that the cable carries data (some cables only charge), that Developer options and USB debugging are on, and to set the USB mode to "File transfer", then try again. Steps 1 and 2 of the [phone control setup guide](setup-guide.en.md) show how to turn on USB debugging.
- More than one device: ask the user to unplug the others or tell you which one to use, then write every command as `adb -s <serial> ...`.

### 1.3 Android version

```
adb shell getprop ro.build.version.sdk
```

- Below 30 (older than Android 11): Mika cannot be installed. Tell the user the system is too old and stop.
- 30 to 32 (Android 11 and 12): Mika installs, but cannot operate apps (the background screen needs Android 13). Only web, files and chat work. Tell the user and ask whether to continue.
- 33 or higher (Android 13+): tasks work. 34 or higher (Android 14+) is best.

### 1.4 CPU architecture

```
adb shell getprop ro.product.cpu.abi
```

- `arm64-v8a`: the Linux sandbox works.
- Anything else: Mika installs and works normally, just without the Linux sandbox (Settings shows "not supported on this device"). Let the user know and carry on.

## Step 2: Download the latest APK

Ask the GitHub API for the latest release, pick the asset named like `Mika-v*.apk`, and download its `browser_download_url`:

```
URL=$(curl -fsSL https://api.github.com/repos/wangzan101/Mika/releases/latest \
  | grep -o '"browser_download_url": *"[^"]*/Mika-v[^"]*\.apk"' \
  | sed 's/.*"\(https[^"]*\)"/\1/' | head -n 1)
echo "$URL"
curl -fL -o Mika.apk "$URL"
```

Success: `$URL` looks like `https://github.com/wangzan101/Mika/releases/download/v0.1.N/Mika-v0.1.N.apk`, `curl` reports no error, and `Mika.apk` is in the current folder.

If jq is available, parsing the JSON with it is more robust; the idea is the same: take the entry in `assets` whose name matches `Mika-v*.apk`.

If GitHub cannot be reached: ask the user to open [Releases](https://github.com/wangzan101/Mika/releases/latest) in a browser, download the APK by hand and tell you where it was saved, then continue from step 3.

## Step 3: Install

```
adb install -r Mika.apk
```

Success: the last line is `Success`.

- Some phones show a confirmation on the phone for installs over USB. Ask the user to watch the phone and tap "Allow" or "Install" when it appears. The command waits until they do.
- On failure, show the user the exact error. Common ones:
  - `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: a copy of Mika signed with a different key (for example a self-built one) is already installed. The only way to install this one is to uninstall the old one first, and **its chats and settings will be lost**. Explain this and ask whether the user wants to uninstall it themselves. Do not uninstall it for them. Once they have, run the install again.
  - `INSTALL_FAILED_VERSION_DOWNGRADE`: the Mika on the phone is newer than this APK. No install needed; go to step 4.
  - `INSTALL_FAILED_USER_RESTRICTED` or `INSTALL_FAILED_ABORTED`: the user declined on the phone, or the system blocks installs over USB. Ask the user to look in Developer options for a switch like "Install via USB" and turn it on (only some phones have it), then retry.
  - `INSTALL_FAILED_INSUFFICIENT_STORAGE`: the phone is out of space; ask the user to free some up.

## Step 4: Notification permission (Android 13 and later)

Mika uses notifications to show task progress and to ask the user to take over. Skip this step if the SDK level is below 33.

```
adb shell pm grant com.wangzan101.mika android.permission.POST_NOTIFICATIONS
```

Success: no output.

## Step 5: Turn on Mika's accessibility service

Mika uses its accessibility service to read the screen and tap. Turn it on by writing the system setting over adb. The reason: for apps installed from outside an app store, Android often blocks turning accessibility on by hand ("Restricted setting"); writing the setting over adb does not hit that block.

Mika's accessibility service component (from `MikaAccessibilityService` in `app/src/main/AndroidManifest.xml`):

```
com.wangzan101.mika/.a11y.MikaAccessibilityService
```

This is the same component as the long form `com.wangzan101.mika/com.wangzan101.mika.a11y.MikaAccessibilityService`; accept either form when checking whether Mika is already in the list.

**Do not remove other accessibility services the user already has on.** Read the current list, append Mika only if it is missing (entries are separated by colons), and write it back:

```
ME=com.wangzan101.mika/.a11y.MikaAccessibilityService
CUR=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
echo "current: $CUR"
case ":$CUR:" in
  *":$ME:"*|*":com.wangzan101.mika/com.wangzan101.mika.a11y.MikaAccessibilityService:"*)
    NEW="$CUR" ;;
  *)
    if [ -z "$CUR" ] || [ "$CUR" = "null" ]; then NEW="$ME"; else NEW="$CUR:$ME"; fi ;;
esac
adb shell settings put secure enabled_accessibility_services "'$NEW'"
adb shell settings put secure accessibility_enabled 1
adb shell settings get secure enabled_accessibility_services
```

Success: the last output contains Mika's component, and every service that was there before is still there.

Notes: `settings get` prints `null` for a setting that was never written; treat it as an empty list. The value is wrapped in single quotes when written back because some service names contain a `$`.

## Step 6: Battery allowlist

Add Mika to the system's battery-optimization allowlist so background and scheduled tasks are not cut off:

```
adb shell cmd deviceidle whitelist +com.wangzan101.mika
```

Success: it prints `Added: com.wangzan101.mika` (also when it was already there).
If the phone does not accept `cmd`, use `adb shell dumpsys deviceidle whitelist +com.wangzan101.mika` instead; it does the same.

Check:

```
adb shell cmd deviceidle whitelist | grep com.wangzan101.mika
```

## Step 7: Start Mika's phone helper

Mika has a built-in phone helper that launches and operates apps on the background screen. It needs Android's own debugging feature to start. There are two ways; let the user choose:

- **A. Pair wireless debugging (recommended, set up once)**: pair once, and the helper comes back by itself after restarts, with no computer needed.
- **B. Start over USB**: works right now while the cable is plugged in, no pairing; but after every phone restart it has to be run again from the computer (unless A is also done).

Doing both is fine: B gets things working right away, A makes it permanent.

First, open Mika once (both ways need this):

```
adb shell am start -n com.wangzan101.mika/.ui.MainActivity
```

`adb shell monkey -p com.wangzan101.mika 1` also works. On first launch Mika walks the user through adding a model; they can skip that for now ("Not now" or similar) and do it in step 8.

### A. Pair wireless debugging

1. Turn on wireless debugging:

   ```
   adb shell settings put global adb_wifi_enabled 1
   adb shell settings get global adb_wifi_enabled
   ```

   Success: the second command prints `1`. The phone must be on Wi-Fi (internet access not required). If the phone asks "Allow wireless debugging on this network?", ask the user to tap "Allow" and tick "Always allow on this network". If it reads back `0`, the phone is probably not on Wi-Fi, or the user did not allow it in that prompt.

2. Tell the user: in Mika, open Settings › Phone control to reach the "Set up phone control" (设置手机操作) wizard. (It also opens on first launch after the model setup. If it does not show up, tap the notice in the status card at the top of Settings, or tap Settings › Phone control › Pair again.) The wizard checks Developer options, USB debugging and Wireless debugging by itself and ticks the steps that are already done.

3. The user has to pair on the phone themselves: in the system's "Wireless debugging" page, tap "Pair device with pairing code", and enter the 6-digit code in Mika's notification (or let Mika read it). You cannot pair for them. Wait until the user says pairing is done.

4. For where to tap at each step and what to do when stuck, point the user to the [phone control setup guide](setup-guide.en.md).

### B. Start over USB

Mika must have been opened at least once, so it can write its start script to the phone. With the cable plugged in, run:

```
adb shell sh /sdcard/Android/data/com.wangzan101.mika/files/usb-start.sh
```

- If it says the file does not exist: open Mika with the command above, wait a few seconds and try again. If it is still missing, the Mika version on the phone may not have this feature yet; use A instead.
- Once it succeeds, the cable can be unplugged; the helper keeps running.
- After a phone restart the helper stops, and this command has to be run again from the computer; not needed if A is done too.

### Check that it is running

Ask the user to open Mika's Settings and look at the status card at the top: "Phone control" shows ready (with a model set up too, it says "Model set up · Phone control ready"). If it says "Phone control isn't ready", redo the steps above or see "Troubleshooting" below.

## Step 8: Add a model

Tell the user: in Mika, open Settings › Model, add a provider, enter their own API key and tap "Test and save"; or, when adding a provider, tap "Continue with ChatGPT" and sign in with a ChatGPT account (Plus or Pro required).

**The user does this step entirely on the phone.** Do not ask for the key, and do not type or send it over adb.

## Step 9: Check the result

```
adb shell dumpsys package com.wangzan101.mika | grep versionName
adb shell settings get secure enabled_accessibility_services
```

Success: the first shows the version just installed; the second contains Mika's component.

Then suggest a harmless first task for the user to send in Mika, for example:

```
Open Settings and tell me the Android version
```

## Step 10 (optional): Connect your AI tool to Mika (MCP over USB)

Do this step only if the user wants AI tools on the computer (you included) to hand tasks to Mika. Ask first. Mika then works as an MCP server that listens on the phone itself only; the computer reaches it over the USB cable through adb port forwarding. Nothing needs to be tapped on the phone. Details: [Computer access (MCP)](mcp.en.md).

1. Turn it on. This also starts the phone helper from step 7 if it isn't running:

   ```
   adb shell sh /sdcard/Android/data/com.wangzan101.mika/files/connect-computer.sh
   ```

   Success: it ends with "Computer access over USB is on" and prints a JSON object with `url`, `headers` and `token`. If it says the file doesn't exist, open Mika once (step 7), wait a few seconds and try again.

2. Forward the port:

   ```
   adb forward tcp:8765 tcp:8765
   ```

3. Read the token again whenever you need it (it is the `token` field):

   ```
   adb shell cat /sdcard/Android/data/com.wangzan101.mika/files/mcp-usb.json
   ```

4. Check that it answers (put the token in):

   ```
   curl -s http://127.0.0.1:8765/mcp \
     -H "Authorization: Bearer <token>" \
     -H "Content-Type: application/json" \
     -H "Accept: application/json, text/event-stream" \
     -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
   ```

   Success: a JSON reply whose `result` has `serverInfo`.

5. The MCP client config: a Streamable HTTP (often called "http") server with URL `http://127.0.0.1:8765/mcp` and the header `Authorization: Bearer <token>`. Most clients take something like:

   ```json
   {
     "mcpServers": {
       "mika": {
         "type": "http",
         "url": "http://127.0.0.1:8765/mcp",
         "headers": { "Authorization": "Bearer <token>" }
       }
     }
   }
   ```

   If the user wants it, add Mika to **your own** MCP configuration in the format your tool uses. Don't change any other entry. Then tell the user to restart you or reload your MCP configuration if your tool needs that. Don't show the token in the chat unless the user asks for it.

Tell the user:

- After unplugging and replugging the cable (or after adb restarts), `adb forward tcp:8765 tcp:8765` has to be run again. The token stays the same.
- After a phone restart USB mode is off. Run the command in 1 again; the token changes then, so the MCP configuration needs the new one.
- To turn it off: `adb shell sh /sdcard/Android/data/com.wangzan101.mika/files/connect-computer.sh off`, the switch in Mika's Settings › Computer access (MCP), or "Turn off" on the notification. The old token stops working at once.

## Troubleshooting

| Problem | What to do |
|---|---|
| The phone is not in `adb devices` | Use a cable that carries data; check that USB debugging is on; set the USB mode to "File transfer"; unplug and plug in again. |
| State is `unauthorized` | Ask the user to tap "Allow" on the phone's "Allow USB debugging?" prompt. No prompt: unplug and replug, or tap "Revoke USB debugging authorizations" in Developer options and plug in again. |
| The install is blocked | Ask the user to check the phone for a confirmation prompt, and to turn on a switch like "Install via USB" in Developer options if there is one. For the different-signature error see step 3; uninstalling is the user's call. |
| Accessibility turns itself off again | Redo step 5. Once wireless debugging is paired, Mika turns it back on by itself (on by default in Settings › Recovery & repair). |
| The phone helper is not running after a restart | With A: connect to Wi-Fi and wait a moment; Mika recovers by itself. If not, tap Pair again in Settings › Phone control. With only B: run the command from step 7 B again from the computer, or set up A. |
| GitHub cannot be reached | Ask the user to open [Releases](https://github.com/wangzan101/Mika/releases/latest) in a browser and download the APK by hand, then run `adb install -r <path to the APK>`. |
| Wireless debugging won't turn on | The phone must be on Wi-Fi, and the user must allow wireless debugging on this network in the prompt. If it still won't, use B. |
| The AI tool can't reach Mika over USB (step 10) | Run `adb forward tcp:8765 tcp:8765` again (needed after every replug). After a phone restart, run the step 10 command that turns it on again and update the token. |
| The forward fails with `cannot bind`, or the reply isn't from Mika (step 10) | Port 8765 is taken on the computer. Forward from another free port, e.g. `adb forward tcp:18765 tcp:8765`, and use `http://127.0.0.1:18765/mcp` in the config; nothing changes on the phone. |

## Finally: a short summary for the user

When everything is done, tell the user in their language:

- **What was done**: which version was installed; whether notifications, accessibility and the battery allowlist are set; how the phone helper was started and whether it is ready. Say honestly if a step failed or was skipped.
- **What the user still needs to do**: add a model in Settings › Model (if not done yet); pair wireless debugging (if they chose A but haven't finished); if only B was used, remind them to run that command again from the computer after a phone restart; then send a simple task to try it out. If step 10 was done: whether Mika was added to your MCP configuration, and that `adb forward` must be run again after replugging the cable, and the step 10 command after a phone restart.
