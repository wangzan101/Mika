# Install, update and remove the computer CLI / Skill

[简体中文](computer-install.md) · [Connection and task commands](cli.en.md) · [MCP configuration](mcp.en.md)

**For computer access, normally choose either CLI + Skill or MCP; you do not need to set up both.** Use the table below to choose. Both require the phone APK; you can use both if you need to connect different clients.

| Goal | What to install |
| --- | --- |
| Run Mika on Android | Install the APK from [Releases](https://github.com/wangzan101/Mika/releases) and complete [phone setup](setup-guide.en.md). |
| Submit tasks from a terminal or script | Go to [CLI installation](#cli-install) and use `mika`. |
| Use Mika through a Skill in an agent such as Codex or Claude Code | Go to [Skill installation](#skill-install); it includes its own CLI script. |
| Use an MCP client | Go straight to the [MCP guide](mcp.en.md); skip CLI / Skill installation here. |

Follow only the steps for your chosen entry point. Skill-only use needs neither a global CLI installation nor pipx. Installing or updating computer tools does not install or update the phone APK; the model and required phone features still need to be set up separately.

Official APKs provide task API v1 starting in **v0.1.23**; for custom builds, rely on the compatibility check in `connect`. Examples pin `v0.1.23`; you can substitute a newer stable tag from Releases.

## CLI: install

The CLI requires Python 3.10+, Git and [pipx](https://pipx.pypa.io/latest/how-to/install-pipx.html); Python 3.11+ is recommended for a new environment. ADB connections also require [Android platform-tools](https://developer.android.com/tools/releases/platform-tools).

**macOS (Homebrew):**

```bash
brew install python pipx git
brew install --cask android-platform-tools
pipx ensurepath
```

**Debian / Ubuntu:**

```bash
sudo apt update
sudo apt install python3 python3-venv pipx git adb
pipx ensurepath
```

For other Linux distributions, follow pipx's instructions and use your distribution's package manager for dependencies.

**Windows (PowerShell):** Install [Python](https://www.python.org/downloads/windows/) with the `py` launcher and [Git](https://git-scm.com/downloads/win). Extract Windows platform-tools and add its directory to PATH, then run:

```powershell
py -m pip install --user pipx
py -m pipx ensurepath
```

Reopen the terminal and check `pipx --version`, `git --version` and `adb version`. Check Python with `python3 --version`, or `py -3 --version` on Windows.

With dependencies ready, install and verify from any directory (also works in PowerShell):

```bash
pipx install 'git+https://github.com/wangzan101/Mika.git@v0.1.23#subdirectory=tools/mika'
mika --version
mika --help
pipx list
```

The list should show package `mika-phone-cli` and command `mika`. pipx fetches the source from GitHub; you do not need to keep a checkout. No PyPI package is currently published. The CLI's own version (`0.1.0` in this tag) is numbered separately from the APK and does not report the phone version.

## CLI: update, roll back and uninstall

Record the current source tag with `pipx list --json`, then choose a target from Releases. To switch a pinned source, uninstall with pipx and install again, replacing the tag below with the target:

```bash
pipx uninstall mika-phone-cli
pipx install 'git+https://github.com/wangzan101/Mika.git@v0.1.23#subdirectory=tools/mika'
mika --version
pipx list --json
```

Use the newer tag to update or the previous tag to roll back; both must support API v1. Check the tag in the installation source because the CLI version may not change with each APK release. `pipx upgrade mika-phone-cli` does not switch to the next tag. If installation fails, restore the recorded previous tag.

For local source installations, use the same steps but install with `pipx install ./tools/mika` from the repository root.

To uninstall only, use the package name and confirm it is no longer listed:

```bash
pipx uninstall mika-phone-cli
pipx list
```

## Skill: install

The Skill's bundled script needs Python 3.10+, plus platform-tools for ADB connections. Use an installer or copy the folder manually; neither requires a global `mika` command.

### Use the skills installer

The [skills tool](https://github.com/vercel-labs/skills) additionally requires Node.js 22.20+ (including npm/npx) and Git. This installs for Codex at **user scope**, available across projects:

```bash
npx skills add https://github.com/wangzan101/Mika/tree/v0.1.23/tools/mika --skill mika --agent codex --global --copy
npx skills list --global --agent codex
```

For Claude Code, replace `--agent codex` with `--agent claude-code`; use `--agent codex claude-code` for both. `--copy` installs complete copies without retaining the download directory. For project scope, run from the project root and omit `--global`; use that same scope when listing, updating and removing it.

| Agent | User scope | Project scope |
| --- | --- | --- |
| Codex | `~/.agents/skills/mika/` | `.agents/skills/mika/` |
| Claude Code | `~/.claude/skills/mika/` | `.claude/skills/mika/` |

Check that the directory contains `SKILL.md` and `scripts/mika_cli.py`, then run the bundled script. For Codex at user scope:

```bash
python3 ~/.agents/skills/mika/scripts/mika_cli.py --version
```

On Windows PowerShell:

```powershell
py -3 "$env:USERPROFILE\.agents\skills\mika\scripts\mika_cli.py" --version
```

Use the `.claude` path for Claude Code. For project scope, remove the leading `~/` (use the current project's path on Windows). In Codex, select `mika` from the skill picker or use `$mika`; in Claude Code, use `/mika`. If it does not appear, restart the session and check the installation scope. See the official [Codex](https://developers.openai.com/codex/skills/) and [Claude Code](https://code.claude.com/docs/en/skills) discovery rules.

### Manual installation (no Node.js needed)

Download **Source code (zip)** from the selected release, extract it and copy the complete `tools/mika` folder to a destination from the table. The result should be `…/skills/mika/SKILL.md`, without an extra nested `mika` folder. If the destination exists, back it up and replace it as described below instead of merging folders. Verify the script and agent discovery as above.

## Skill: update, roll back and uninstall

The Skill and global CLI are maintained separately. If `mika` is already installed, the current Skill prefers that command; updating the Skill does not update the global CLI. Verify the command and source the agent actually uses, not just the bundled script's version.

**Installer-managed copies:** Repeat the original `skills add` command with the target tag in its URL, keeping the same agent and scope. Use a newer tag to update or the previous tag to roll back, then repeat the `skills list` and script checks. Back up any edits outside the skill directories first. Automation can add `--yes` to confirm replacing the skill.

For a complete uninstall, specify the skill name and installation scope:

```bash
npx skills remove mika --global --yes
npx skills list --global
```

For project scope, omit `--global` and run from the original project root. This removes Mika's copies for all agents in that scope while retaining other skills. Mika should no longer appear in the list.

**Manual copies:** To update or roll back, move the old `mika` folder outside the skill directories as a backup, then copy the complete folder from the target tag. To uninstall, delete only that `mika` folder. Restart the agent session to confirm the result. Manual copies have no installer-recorded source version; manage their updates and removal as folders.

## Connection check after installation

This section is for CLI / Skill; MCP users should check the [MCP guide](mcp.en.md). For USB or connected wireless ADB, open Mika once on the phone so it creates its connection script, then follow [Connect](cli.en.md#connect) to run `devices`, `connect` and `doctor`. With only the Skill installed, use its bundled script instead of `mika`.

For direct Wi-Fi / private-network access, use the URL and token-file instructions in that connection guide; skip the ADB `devices` / `connect` flow. `doctor` checks API connectivity and available device state, not Skill discovery, model configuration or real task success; see [Tasks](cli.en.md#tasks) to verify execution separately.

Removing the computer tools does not erase phone data or disable computer access. To stop remote access, turn off the relevant switch under Settings > Computer access on the phone.

## Troubleshooting

| Symptom | Action |
| --- | --- |
| `pipx` or `mika` not found | Run `pipx ensurepath` and reopen the terminal; on Windows, use `py -m pipx ensurepath` if needed. Use `pipx environment` to find installation directories. |
| `mika` still runs after uninstall | Locate other copies with `command -v mika` (`(Get-Command mika).Source` in PowerShell) and remove them with their original installer. For pip installations, run `python -m pip uninstall mika-phone-cli` inside that virtual environment. |
| `externally-managed-environment` | Install pipx through your system package manager, then install Mika with pipx instead of forcing it into system Python. |
| Skill is missing or still outdated | Check user/project scope, the loaded path and duplicate names. Confirm that scripts were copied, then restart the session. |
| ADB, connection script or API unavailable | Check that ADB is on PATH, the APK has been opened and it supports API v1. Select and authorize the phone using the [connection guide](cli.en.md#connect). |
| GitHub download fails | Download the release source zip manually, run `pipx install ./tools/mika` from its extracted root, or copy the Skill manually. |
