# Connect the phone directly to a Feishu bot

An opt-in Android channel receives text commands and sends results over an outbound Feishu long connection. It needs neither a PC bridge nor Tailscale, MCP, or an inbound public port. Private text chats with an enterprise custom-app bot are supported; group chats, attachments, card callbacks, and international Lark domains are not.

## Setup

1. Create an enterprise custom app and enable its bot capability.
2. Grant `im:message.p2p_msg:readonly` and `im:message:send_as_bot`.
3. In Mika Settings › Feishu bot, enter the App ID and App Secret, enable the connection, and save.
4. Under Event configuration, select long connections and subscribe to `im.message.receive_v1`. In the separate Callback configuration tab, also enable long connections and add `card.action.trigger`. Publish a version available to your account. Message subscription alone does not enable card buttons. Feishu may require a live phone connection before saving.
5. Send `/pair <pairing-code>` to the bot in a private chat. The code is shown only in phone settings. After pairing, only that user in that private chat can issue commands.

Use one app per phone. Stop a PC bridge for the same application before enabling this channel: multiple Feishu connections distribute events rather than broadcasting every event to all clients.

Credentials, owner binding, inbound receipts, and delivery attempts are encrypted using Android Keystore and stored outside system backup. Changing credentials clears binding. Unpairing generates a new code. Disabling or unpairing does not cancel phone tasks already accepted.

## Commands and notifications

This version uses the v0.1.36 native channel registry, acceptance acknowledgements and Outbox. `/tasks` or `/任务` sends an interactive menu. Tapping the dropdown opens title options for all Mika conversation windows; select one to view it, or tap New task to select a fresh draft window. Lists are paginated in groups of 20, with short IDs to distinguish duplicate titles. Selection and creation never mutate another run.

Plain text starts a new task only in the selected idle window. `/new` selects a fresh draft even while other windows run; `/status` displays the selected window. Native Mika settings determine whether separate windows execute in parallel or queue. A message in an already busy window is not silently steered into its task. `回复: answer`, `追加: instruction` and `/cancel` target the exact selected bot-owned run; `/retry` reuses an unconfirmed native channel message ID and its original window/content.

Owner-requested lists include local, scheduled, WeChat, legacy remote and Feishu windows in this installed app. Their titles and selected contents are sent to Feishu only when the paired owner requests them. Running tasks belonging to the phone UI or another channel are view-only; an idle conversation can accept a fresh channel-origin turn. This cannot read data from a separately installed official or Dev app. Old menus, deleted windows and stale run buttons cannot retarget a different run.

Native TaskService acknowledges each task once and independently routes every window’s final results and questions, labeled with its title. Changing selection does not move another task’s replies. Step-by-step notifications stay silent; window cards refresh only on request. Payment approvals and takeover remain on the phone. The computer remote task API is unchanged.

An explicit setting also mirrors final local task results, scheduled task results, fixed reminders, and schedule errors to the paired chat. Enabling it sends those results to Feishu. Unmet read-only condition checks remain silent. Long replies are shortened with a notice to read the full result on the phone.

## Recovery and limits

Events are persisted before acknowledgement, deduplicated by message ID, and mapped to stable task IDs. An interrupted command is not blindly replayed. Query an uncertain task before retrying; ambiguous Feishu message writes are not automatically retried. This is not a claim of exactly-once delivery across systems.

The user-enabled Android foreground service has a persistent notification and reconnects with backoff. Boot recovery is requested, but force-stop, locked credential storage, OEM background restrictions, and Android foreground-service rules can prevent it. Reopen Mika after force-stop. Physical-device verification of setup, network switching, screen-off, reboot, and background lifetime remains necessary in addition to JVM tests and Android compilation.

See the [Chinese guide](feishu.md) and the official [long-connection documentation](https://open.feishu.cn/document/ukTMukTMukTM/uYDNxYjL2QTM24iN0EjN/event-subscription-configure-).
