## 1. 决策与协议

- [x] 1.1 `AppsAfterTask.decide/recheck` 加 keepPlaying + `Why.UserSetting`；`AppsAfterTaskTest` 补用例（出声保留、静音未知保留、标 close 仍关、自家仍关、recheck 豁免）
- [x] 1.2 AIDL `finishDisplayKeepPlaying = 51`；`ShellService` 共用实现按 keepPlaying 决策与复查
- [x] 1.3 `AppsAfterTaskEnd.finish` 透传开关选方法；`TaskRun` 开工读一次设置

## 2. 设置与文案

- [x] 2.1 `FeatureFlags.keepAppsAfterTask()` 默认 false + setter；干活时页开关行（挨着静音）+ 字符串 ×10

## 3. 验证

- [x] 3.1 `:core:test :core:cliTest :app:testDebugUnitTest` + i18n + updateReadme 全绿
- [x] 3.2 真机对照：同一出声 App 任务两次（默认＝被关 / 开关开＝保留在主屏），apps_after_task 事件对账；取证 `evidence/`
- [ ] 3.3 提交、推 fork、建 PR 关联 #434
