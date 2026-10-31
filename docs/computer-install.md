# 安装、升级和卸载电脑端 CLI / Skill

[English](computer-install.en.md) · [连接与任务命令](cli.md) · [MCP 配置](mcp.md)

**电脑接入通常二选一：CLI + Skill 或 MCP，无需两套都配置。** 按下表选择适合的方式；手机 APK 是两者的共同前提。需要同时接入不同客户端时，也可以并用。

| 用途 | 安装什么 |
| --- | --- |
| 在手机上运行 Mika | 从 [Releases](https://github.com/wangzan101/Mika/releases) 安装 APK,完成[手机设置](setup-guide.md)。 |
| 在终端或脚本中派任务 | 跳到 [CLI 安装](#cli安装),使用 `mika` 命令。 |
| 让 Agent 通过 Skill 使用 Mika（如 Codex、Claude Code） | 跳到 [Skill 安装](#skill安装);它自带 CLI 脚本。 |
| 使用 MCP 客户端 | 直接按 [MCP 指南](mcp.md)配置,无需执行本文的 CLI / Skill 安装。 |

只执行所选入口的步骤。只装 Skill 不用先装全局 CLI 或 pipx。电脑端工具的安装、升级不会安装或更新手机 APK；手机上的模型和所需功能仍要单独设置。

官方 APK 从 **v0.1.23** 提供 task API v1;自编译包以 `connect` 的兼容性检查为准。下面以 `v0.1.23` 演示固定版本安装,可替换为 Releases 中更新的正式 tag。

## CLI：安装

CLI 需要 Python 3.10+、Git 和 [pipx](https://pipx.pypa.io/latest/how-to/install-pipx.html);新环境建议 Python 3.11+。ADB 连接还需要 [Android platform-tools](https://developer.android.com/tools/releases/platform-tools)。

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

其他 Linux 发行版按 pipx 官方指引和发行版包管理器安装依赖。

**Windows（PowerShell）**：先安装 [Python](https://www.python.org/downloads/windows/)（含 `py` 启动器）、[Git](https://git-scm.com/downloads/win),解压 Windows platform-tools 并将其目录加入 PATH,然后运行：

```powershell
py -m pip install --user pipx
py -m pipx ensurepath
```

重开终端,检查 `pipx --version`、`git --version`、`adb version`。Python 检查用 `python3 --version`,Windows 用 `py -3 --version`。

依赖就绪后,在任意目录安装并验证（同样适用于 PowerShell）：

```bash
pipx install 'git+https://github.com/wangzan101/Mika.git@v0.1.23#subdirectory=tools/mika'
mika --version
mika --help
pipx list
```

列表应显示包 `mika-phone-cli` 和命令 `mika`。pipx 从 GitHub 获取源码,无需保留仓库检出;当前没有发布同名 PyPI 包。CLI 自身版本（此 tag 为 `0.1.0`）与 APK 分别编号,不代表手机版本。

## CLI：升级、回退和卸载

先用 `pipx list --json` 记录当前来源 tag,再从 Releases 选择目标版本。切换固定来源时,用 pipx 卸载后重新安装;下面的 tag 换成目标版本：

```bash
pipx uninstall mika-phone-cli
pipx install 'git+https://github.com/wangzan101/Mika.git@v0.1.23#subdirectory=tools/mika'
mika --version
pipx list --json
```

升级用新 tag,回退用原 tag（均须支持 API v1）。在安装来源中核对 tag;CLI 版本号不一定随 APK release 改变。`pipx upgrade mika-phone-cli` 不会自动切换到下一个 tag。若安装失败,可用记录下的原 tag 恢复。

本地源码安装使用相同步骤,将安装命令换成仓库根目录下的 `pipx install ./tools/mika`。

只卸载时,使用包名并确认列表中已移除：

```bash
pipx uninstall mika-phone-cli
pipx list
```

## Skill：安装

运行 Skill 自带脚本需要 Python 3.10+,ADB 连接还需要 platform-tools。可用安装器管理,也可手动复制;两种方式都不要求安装全局 `mika`。

### 使用 skills 安装器

[skills 工具](https://github.com/vercel-labs/skills)另需 Node.js 22.20+（含 npm/npx）和 Git。以下安装到 Codex 的**用户范围**,所有项目可用：

```bash
npx skills add https://github.com/wangzan101/Mika/tree/v0.1.23/tools/mika --skill mika --agent codex --global --copy
npx skills list --global --agent codex
```

Claude Code 将 `--agent codex` 换成 `--agent claude-code`;两者都装可用 `--agent codex claude-code`。`--copy` 安装完整副本,无需保留下载目录。只给当前项目安装时,在项目根目录执行并去掉 `--global`;查看、升级和卸载也使用同一范围。

| Agent | 用户范围 | 项目范围 |
| --- | --- | --- |
| Codex | `~/.agents/skills/mika/` | `.agents/skills/mika/` |
| Claude Code | `~/.claude/skills/mika/` | `.claude/skills/mika/` |

验证目录中有 `SKILL.md` 和 `scripts/mika_cli.py`,并运行随附脚本。以 Codex 用户范围为例：

```bash
python3 ~/.agents/skills/mika/scripts/mika_cli.py --version
```

Windows PowerShell 使用：

```powershell
py -3 "$env:USERPROFILE\.agents\skills\mika\scripts\mika_cli.py" --version
```

Claude Code 使用 `.claude` 路径;项目范围去掉开头的 `~/`（Windows 使用当前项目路径）。然后在 Codex 技能列表选择 `mika` 或用 `$mika`,在 Claude Code 用 `/mika`。看不到时重开会话,检查安装范围。目录规则见 [Codex](https://developers.openai.com/codex/skills/)和 [Claude Code](https://code.claude.com/docs/en/skills) 官方文档。

### 手动安装（无需 Node.js）

从选定 release 下载 **Source code (zip)** 并解压,将整个 `tools/mika` 文件夹复制到上表的目标目录。结果应为 `…/skills/mika/SKILL.md`,而不是多嵌套一层 `mika`。目标已有副本时先按下一节备份替换,不要合并复制。然后按上面的脚本和 Agent 发现步骤验证。

## Skill：升级、回退和卸载

Skill 和全局 CLI 分别维护。电脑已有 `mika` 命令时，当前 Skill 会优先使用它；更新 Skill 不会升级全局 CLI。验证更新时要核对 Agent 实际调用的命令和来源，不能只看随附脚本的版本。

**安装器管理的副本**：重复安装时的 `skills add` 命令,把 URL 中的 tag 换成目标版本,保持相同 Agent 和范围。升级用新 tag,回退用原 tag;随后重新执行 `skills list` 和脚本检查。若自己修改过 Skill,先把修改备份到技能目录之外。自动化可加 `--yes` 确认覆盖同名 Skill。

完整卸载使用 Skill 名称和安装范围：

```bash
npx skills remove mika --global --yes
npx skills list --global
```

项目范围去掉 `--global`,在原项目根目录执行。这会移除此范围内各 Agent 的 Mika 副本,保留其他 Skill;列表中应不再有 Mika。

**手动副本**：升级或回退时将旧 `mika` 文件夹移到技能目录之外备份,再复制目标 tag 的完整文件夹;卸载时只删除该 `mika` 文件夹。重开 Agent 会话确认结果。手动副本没有安装器记录的来源版本,用文件夹管理其更新和卸载。

## 安装后的连接检查

本节用于 CLI / Skill；MCP 用户按 [MCP 指南](mcp.md)检查。使用 USB 或已连接的无线 ADB 时，在手机上打开 Mika 一次，让它生成连接脚本，再按[连接手机](cli.md#连接手机)执行 `devices`、`connect`、`doctor`；只装 Skill 时用其随附脚本替代 `mika`。

直接经 Wi-Fi／私有网络连接时，按同一连接指南里的 URL 和令牌文件方式检查，无需执行 ADB 的 `devices` / `connect` 流程。`doctor` 只验证接口连接和可读设备状态，不代表 Agent 已加载 Skill、模型已配置或真实任务成功；任务执行另见[任务命令](cli.md#任务命令)。

卸载电脑端工具不会删除手机数据或关闭手机电脑连接。需要停止远程访问时,在手机「设置 › 电脑连接」关闭对应开关。

## 常见问题

| 现象 | 处理 |
| --- | --- |
| 找不到 `pipx` 或 `mika` | `pipx ensurepath` 后重开终端;Windows 可先用 `py -m pipx ensurepath`。用 `pipx environment` 查安装目录。 |
| 卸载后仍能运行 `mika` | 用 `command -v mika`（PowerShell 用 `(Get-Command mika).Source`）找到其他副本,通过原安装方式卸载;虚拟环境中的 pip 安装用该环境的 `python -m pip uninstall mika-phone-cli`。 |
| `externally-managed-environment` | 用系统包管理器安装 pipx,再用 pipx 安装 Mika,不要向系统 Python 强装。 |
| Skill 不可见或仍是旧版本 | 检查用户/项目范围、实际加载路径和同名副本;确认完整复制了脚本,然后重开会话。 |
| ADB、连接脚本或 API 不可用 | 确认 ADB 已在 PATH、APK 已打开过、手机支持 API v1;按[连接指南](cli.md#连接手机)选定设备并授权。 |
| GitHub 下载失败 | 手动下载 release 源码 zip,在解压根目录 `pipx install ./tools/mika`,或手动安装 Skill。 |
