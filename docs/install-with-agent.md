# 用电脑上的 AI 助手安装 Mika

简体中文 | [English](install-with-agent.en.md)

这份文档是写给**电脑上能执行命令的 AI 助手**看的：手机用 USB 连着电脑，助手照着下面的步骤，用 adb 把 Mika 装到手机上并做好设置。人也可以照着做，每条命令都能直接复制。

如果你是用户：把手机用数据线连上电脑，在手机上打开 USB 调试，然后把这句话发给电脑上的 AI 助手：

```
请按 https://github.com/wangzan101/Mika/blob/main/docs/install-with-agent.md 的步骤，帮我把 Mika 安装到通过 USB 连着的安卓手机上。
```

如果你是 AI 助手：先读完「规则」，再从第 1 步开始，一步一步做。

## 规则

1. **只安装和设置 Mika。** 只做本文写到的事，不顺手做别的。
2. **不碰无关的系统设置。** 不改锁屏、屏幕超时、显示、声音、网络、开发者选项里的其他开关等任何本文没写到的设置。
3. **不卸载任何 App，不清除任何数据。** 连 Mika 自己也不行。需要卸载时，说明原因，让用户自己决定、自己动手。
4. **不碰用户的模型 API Key。** 不向用户索要，不替用户输入，不通过 adb 发送。Key 由用户在 Mika 里自己填。
5. **需要在手机上点的时候，停下来告诉用户。** 说清楚屏幕上会出现什么、该点哪个按钮，等用户说好了再继续。
6. **每一步先用用户的语言简短说明要做什么、为什么。** 命令失败时，把错误原文给用户看，再说可能的原因。
7. 下面的命令按类 Unix 的 shell 写（macOS、Linux、Windows 上的 WSL 或 Git Bash）。在 Windows 的 PowerShell 里，按同样的意思改写即可。

下文把包名写作 `com.wangzan101.mika`。

## 第 1 步：检查前提条件

### 1.1 adb

```
adb version
```

成功：输出 `Android Debug Bridge version ...`。

找不到 adb 时，告诉用户去安卓开发者网站下载「SDK Platform-Tools」（有 Windows、macOS、Linux 三个版本），解压后把目录加进 PATH；也可以用系统自带的包管理器安装 platform-tools。装好后重新检查。

### 1.2 手机已连接、已授权

```
adb devices
```

成功：列表里**正好一台**设备，状态是 `device`。

- 状态是 `unauthorized`：请用户看手机屏幕，在「允许 USB 调试吗？」的弹框里点「允许」（可以勾上「一律允许这台计算机」），然后重新运行 `adb devices`。
- 列表是空的：请用户确认数据线能传数据（有的线只能充电），手机上已经打开「开发者选项」和「USB 调试」，并把 USB 用途选成「文件传输」再试。打开 USB 调试的方法见[手机操作设置指南](setup-guide.md)的第 1、2 步。
- 有多台设备：请用户只留要装 Mika 的那一台，或者问清是哪一台，之后每条命令都写成 `adb -s <序列号> ...`。

### 1.3 安卓版本

```
adb shell getprop ro.build.version.sdk
```

- 小于 30（安卓 11 以下）：装不上。告诉用户这台手机的系统版本太旧，停止。
- 30 到 32（安卓 11、12）：能装，但不能操作 App（后台屏要安卓 13）。只能用上网、文件和聊天。告诉用户这一点，问是否继续。
- 33 及以上（安卓 13+）：可以执行任务。34 及以上（安卓 14+）最好。

### 1.4 处理器架构

```
adb shell getprop ro.product.cpu.abi
```

- `arm64-v8a`：Linux 沙箱可用。
- 其他值：Mika 照常能装能用，只是没有 Linux 沙箱（设置里会显示「此设备不支持」）。告诉用户一声即可，继续。

## 第 2 步：下载最新的 APK

从 GitHub 接口取最新版本，选名字形如 `Mika-v*.apk` 的那个文件，用它的 `browser_download_url` 下载：

```
URL=$(curl -fsSL https://api.github.com/repos/wangzan101/Mika/releases/latest \
  | grep -o '"browser_download_url": *"[^"]*/Mika-v[^"]*\.apk"' \
  | sed 's/.*"\(https[^"]*\)"/\1/' | head -n 1)
echo "$URL"
curl -fL -o Mika.apk "$URL"
```

成功：`$URL` 形如 `https://github.com/wangzan101/Mika/releases/download/v0.1.N/Mika-v0.1.N.apk`，`curl` 没有报错，当前目录下有下载完的 `Mika.apk`。

电脑上有 jq 的话，用它解析 JSON 更稳；做法一样，取 `assets` 里名字匹配 `Mika-v*.apk` 的那一项。

连不上 GitHub：请用户在浏览器里打开 [Releases](https://github.com/wangzan101/Mika/releases/latest)，手动下载 APK，告诉你文件存在哪，再从第 3 步继续。

## 第 3 步：安装

```
adb install -r Mika.apk
```

成功：最后一行是 `Success`。

- 有的手机通过 USB 安装时，会在手机上弹出确认框。请用户看着手机，出现时点「允许」或「安装」。命令会等到用户点完。
- 失败时把错误原文给用户看。常见的：
  - `INSTALL_FAILED_UPDATE_INCOMPATIBLE`：手机上已经装了一份签名不同的 Mika（比如自己编译的版本）。要装新的只能先卸载旧的，**旧的里面的对话和设置会丢**。说明这一点，问用户要不要自己去卸载；不要替用户卸载。用户卸载后再运行安装命令。
  - `INSTALL_FAILED_VERSION_DOWNGRADE`：手机上的 Mika 比要装的更新，不用装了，直接到第 4 步。
  - `INSTALL_FAILED_USER_RESTRICTED` 或 `INSTALL_FAILED_ABORTED`：用户在手机上拒绝了，或者系统禁止通过 USB 安装。请用户在开发者选项里找「通过 USB 安装」之类的开关并打开（部分手机才有），然后重试。
  - `INSTALL_FAILED_INSUFFICIENT_STORAGE`：手机存储不够，请用户清理一些空间。

## 第 4 步：通知权限（安卓 13 及以上）

Mika 用通知告诉用户任务进展、请用户接管。SDK 小于 33 时跳过这一步。

```
adb shell pm grant com.wangzan101.mika android.permission.POST_NOTIFICATIONS
```

成功：没有输出。

## 第 5 步：打开 Mika 的无障碍服务

Mika 靠无障碍服务读界面、做点按。通过 adb 直接写系统设置来打开它，原因是：不从应用商店安装的 App，用户在系统设置里手动打开无障碍时，常被系统以「受限制的设置」拦下；通过 adb 写入不会遇到这个问题。

Mika 的无障碍服务组件名（来自 `app/src/main/AndroidManifest.xml` 里的 `MikaAccessibilityService`）：

```
com.wangzan101.mika/.a11y.MikaAccessibilityService
```

它和完整写法 `com.wangzan101.mika/com.wangzan101.mika.a11y.MikaAccessibilityService` 是同一个；判断列表里有没有 Mika 时，两种写法都要认。

**不要覆盖用户已经开着的其他无障碍服务。** 先读出现有列表，没有 Mika 才在末尾加上（用冒号分隔），再写回去：

```
ME=com.wangzan101.mika/.a11y.MikaAccessibilityService
CUR=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
echo "现有：$CUR"
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

成功：最后一行输出里有 Mika 的组件，原来的那些服务也都还在。

说明：`settings get` 对从没写过的项输出 `null`，要当成空列表；写回时外面加单引号，是因为有的服务名里带 `$` 符号。

## 第 6 步：电池白名单

把 Mika 加进系统的省电白名单，免得后台任务、定时任务被系统中途掐掉：

```
adb shell cmd deviceidle whitelist +com.wangzan101.mika
```

成功：输出 `Added: com.wangzan101.mika`（已经在里面时也一样）。
如果这台手机不认 `cmd`，改用 `adb shell dumpsys deviceidle whitelist +com.wangzan101.mika`，效果相同。

检查：

```
adb shell cmd deviceidle whitelist | grep com.wangzan101.mika
```

## 第 7 步：启动 Mika 的手机助手

Mika 有一个内置的手机助手，在后台屏上启动和操作 App。它要借安卓自带的调试功能才能启动。有两种方式，请让用户选：

- **A. 配对无线调试（推荐，一劳永逸）**：配对一次，以后重启手机也能自己连上，不需要电脑。
- **B. 用 USB 启动**：现在插着线就能启动，不用配对；但每次重启手机后，都要再连电脑运行一次（除非也做了 A）。

两种可以都做：先用 B 马上用上，再做 A 一劳永逸。

先打开一次 Mika（两种方式都需要）：

```
adb shell am start -n com.wangzan101.mika/.ui.MainActivity
```

也可以用 `adb shell monkey -p com.wangzan101.mika 1`。第一次打开时 Mika 会引导用户添加模型；可以先让用户点「稍后再说」之类的按钮跳过，模型放到第 8 步。

### A. 配对无线调试

1. 打开无线调试：

   ```
   adb shell settings put global adb_wifi_enabled 1
   adb shell settings get global adb_wifi_enabled
   ```

   成功：第二条输出 `1`。手机要连着 Wi-Fi（不用能上网）。如果手机弹出「要允许在此网络上使用无线调试吗？」，请用户点「允许」，并勾上「始终允许在此网络上使用」。如果读回来又变成 `0`，多半是没连 Wi-Fi，或者用户没在弹框里允许。

2. 告诉用户：在 Mika 里打开 设置 › 手机操作，进入「设置手机操作」向导（第一次打开 Mika 时，设置模型之后也会进入它；向导没出现时，点设置页顶上状态卡片里的提示，或者点 设置 › 手机操作 › 重新配对）。向导会自己检查开发者选项、USB 调试和无线调试，已经打开的会自动打勾。

3. 配对要用户在手机上亲手完成：在系统的「无线调试」页里点「使用配对码配对设备」，把屏幕上的 6 位配对码填进 Mika 的通知里（或者让 Mika 自己读）。你没法替用户配对，等用户说配好了再继续。

4. 每一步怎么点、卡住了怎么办，请用户看[手机操作设置指南](setup-guide.md)。

### B. 用 USB 启动

前提：Mika 至少打开过一次，它才会把启动脚本写到手机上。插着线时运行：

```
adb shell sh /sdcard/Android/data/com.wangzan101.mika/files/usb-start.sh
```

- 提示文件不存在：先用上面的命令打开一次 Mika，等几秒再试。还是没有的话，手机上的 Mika 版本可能还不带这个功能，改用 A。
- 成功之后可以拔掉数据线，助手会继续运行。
- 重启手机后助手会停，要再连电脑运行一次这条命令；做过 A 的话就不需要了。

### 确认已经启动

请用户打开 Mika 的设置，看顶上的状态卡片：「手机操作」显示可用（模型也配好时显示「模型已配置 · 手机操作可用」）。如果显示「手机操作还没准备好」，按上面的步骤重做，或者看下面的「常见问题」。

## 第 8 步：添加模型

告诉用户：在 Mika 里打开 设置 › 模型，添加一个服务商并填入自己的 API Key，点「测试并保存」；或者在添加服务商那里点「使用 ChatGPT 继续」，用 ChatGPT 账号登录（需要 Plus 或 Pro）。

**这一步完全由用户在手机上操作。** 不要索要 Key，不要通过 adb 输入或发送 Key。

## 第 9 步：检查结果

```
adb shell dumpsys package com.wangzan101.mika | grep versionName
adb shell settings get secure enabled_accessibility_services
```

成功：第一条显示刚装的版本号；第二条的输出里有 Mika 的组件。

然后建议用户在 Mika 里发一个不会产生影响的任务试试，比如：

```
打开设置，告诉我安卓版本是多少
```

## 常见问题

| 现象 | 怎么办 |
|---|---|
| `adb devices` 里没有手机 | 换一根能传数据的线；手机上确认开了「USB 调试」；把 USB 用途选成「文件传输」；拔插一次。 |
| 状态是 `unauthorized` | 请用户在手机上的「允许 USB 调试吗？」弹框里点「允许」。没看到弹框：拔插一次，或者在开发者选项里点「撤销 USB 调试授权」后再插上。 |
| 安装被拦下 | 请用户看手机上有没有确认框；在开发者选项里打开「通过 USB 安装」之类的开关（如果有）。签名不同的报错见第 3 步，卸载由用户决定。 |
| 无障碍过一会儿又关了 | 再做一次第 5 步。配对过无线调试的话，Mika 会自己把它重新打开（设置 › 恢复与修复 里默认开着）。 |
| 重启后手机助手没在运行 | 做过 A：连上 Wi-Fi 等一会儿，Mika 会自己恢复；不行就在 设置 › 手机操作 里点「重新配对」。只做过 B：再连电脑运行一次第 7 步 B 的命令，或者改做 A。 |
| 连不上 GitHub | 请用户在浏览器里打开 [Releases](https://github.com/wangzan101/Mika/releases/latest) 手动下载 APK，然后 `adb install -r <APK 文件路径>`。 |
| 无线调试打不开 | 手机要连着 Wi-Fi；在弹框里允许在此网络上使用无线调试。实在不行就用 B。 |

## 最后：给用户一份小结

全部做完后，用用户的语言告诉用户：

- **已经做好的**：装了哪个版本；通知权限、无障碍、电池白名单是否设好；手机助手用哪种方式启动、是否已经可用。哪一步失败或跳过了，也照实说。
- **用户还要做的**：在 设置 › 模型 里添加模型（如果还没做）；配对无线调试（如果选了 A 但还没配完）；只用了 B 的话，提醒重启手机后要再连电脑运行一次那条命令；最后发一个简单的任务试试。
