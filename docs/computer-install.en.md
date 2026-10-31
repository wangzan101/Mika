# Install, update and remove the computer CLI / Skill

[简体中文](computer-install.md) · [Task commands and API](cli.en.md) · [MCP configuration](mcp.en.md)

## Choose what you need

| Goal | What to install |
| --- | --- |
| Run Mika on Android | Install the APK from [Releases](https://github.com/wangzan101/Mika/releases) and complete [phone setup](setup-guide.en.md). |
| Run `mika` from a terminal or script | Install the CLI below. |
| Use Mika from Codex, Claude Code or another agent | Install the Skill below. It includes a CLI script and works on its own. |
| Use an existing MCP client | Follow the [MCP guide](mcp.en.md); CLI and Skill installation is optional. |

The APK, CLI and Skill are separate installations. The computer CLI/Skill shipped in **Mika v0.1.23** and require task API v1 on the phone. This guide pins examples to `v0.1.23`. Before installing or updating, choose a stable release and replace the tag in the commands with that release. Do not choose a tag older than v0.1.23. For custom APK builds, rely on the API compatibility check in `connect`.

The Python package is named `mika-phone-cli`; its command is `mika`. The CLI version (`0.1.0` in the v0.1.23 source) and APK version are numbered separately. `mika --version` does not report the phone version. `mika connect` checks API compatibility.

## CLI: install

### Prerequisites

The CLI requires Python 3.10+. For a new setup, use Python 3.11+ to meet current pipx requirements. Install Git and [pipx](https://pipx.pypa.io/latest/how-to/install-pipx.html). ADB connections also need [Android platform-tools](https://developer.android.com/tools/releases/platform-tools).

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

For other Linux distributions, follow pipx's installation guide and install Git and ADB using your distribution's package manager.

**Windows (PowerShell):** Install [Python](https://www.python.org/downloads/windows/) with its `py` launcher and [Git](https://git-scm.com/downloads/win). Extract Windows platform-tools and add its directory to PATH, then run:

```powershell
py -m pip install --user pipx
py -m pipx ensurepath
```

Close and reopen your terminal. Check `pipx --version`, `git --version` and `adb version`. Check Python with `python3 --version` on macOS/Linux or `py -3 --version` on Windows. `pipx ensurepath` adds your user command directory to PATH; `sudo pipx` is unnecessary.

### Install and verify

Run from any directory in a macOS/Linux shell or Windows PowerShell:

```bash
pipx install 'git+https://github.com/wangzan101/Mika.git@v0.1.23#subdirectory=tools/mika'
mika --version
mika --help
pipx list
```

The list should show `mika-phone-cli` and its `mika` command. pipx fetches the source; you do not need to keep a Mika checkout. GitHub is the current distribution source. Do not shorten the command to `pipx install mika-phone-cli`: the project is not published on PyPI and has no Mika Homebrew or WinGet package.

Use `pipx environment` to find installation directories. Locate the executable with `command -v mika` on macOS/Linux or `(Get-Command mika).Source` in PowerShell.

## CLI: update, roll back and uninstall

Check the current source tag with `pipx list --json` and choose a target tag from Releases. To switch a pinned source reliably, remove this package with pipx and install the target. This example installs or restores v0.1.23:

```bash
pipx uninstall mika-phone-cli
pipx install 'git+https://github.com/wangzan101/Mika.git@v0.1.23#subdirectory=tools/mika'
mika --version
pipx list --json
```

To update, substitute the newer tag; to roll back, use a previously working tag that still supports API v1. Several APK releases may contain the same CLI version number, so also check the installation source in `pipx list --json`. A pinned tag does not follow the next release, and `pipx upgrade mika-phone-cli` does not change that tag.

For a local source installation, use the same uninstall step followed by `pipx install ./tools/mika` from the target version's repository root, or switch to a release tag using the GitHub URL above. If the new installation fails, install the recorded previous tag to restore the CLI. If you previously installed with pip in a virtual environment, run `python -m pip uninstall mika-phone-cli` in that environment to remove the old copy and avoid PATH selecting it.

Uninstall by **package name**, not command name:

```bash
pipx uninstall mika-phone-cli
pipx list
```

The package should no longer appear. If `mika` still runs, locate the executable as described above and remove that other installation using its original installer. Do not delete the entire pipx directory.

## Skill: install

### Use the skills tool

[skills](https://github.com/vercel-labs/skills) is a separate Agent Skill manager. This method also needs Node.js 22.20+ (including npm/npx) and Git. Running Mika's bundled script still requires Python and ADB as described above, but no globally installed `mika` command.

Choose your agent and install at **user scope** (available across projects):

```bash
# Codex
npx skills add https://github.com/wangzan101/Mika/tree/v0.1.23/tools/mika --skill mika --agent codex --global --copy

# Claude Code (choose this if you only use Claude Code)
npx skills add https://github.com/wangzan101/Mika/tree/v0.1.23/tools/mika --skill mika --agent claude-code --global --copy
```

`--copy` installs a complete copy, so you do not need to keep the download directory. To install for both agents, use `--agent codex claude-code`. For a project installation, run from the project root and omit `--global`; use the same scope when listing, updating or removing it. On first use, npx may ask to download the skills tool.

Installation directories (`~` means your user directory, including on Windows):

| Agent | User scope | Project scope |
| --- | --- | --- |
| Codex | `~/.agents/skills/mika/` | `.agents/skills/mika/` |
| Claude Code | `~/.claude/skills/mika/` | `.claude/skills/mika/` |

Verify installation:

```bash
npx skills list --global --agent codex
python3 ~/.agents/skills/mika/scripts/mika_cli.py --version
```

For Claude Code, replace `codex` with `claude-code` and use `~/.claude/skills/mika/`. For a project installation, omit `--global` and remove the leading `~/` from the script path. In PowerShell, invoke the user-scoped script with:

```powershell
py -3 "$env:USERPROFILE\.agents\skills\mika\scripts\mika_cli.py" --version
```

The installed directory must contain both `SKILL.md` and `scripts/mika_cli.py`; copying only the Markdown file is insufficient. In Codex, select `mika` from the skill picker or invoke `$mika`; in Claude Code, use `/mika`. If it does not appear, start a new agent session and check the selected user/project scope. See the [Codex](https://developers.openai.com/codex/skills/) and [Claude Code](https://code.claude.com/docs/en/skills) documentation for discovery rules.

### Without Node.js: install manually

Download **Source code (zip)** from the chosen release and extract it. Copy the **complete** `tools/mika` folder to the `mika` directory in the table above, or copy it from a Git checkout of that tag. The result should be `…/skills/mika/SKILL.md`, not `…/skills/mika/mika/SKILL.md`. If the destination exists, follow the update procedure below instead of merging folders. Run the same bundled-script check and verify discovery in your agent.

Manual copies have no source version recorded by the installer. Even if skill listings discover them, use folder-based updates and removal for this method.

## Skill: update, roll back and uninstall

**Managed by skills:** Choose a release tag and repeat `skills add` for the same agent and scope. It replaces that `mika` copy. If you edited the installed Skill, back up your changes outside the skill directories first. This example installs or restores v0.1.23 for Codex at user scope:

```bash
npx skills add https://github.com/wangzan101/Mika/tree/v0.1.23/tools/mika --skill mika --agent codex --global --copy
npx skills list --global --agent codex
```

Replace the tag to update or roll back, then recheck the bundled script and agent skill list. Change pinned releases with an explicit `add` URL instead of relying on an update command to infer the next stable release. In automation, add `--yes` to skip installer prompts only when you intend to overwrite that skill.

Remove Mika from the selected scope, including its copies for other agents in that scope:

```bash
npx skills remove mika --global --yes
npx skills list --global
```

For project installations, omit `--global` and run from the original project root. This removes Mika for all agents in that scope; other skills are retained. Use `npx skills list --global` (or `npx skills list` for the project) to confirm Mika is gone. Agent directories may share a canonical copy, so do not treat removal of an agent-specific link as a complete uninstall.

**Manual copies:** To update or roll back, move the old `mika` folder outside the skill directories as a backup, then copy the complete folder from the target tag. To uninstall, delete only the `mika` folder you installed. Do not remove all of `.agents/skills` or `.claude/skills`. Start a new agent session and confirm Mika is gone.

## Connect your phone for the first time

Install Mika v0.1.23 or a newer compatible APK on the phone, **open Mika once** to generate the connection script, and configure its model. The phone must be listed by ADB over USB or paired wireless debugging. The computer CLI does not need a model API key; keep it in Mika on the phone.

```bash
mika devices --json
mika connect --device SERIAL --json
mika doctor --device SERIAL --json
```

Replace `SERIAL` with the device identifier from the first command. With only the Skill installed, substitute `python3 <actual-skill-directory>/scripts/mika_cli.py` for `mika` (`py -3` on Windows). `connect` enables computer access. The CLI manages temporary forwarding automatically; there is no MCP configuration or token copying step. `doctor` checks connectivity, not whether a model task succeeds.

Submit a task only when the user has requested one. A harmless goal can verify the complete flow:

```bash
mika --device SERIAL task run --goal 'Compute 37 + 58. Reply with the result; do not operate apps or change data.' --json
mika --device SERIAL task wait TASK_ID --timeout 45 --json
```

Replace `TASK_ID` with the returned ID. If still running, check it or wait again within a bounded interval. Report completion only after observing `done` and checking the answer. See [task commands](cli.en.md) for questions, failures and cancellation.

Removing the computer CLI/Skill does not uninstall the APK, erase phone data or disable computer access. To stop remote access, turn off the relevant USB/LAN switch under Settings > Computer access on the phone.

## Troubleshooting

| Symptom | Action |
| --- | --- |
| `pipx` or `mika` not found | Run `pipx ensurepath` and reopen the terminal. On Windows, use `py -m pipx ensurepath` if pipx is not on PATH yet. Check `pipx environment` and the actual executable location. |
| `externally-managed-environment` | Install pipx with your system package manager, then install Mika with pipx instead of forcing it into system Python. |
| Git, ADB or Python not found | Install the prerequisites and make them available to the agent's environment too. The CLI accepts an absolute ADB path with `--adb`. |
| Skill is listed but its script fails | Check the bundled script and Python version; use `py -3` on Windows. Installing only `SKILL.md` is insufficient. |
| Old Skill content after updating | Check for both user and project copies or another skill with the same name. Inspect the loaded path and start a new session. |
| `incompatible_api` | Update the phone APK to one with API v1, open Mika and run `connect` again. |
| Connection script missing on the phone | Confirm the APK is installed and has been opened at least once, then run `connect`. |
| Multiple devices, `unauthorized` or `offline` | Select `--device`, approve ADB on the phone or restore its connection. Do not guess another device. |
| GitHub download fails | Download the release's source zip manually, extract it and run `pipx install ./tools/mika` from its root, or install the Skill manually. |
