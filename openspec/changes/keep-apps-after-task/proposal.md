# 任务结束后保留被操作的 App（含出声的）（issue #434）

## Why

用户让 Mika 代玩主线，中途要亲手接管游戏：只有终止任务才能交出操控权，而收尾时游戏正在出声（BGM/音效），按现行规则「正在出声的随屏关掉」被误伤关闭，重新打开要从头加载。游戏类任务几乎总有声音，这个默认对「接管后接着玩」的场景不友好（issue #434）。

现状：任务收尾时屏上 App 默认保留（挪到主屏后面），仅「正在出声 / 读不到出声状态且被静音 / 模型标了 close / Mika 自己的页面 / 挪不动」才关。缺一个用户级的「出声的也保留」选项。

## What Changes

- 设置 › 干活时 新增开关「任务结束后保留被操作的 App」（默认关，行为与现在完全一致）：打开后收尾时正在出声的 App 也保留（挪到主屏后面），不再因为出声被关；静音还原后它们可能有声音——这正是用户要的（接管接着玩），副文案写明。
- 不改变的：模型按用户话标过 close 的照关；Mika 自己的页面、非普通 App 页面照关；系统挪不动时照旧随屏关。
- 运行记录的 apps_after_task 事件带上新原因（user_setting），方便对账。

## Capabilities

### New Capabilities
- `keep-apps-after-task`: 任务收尾时被操作 App 去留的用户级开关（覆盖「出声即关」默认）。

### Modified Capabilities

（无既有 specs。）

## Impact

- `:app`
  - `cap/phone/shell/AppsAfterTask.kt`：`decide`/`recheck` 增加 keepPlaying 语义 + 新 `Why.UserSetting`
  - `aidl IMikaShell`：新方法 `finishDisplayKeepPlaying = 51`（同 finishDisplay，出声不关）
  - `cap/phone/shell/ShellService.kt`：共用实现，按 keepPlaying 决策与复查
  - `task/AppsAfterTaskEnd.kt`：透传开关，调用新方法
  - `task/TaskRun.kt`：任务开始读一次设置（下一任务起生效）
  - `settings/FeatureFlags.kt`：`keepAppsAfterTask()` 默认 false
  - `ui/SettingsSubPages.kt` 干活时页开关行 + `strings_settings` ×10
- 单测：`AppsAfterTaskTest` 补 keepPlaying 三态（出声保留/静音未知保留/标 close 仍关/自家页面仍关 + recheck 豁免）
