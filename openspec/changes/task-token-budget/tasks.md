## 1. core：预算数据面

- [x] 1.1 `RunUsageTally.total()`：input+output+cached 合计，带单测
- [x] 1.2 `RunSpec.tokenBudget: Long?`；`RunEvent.TaskStarted.tokenBudget: Long? = null`（AgentLoop 发开始事件时带上），旧记录兼容单测
- [x] 1.3 `FailKind.TokenLimit`；与 StepLimit 并列的既有分支补齐（`SkillOutcomes`、`AppKnowledgeLearning`、`Scoring`、`RemoteTaskRecord` → `"token_limit"`、`TaskState` 注释）

## 2. core：到限检查与收尾

- [x] 2.1 `Closeout.TokenLimit` + `TurnPolicy.closeout` 分支 + `loop.md` 新提示词条 `token_limit_closeout`、`token_limit_source`
- [x] 2.2 `StopRules.tokenLimit(budget)`、`closeoutFailed` / `afterFinalTurn` 带 Closeout 区分；LoopPoliciesTest 补断言
- [x] 2.3 `AgentLoop`：run() 每步开头读协程上下文里的 `RunUsageTally`，`total() >= tokenBudget` 时 `finalTurn(Closeout.TokenLimit)`
- [x] 2.4 AgentLoopTest：假模型带 usage——到限停（结局 FailKind.TokenLimit、说明含预算值）、预算内不受影响、usage=null 不计入、子任务消耗计入

## 3. app：设置与展示

- [x] 3.1 `FeatureFlags.taskTokenBudget()/setTaskTokenBudget()`（prefs Long，0=不限，1 万–1 亿），单测
- [x] 3.2 `TaskRun`：读设置填 `RunSpec.tokenBudget`（conditionCheck 为 null，同 stepLimit）
- [x] 3.3 `DuringWorkScreen` 加「每个任务的 token 预算」行 + 预算对话框（不限/50万/100万/500万/1000万/自定义），`SettingsScreen` 接线
- [x] 3.4 字符串资源：`values` + 9 个翻译目录（设置行、对话框、timeline 行）
- [x] 3.5 `RunTimelineViewModel`/`RunTimelineScreen`：header 带 tokenBudget 与已用合计，显示「token 已用 / 预算」，null 不显示

## 4. 验证

- [x] 4.1 `./gradlew :core:test :core:cliTest :app:testDebugUnitTest` 全绿
- [ ] 4.2 真机验证：设小预算跑一个任务到限收尾、改回不限正常跑、时间线显示两行；截图与时间线取证到 `evidence/`
- [ ] 4.3 `updateReadme`（若 README generated 段涉及）＋提交 PR 关联 #158
