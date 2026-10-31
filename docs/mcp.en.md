# Computer access (MCP): let AI tools on your computer hand tasks to Mika

[简体中文](mcp.md) | English

With "Computer access" on, Mika is an MCP server: an AI tool on your computer that speaks MCP (an MCP client) can hand Mika a job, and Mika does it on the phone its own way (operating apps, using the built-in browser, working with files), then hands back the result. The computer says what to get done; Mika on the phone decides how.

There are two ways to connect. Each has its own switch and token, and both can be on at once:

- **Over USB (recommended)**: with the phone on a cable and USB debugging on, one command on the computer turns it on, without touching the phone. It listens on the phone itself only, so it can't be reached over Wi-Fi.
- **Over Wi-Fi or a private network**: turned on in the phone's settings, off by default. When the computer and the phone are on the same Wi-Fi, or in the same private VPN network (a virtual LAN made by a mesh networking tool), they can connect without any port forwarding on your router. This is also how you use Mika remotely.

## Connecting over USB (recommended)

You need adb on the computer, the phone listed in `adb devices`, and Mika opened at least once (that's when it writes the script onto the phone).

1. On the computer, run:

   ```
   adb shell sh /sdcard/Android/data/com.wangzan101.mika/files/connect-computer.sh
   ```

   It first starts Mika's phone helper (the same as "Start from a computer"; skipped if it's already running), then turns USB mode on, and finally prints the connection info, like this:

   ```json
   {
       "transport": "streamable-http",
       "url": "http://127.0.0.1:8765/mcp",
       "headers": {
           "Authorization": "Bearer <token>"
       },
       "token": "<token>",
       "port": 8765,
       "forward": "adb forward tcp:8765 tcp:8765",
       "boot": "17"
   }
   ```

2. Forward port 8765 on the computer to the phone:

   ```
   adb forward tcp:8765 tcp:8765
   ```

3. In your MCP client, add a server of type Streamable HTTP (also called "HTTP"): URL `http://127.0.0.1:8765/mcp`, header `Authorization: Bearer <token>`. See the config example under "Connecting over Wi-Fi or a private network" below; just use `http://127.0.0.1:8765/mcp` as the URL.

To see the connection info again later:

```
adb shell cat /sdcard/Android/data/com.wangzan101.mika/files/mcp-usb.json
```

Once it's on:

- A "Computer access is on" notification stays in the shade, and it stays on while Mika is in the background. On the phone, Settings › Computer access (MCP) shows "Connected through USB (started from a computer)".
- **After you unplug and replug the cable** (or adb on the computer restarts), run `adb forward tcp:8765 tcp:8765` again. The token stays the same.
- Running the command in step 1 again while it's on keeps the token, so clients you already set up keep working.
- **It turns off when the phone restarts.** Run the command in step 1 again; the token is new then, so update it in your client config.

To turn it off (any of these; the old token stops working at once and the connection info file is deleted; the next time you turn it on you get a new token):

```
adb shell sh /sdcard/Android/data/com.wangzan101.mika/files/connect-computer.sh off
```

Or switch off "Connect through USB" in Settings › Computer access (MCP) on the phone, or tap "Turn off" on the notification (which also turns off the Wi-Fi mode if it's on).

What the script does can also be done with plain commands (after turning it on, the connection info file takes a second or two to appear):

```
adb shell am start-foreground-service -n com.wangzan101.mika/.mcp.McpService -a com.wangzan101.mika.mcp.USB_ON
adb shell cat /sdcard/Android/data/com.wangzan101.mika/files/mcp-usb.json
adb shell am start-foreground-service -n com.wangzan101.mika/.mcp.McpService -a com.wangzan101.mika.mcp.USB_OFF
```

## Connecting over Wi-Fi or a private network

### Turning it on

1. In Mika, open Settings › Computer access (MCP) and turn on "Wi-Fi and private networks". It is off by default.
2. While it is on, a "Computer access is on" notification stays in the shade to show Mika is listening in the background; its "Turn off" button switches it off right there.
3. The page shows:
   - **Address**: like `http://192.168.1.23:8765/mcp`. One per network the phone is on (Wi-Fi, private VPN); tap one to copy it.
   - **Access token**: a random string. Copy it, or use "New token" (computers still using the old one stop working).
   - **Client config**: the address and token put together; tap "Copy config".
   - **Status**: whether it is listening, how many clients connected recently, and when the last request came in.

The token is stored encrypted on the phone, the same way as provider API keys. Turning the switch off closes the port (if USB mode is still on, only the listener on the phone itself stays).

### Connecting from a computer

- **Same Wi-Fi**: use the Wi-Fi address from the list (usually `192.168.*.*` or `10.*.*.*`).
- **Private VPN network**: once the phone and the computer are in the same network, use that network's address from the list (these networks often use the `100.64.*.*` to `100.127.*.*` range).
- When the phone is on mobile data, or the other side has a public address, Mika refuses the connection.

In your MCP client, add a server of type "HTTP" (also called Streamable HTTP), enter the address, and add the header `Authorization: Bearer <token>`. Each client words its config file a little differently; most look like this:

```json
{
  "mcpServers": {
    "mika": {
      "type": "http",
      "url": "http://192.168.1.23:8765/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

You can also try it from a terminal first (use your own address and token):

```sh
curl -s http://192.168.1.23:8765/mcp \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
```

## Tools

| Tool | Arguments | What it does |
| --- | --- | --- |
| `mika_run_task` | `goal` (required), `conversation` (optional) | Hands Mika a job and returns a `task_id` right away. The task is recorded in a conversation you can see in the app. To follow up on an earlier job, pass the `conversation` that task returned. |
| `mika_task_status` | `task_id` | The task's state now: status, latest progress, what it is waiting for, and the answer when done. |
| `mika_wait_task` | `task_id`, `timeout_s` (optional, default 30, at most 50) | Waits until the status changes (for example Mika starts waiting for an answer, or finishes) or time runs out, then returns the state. Progress notes alone don't count, so it doesn't return on every step. |
| `mika_answer` | `task_id`, `text` | Answers the question Mika asked in this task. |
| `mika_cancel_task` | `task_id` | Stops the task. |
| `mika_list_tasks` | `limit` (optional, default 10) | The most recent tasks handed over from computers, newest first. |

Statuses:

- `starting`: just handed to the phone, not started yet.
- `running`: in progress, with the latest progress note.
- `paused`: waiting for a condition, such as the phone being unlocked (it carries on by itself afterwards), or waiting in the queue.
- `waiting_for_answer`: Mika asked a question; answer with `mika_answer` (answering on the phone works too). If nobody answers for a long time, Mika carries on as if there were no answer.
- `waiting_on_phone`: this step can only be handled by a person on the phone, such as a step handed over for you to do yourself. The computer can't answer it; wait or cancel. The phone shows its usual notification.
- `done`: finished, with the answer; `verified` says whether the answer was checked.
- `failed`: not done, with the reason.
- `cancelled`: stopped.

Suggested flow: `mika_run_task` → `mika_wait_task` repeatedly → on `waiting_for_answer`, reply with `mika_answer` → until `done` / `failed` / `cancelled`.

## Security

- **Off by default**; it listens only while on and stops at once when turned off.
- **USB mode listens on the phone itself (127.0.0.1) only**; it can't be reached over Wi-Fi or any other network, and the computer comes in through adb's port forwarding. Other apps on the phone can reach that port too, so the token is still required, and the USB token is only accepted from the phone itself.
- **Only the computer can turn USB mode on and get its token**: Mika's service only accepts callers holding the `INTERACT_ACROSS_USERS_FULL` permission. The adb shell has it; ordinary apps can't get it (and adb can't grant it to other apps). The token is written in Mika's external files folder (`/sdcard/Android/data/com.wangzan101.mika/files/mcp-usb.json`); since Android 11 other apps can't read that folder, only Mika and the adb shell can.
- **Every request needs the token.** The token is 32 random bytes and is compared in constant time; 5 wrong tries from one address within a minute lock that address out for a minute.
- **No browser requests**: anything carrying an `Origin` header is refused, so web pages can't reach the port through your browser.
- **Private networks only**: both ends must be on a LAN, a private VPN network, or the phone itself; connections from public addresses are dropped immediately.
- **Spending and confirmations stay on the phone**: in tasks handed over from a computer, payments and actions that need your approval are never carried out, whether or not "Autonomous mode" is on; a step handed over for you to do yourself can only be done on the phone. `mika_answer` answers questions only and cannot approve any of these.
- **Only the computer's own tasks**: a computer can't see or stop tasks you started on the phone, and the conversations it opens use a fixed prefix, so it can't join your conversations on the phone.
- Every run record notes that the run was started by a computer (MCP).
- The transport is plain HTTP, so use it only on networks you trust. Don't share the token; if you think it leaked, use "New token".

## When it doesn't work

- **No address in the list**: the phone isn't on Wi-Fi or a private VPN network.
- **Status says "Couldn't start"**: another app may be using the port; turn the switch off and on again.
- **The USB script says the file doesn't exist**: open Mika once, wait a few seconds, and try again. If it's still missing, the Mika on the phone doesn't have this feature yet.
- **Can't connect over USB** (`curl` reports connection refused or reset): run `adb forward tcp:8765 tcp:8765` again; if the phone restarted, run the command that turns it on again first.
- **Port 8765 is already used on the computer** (the forward fails with `cannot bind`, or you get a response that isn't from Mika): pick another free port on the computer, e.g. `adb forward tcp:18765 tcp:8765`, and use `http://127.0.0.1:18765/mcp` in the config. Nothing changes on the phone.
- **HTTP 429**: too many wrong tokens from one address; wait a minute. Over USB, everything on the phone that connects to the local port counts as the same address.
- **The computer can't connect**: make sure both are on the same network; some Wi-Fi networks (guest networks, for example) block devices from reaching each other.
- **The task never starts** (the call fails right away, or the task turns `failed` after two minutes): open Mika on the phone and try again. Android sometimes stops a background app from starting work on its own; once you've opened Mika it works.
- **The task stays `paused`**: the phone is most likely locked. Mika needs the phone unlocked to work.
