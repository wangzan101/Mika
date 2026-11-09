# keep-apps-after-task 的验证取证（issue #434）

分支 `feat/434-keep-apps-after-task`（基于 main `8d61573e`）。

## 单元测试（全绿）

- `:core:test :core:cliTest :app:testDebugUnitTest` 通过；i18n（2183 资源 ×10 语言）、
  `updateReadme` 通过。
- `AppsAfterTaskTest` 新增：
  - `keepPlayingKeepsLoudAppsAsUserAsked`：开关开时出声的（playing=true）与读不到出声状态
    又被静着的（playing=null+muted）都判保留（Why.UserSetting）；模型标 close 的照样关
    （Why.Marked）、自家页面不受影响（Why.Own）。
  - `keepPlayingSparesLoudAppsFromTheRecheck`：user_setting 保留的 App 挪到主屏后开始出声
    不挪回关掉；默认口径（未标记又出声）仍挪回关掉。

## 真机对照验证（华为 Mate X2，2026-11-09）

- 包：本分支 `assembleDebug`（为读运行记录镜像；签名与 perf 相同，覆盖安装不留痕），
  手机助手经 `usb-start.sh` 启动，任务是同一句话：打开抖音看首页、说出第一个视频的文案
  （抖音首页自动播放视频，任务全程有音频播放——`dumpsys audio` 有它的
  requestAudioFocus/PLAYING_APP_NAME 记录）。

| 轮 | 设置 | 运行 | 收尾 apps_after_task 事件（events.jsonl 原文） | 抖音去向 |
| --- | --- | --- | --- | --- |
| A | 关（默认） | `20261109-102601-1gqv2w`（6 步成功） | `kept -; closed com.ss.android.ugc.aweme:playing` | 随屏关闭（现状） |
| B | 开 | `20261109-103105-yce4a7`（succeeded） | `kept com.ss.android.ugc.aweme; closed -` | 挪到主屏后面保留（recents visible=false、进程存活 pid 28547） |

- 截图：01-toggle-off.png（开关行，默认关）、02-toggle-on.png（打开后）、
  03-after-keep.png（B 轮结束后，Mika 在前、抖音保留在后台）。
- 额外发现：perf 包上先跑过一次同任务，抖音被保留——读 debug 包记录前以为是行为不一致，
  实为模型在该轮主动标了 `phone_app_after_task` keep（Why.Marked 优先于出声规则），属设计内
  行为；对照实验因此改用「模型未标记」的轮次读事件原文，结论以上表为准。
- 验证后已把开关恢复为默认关，装回非 debug 的 perf 包，手机不留状态变更。

## 已知边界

- kept 的键在 events.jsonl 里不带原因字样（closed 才带「键:原因」），`user_setting` 原因
  在 kept 场景只体现在行为差异上；如需要可在以后把 kept 也带上原因。
