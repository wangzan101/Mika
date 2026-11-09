# 设计：任务结束后保留被操作的 App（issue #434）

## Context

收尾链路：`TaskRun` → `AppsAfterTaskEnd.finish(shell, displayId, marks)` → AIDL `IMikaShell.finishDisplay`(=48) → 助手 `ShellService` 用 `AppsAfterTask.decide` 定去留：默认保留，`playing==true` 或（`playing==null` 且被静音）关，模型标记优先；挪到主屏后 `recheck` 发现新出声的（非标记保留）挪回随屏关。AIDL 方法按编号演进（最新 =50）。

## Goals / Non-Goals

**Goals:** 用户开关让出声的 App 也保留；标记 close、自家页面、挪不动等既有规则不动；默认零变化。

**Non-Goals:** 改「默认保留不出声 App」的现行规则；按 App 粒度的白名单；接管路径的特殊处理（开关对所有收尾一致）。

## Decisions

1. **语义定为「覆盖出声即关」，不叫 keep-all。**
   issue 的痛点精确对应 `Why.Playing`/`Why.PlaybackUnknown` 两个分支；模型显式标的 close 与系统页面关闭规则不该被用户开关越过（那是任务语义与安全边界）。开关名与 AIDL 名都按 `keepPlaying` 表述，避免误解为「全部保留」。

2. **决策纯函数加参数：`decide(tasks, keep, close, keepPlaying = false)`，新 `Why.UserSetting("user_setting")`。**
   keepPlaying 时 playing/ playbackUnknown 分支判保留并记 user_setting（运行记录可对账）；`recheck` 对 `Why.UserSetting` 与 `Why.Marked` 同样豁免（用户接受了出声保留）。默认参数＝现行为，既有测试不动。

3. **AIDL 加新方法 `finishDisplayKeepPlaying = 51` 而非改 =48 签名。**
   与仓库演进方式一致（编号只增不改）；助手与 App 同 APK 同版本，无跨版本兼容问题。`ShellService` 内部共用一个带 keepPlaying 的实现。

4. **TaskRun 在任务开始读一次设置**（与操作节奏等「下一个任务起生效」的既有做法一致），收尾时经 `AppsAfterTaskEnd.finish` 透传选择 AIDL 方法。

5. **设置开关放「干活时」页**，紧挨「被操作的 App 静音」：两者同属任务期间对被操作 App 的声音处置，副文案写明「包括正在出声的；任务结束后声音会继续」。

## Risks / Trade-offs

- [保留的出声 App 在静音还原后突然响] → 这正是用户选择的行为（接管接着玩），副文案明示；默认关。
- [与我另两个未合并 PR 同文件（干活时页/strings_settings/TaskRun）] → 各自独立成立，后合并者 rebase，常规操作。
- [AIDL 新方法在旧助手进程上不存在] → 助手与 App 同包同版本一起更新，不存在新旧混跑。

## Migration Plan

默认关闭，无行为变化；回滚即还原代码。
