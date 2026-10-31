# 安装、升级和卸载电脑端 CLI / Skill

[English](computer-install.en.md) · [任务命令与 API](cli.md) · [MCP 配置](mcp.md)

## 先选需要的部分

| 你想做什么 | 安装什么 |
| --- | --- |
| 在手机上运行 Mika | 从 [Releases](https://github.com/wangzan101/Mika/releases) 安装 APK,完成[手机设置](setup-guide.md)。 |
| 在终端或脚本里用 `mika` 派任务 | 安装下面的 CLI。 |
| 让 Codex、Claude Code 等 Agent 使用 Mika | 安装下面的 Skill;它自带 CLI 脚本,可以单独使用。 |
| 已有 MCP 客户端 | 按 [MCP 指南](mcp.md)配置,无需再装 CLI 或 Skill。 |

APK、CLI、Skill 分别安装。电脑端 CLI/Skill 从 **Mika v0.1.23** 起提供,要求手机支持 task API v1。本文用 `v0.1.23` 演示固定版本安装;安装或升级前在 Releases 选择要用的正式版本,将命令中的 tag 换成该版本。不要用低于 v0.1.23 的 tag。自编译 APK 以 `connect` 的 API 兼容性检查为准。

CLI 包名是 `mika-phone-cli`,安装后的命令是 `mika`。CLI 自己的版本（v0.1.23 源码里是 `0.1.0`）与 APK 版本分别编号;`mika --version` 不会显示手机版本。是否兼容由 `mika connect` 检查的 API 版本决定。

## CLI：安装

### 准备依赖

CLI 需要 Python 3.10+;新安装建议 Python 3.11+,以满足当前 pipx 的要求。还需要 Git、[pipx](https://pipx.pypa.io/latest/how-to/install-pipx.html)。通过 ADB 连接时需要 [Android platform-tools](https://developer.android.com/tools/releases/platform-tools)。

**macOS（Homebrew）**：

```bash
brew install python pipx git
brew install --cask android-platform-tools
pipx ensurepath
```

**Debian / Ubuntu**：

```bash
sudo apt update
sudo apt install python3 python3-venv pipx git adb
pipx ensurepath
```

其他 Linux 发行版按 pipx 官方指引安装,用发行版包管理器安装 Git 和 ADB。

**Windows（PowerShell）**：先安装 [Python](https://www.python.org/downloads/windows/)（含 `py` 启动器）、[Git](https://git-scm.com/downloads/win),解压 Windows platform-tools 并把其中的目录加入 PATH,然后运行：

```powershell
py -m pip install --user pipx
py -m pipx ensurepath
```

关闭并重新打开终端,确认 `pipx --version`、`git --version`、`adb version` 可用。macOS/Linux 用 `python3 --version` 检查 Python,Windows 用 `py -3 --version`。`pipx ensurepath` 将当前用户的命令目录加入 PATH,无需 `sudo pipx`。

### 安装与检查

下面的命令可在任意目录执行,适用于 macOS/Linux shell 和 Windows PowerShell：

```bash
pipx install 'git+https://github.com/wangzan101/Mika.git@v0.1.23#subdirectory=tools/mika'
mika --version
mika --help
pipx list
```

看到 `mika-phone-cli` 和 `mika` 表示安装成功。源码由 pipx 获取,不需要保留 Mika 仓库检出。当前安装来源是 GitHub,不要省略 URL 改成 `pipx install mika-phone-cli`:项目尚未在 PyPI 发布,也未提供 Homebrew 或 WinGet 的 Mika 包。

查实际安装目录用 `pipx environment`;查命令位置,macOS/Linux 用 `command -v mika`,PowerShell 用 `(Get-Command mika).Source`。

## CLI：升级、回退和卸载

先用 `pipx list --json` 记录当前来源 tag,再在 Releases 选定目标 tag。切换固定来源时,通过同一个 pipx 移除本包再安装目标版本。下面以安装/恢复到 v0.1.23 为例：

```bash
pipx uninstall mika-phone-cli
pipx install 'git+https://github.com/wangzan101/Mika.git@v0.1.23#subdirectory=tools/mika'
mika --version
pipx list --json
```

升级时换成新 tag;回退时换成之前使用过、仍支持 API v1 的 tag。同一个 CLI 版本号可能出现在多个 APK release 中,所以还应在 `pipx list --json` 的安装来源中核对 tag。固定 tag 不会跟随下一个 release,`pipx upgrade mika-phone-cli` 也不会自动改写这个 tag。

如果原先从本地源码安装,同样先卸载,再在目标版本的仓库根目录运行 `pipx install ./tools/mika`;或用上面的 GitHub URL 切换到正式 tag。若新版本安装失败,用记录下的旧 tag 重新安装即可恢复 CLI。原先用虚拟环境里的 pip 安装的,先在那个环境里用 `python -m pip uninstall mika-phone-cli` 移除旧副本,避免 PATH 选中错误版本。

卸载使用**包名**,不是命令名：

```bash
pipx uninstall mika-phone-cli
pipx list
```

列表里应不再有该包。如果 `mika` 仍能运行,用前面的命令位置检查找到另一个环境里的副本,再通过那个安装方式卸载。不要直接删除整个 pipx 目录。

## Skill：安装

### 使用 skills 工具

[skills](https://github.com/vercel-labs/skills) 是独立的 Agent Skill 管理工具。此方式还需要 Node.js 22.20+（含 npm/npx）和 Git;运行 Mika 随附脚本仍需要前面的 Python 和 ADB,不要求安装全局 `mika` 命令。

选择你使用的 Agent,安装到**当前用户**（所有项目可用）：

```bash
# Codex
npx skills add https://github.com/wangzan101/Mika/tree/v0.1.23/tools/mika --skill mika --agent codex --global --copy

# Claude Code（只使用 Claude Code 时选这条）
npx skills add https://github.com/wangzan101/Mika/tree/v0.1.23/tools/mika --skill mika --agent claude-code --global --copy
```

`--copy` 安装完整副本,无需保留下载目录。要同时安装给两者,用 `--agent codex claude-code`。只给当前项目安装时,在项目根目录执行同样的命令但去掉 `--global`;后续查看、升级和卸载也使用同一个范围。首次运行 npx 可能要求确认下载 skills 工具。

目录如下（`~` 在 Windows 上指用户目录）：

| Agent | 当前用户 | 当前项目 |
| --- | --- | --- |
| Codex | `~/.agents/skills/mika/` | `.agents/skills/mika/` |
| Claude Code | `~/.claude/skills/mika/` | `.claude/skills/mika/` |

检查安装：

```bash
npx skills list --global --agent codex
python3 ~/.agents/skills/mika/scripts/mika_cli.py --version
```

Claude Code 将 `codex` 换成 `claude-code`,脚本目录换成 `~/.claude/skills/mika/`。项目安装去掉 `--global`,脚本路径去掉开头的 `~/`。PowerShell 调用用户目录里的脚本时使用：

```powershell
py -3 "$env:USERPROFILE\.agents\skills\mika\scripts\mika_cli.py" --version
```

完整目录中必须同时有 `SKILL.md` 和 `scripts/mika_cli.py`,只复制 Markdown 不够。Codex 可在技能列表选择 `mika` 或用 `$mika`;Claude Code 用 `/mika`。安装后若看不到,重新开启 Agent 会话,并检查是否装到了当前使用的用户/项目范围。目录规则见 [Codex 文档](https://developers.openai.com/codex/skills/)和 [Claude Code 文档](https://code.claude.com/docs/en/skills)。

### 不使用 Node.js：手动安装

从选定 release 的 **Source code (zip)** 下载源码并解压,把其中**整个** `tools/mika` 文件夹复制到上表中的 `mika` 目录。也可以从对应 tag 的 Git 检出复制。检查结果应是 `…/skills/mika/SKILL.md`,不是 `…/skills/mika/mika/SKILL.md`。如果目标已存在,按下一节升级,不要合并复制。然后运行同一个随附脚本并在 Agent 中检查发现情况。

手动副本没有安装器记录的来源版本;即使能被技能列表发现,也应按文件夹管理其升级和卸载。

## Skill：升级、回退和卸载

**skills 管理的安装**：选定 release tag,再次运行相同 Agent、相同范围的 `skills add` 命令。它会替换对应的 `mika` 副本;若自己改过 Skill,先把修改备份到技能目录之外。下面以 Codex 用户范围安装/恢复到 v0.1.23 为例：

```bash
npx skills add https://github.com/wangzan101/Mika/tree/v0.1.23/tools/mika --skill mika --agent codex --global --copy
npx skills list --global --agent codex
```

升级或回退时替换 URL 中的 tag,然后再次检查随附脚本和 Agent 技能列表。固定版本安装用明确的 `add` URL 切换版本,不依赖更新命令去推断“下一个正式版本”。自动化中可加 `--yes` 跳过安装器确认,仅在确认要覆盖同名 Skill 时使用。

从选定范围卸载 Mika（包括该范围内给其他 Agent 安装的 Mika 副本）：

```bash
npx skills remove mika --global --yes
npx skills list --global
```

项目安装去掉 `--global`,并在原项目根目录执行。这会移除此范围内各 Agent 的 Mika,保留其他 Skill。用 `npx skills list --global`（项目范围用 `npx skills list`）确认 Mika 已消失。Agent 目录可能共用一份存储,不要仅删除某个 Agent 的链接就认定已完全卸载。

**手动副本**：升级/回退时将旧 `mika` 文件夹移到技能目录之外备份,再复制目标 tag 的完整文件夹;卸载时只删除当初复制的 `mika` 文件夹。不要删除整个 `.agents/skills` 或 `.claude/skills`。重开 Agent 会话确认 Mika 已移除。

## 首次连接手机

先在手机上安装 v0.1.23 或更新的兼容 APK,**打开 Mika 一次**让它生成连接脚本,完成模型设置。手机应已通过 USB 或已配对的无线调试出现在 ADB 中。电脑端不需要模型 API Key,Key 留在手机 Mika 中。

```bash
mika devices --json
mika connect --device SERIAL --json
mika doctor --device SERIAL --json
```

将 `SERIAL` 换成第一条输出里的设备标识。只装 Skill 时,用 `python3 <实际Skill目录>/scripts/mika_cli.py` 代替 `mika`（Windows 用 `py -3`）。`connect` 打开手机电脑连接;CLI 自动管理临时端口,无需手工写 MCP 配置或复制令牌。`doctor` 只验证连接,不代表模型任务已成功。

只有用户已要求运行任务时才提交。用一个无副作用的目标验证完整流程：

```bash
mika --device SERIAL task run --goal 'Compute 37 + 58. Reply with the result; do not operate apps or change data.' --json
mika --device SERIAL task wait TASK_ID --timeout 45 --json
```

将 `TASK_ID` 换成实际返回的 ID。还在运行就查询/继续有界等待,看到 `done` 并核对答案后才算完成;追问、失败、取消等见[任务命令](cli.md)。

卸载电脑 CLI/Skill 不会卸载手机 APK、删除手机数据或关闭手机电脑连接。需要停止远程访问时,在手机的「设置 › 电脑连接」关闭对应的 USB/局域网开关。

## 常见问题

| 现象 | 处理 |
| --- | --- |
| `pipx` 或 `mika` 找不到 | 运行 `pipx ensurepath` 后重开终端;Windows 的 pipx 不在 PATH 时先用 `py -m pipx ensurepath`。用 `pipx environment` 和命令位置检查确认实际目录。 |
| `externally-managed-environment` | 用系统包管理器安装 pipx,再用 pipx 安装 Mika;不要向系统 Python 强装 Mika。 |
| `git`、`adb` 或 Python 找不到 | 按依赖步骤安装,并确认 Agent 运行环境也能找到它们;有 ADB 绝对路径时可用 CLI 的 `--adb`。 |
| Skill 列得出但无法运行 | 检查随附脚本是否齐全、Python 是否满足版本,Windows 用 `py -3`;不要仅安装 `SKILL.md`。 |
| Skill 更新后还是旧内容 | 检查是否同时存在用户和项目副本、是否选中了另一份同名 Skill;核对实际文件位置并重开会话。 |
| `incompatible_api` | 升级手机 Mika 到支持 API v1 的版本,重新打开 App 再运行 `connect`。 |
| 找不到手机连接脚本 | 确认手机 APK 已安装并至少启动过一次,再运行 `connect`。 |
| 多设备、`unauthorized` 或 `offline` | 选定 `--device`,在手机确认 ADB 授权或恢复连接;不要猜另一台设备。 |
| GitHub 下载失败 | 从 Releases 手动下载对应源码 zip,解压后在根目录 `pipx install ./tools/mika`,或按手动方式安装 Skill。 |
