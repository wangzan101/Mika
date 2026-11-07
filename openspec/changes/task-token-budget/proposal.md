# 每个任务的 token 预算上限（issue #158 剩余部分）

## Why

一个难以收尾的长任务（如「玩合成西瓜直到成功」）可以把服务商额度一次性耗光，中途没有任何停止点。步数上限（#193，v0.1.34 已发布）只按轮数截停，而截图密集型任务单轮就可能消耗数万 token；#158 明确提出还需要「每个任务的 token 上限」，维护者已在 issue 里确认该部分仍未实现、保持开放跟踪。

## What Changes

- 设置 › 干活时新增「每个任务的 token 预算」：不限制（默认）/ 50 万 / 100 万 / 500 万 / 1000 万 / 自定义 1 万–1 亿，下一个任务起生效；条件检查（readOnly）任务不设预算，与步数上限的既有豁免一致。
- 运行中每一步调模型前，按 [RunUsageTally] 累计的 input+output+cachedInput token 检查预算；达到预算即停止调度新的模型调用，走与步数上限相同的收尾轮（只剩 end_not_done，交代已确认的部分结果、查过什么、缺什么），结局为新的 `FailKind.TokenLimit`。
- 运行时间线（任务详情页）显示「token 已用 / 预算」，与步数「已用 / 上限」并列。
- 子任务（只读调研）与旁路调用（意图判断、看图、压缩等）的消耗经同一份 [RunUsageTally] 计入任务预算；子循环不单独设预算。
- 服务商不报用量的调用（usage 为 null）照旧不估不计——预算只能约束自报用量的部分，这一局限写进设置项说明。

不做（另行跟踪）：#158 第 3 点「每个服务商每天的 token 上限」；到限时不 ask_user（与 #193 落地的硬收尾语义一致，定时任务无人应答）。

## Capabilities

### New Capabilities
- `task-token-budget`: 每任务 token 预算的设置、运行中检查与到限收尾、时间线展示。

### Modified Capabilities

（无既有 specs；步数上限行为不变。）

## Impact

- `:core`
  - `run/RunContracts.kt`：`RunSpec.tokenBudget: Long?`
  - `agent/AgentLoop.kt`：每步开头的预算检查 → `finalTurn(Closeout.TokenLimit)`；`RunEvent.TaskStarted` 带 `tokenBudget`
  - `agent/TurnPolicy.kt`、`agent/StopRules.kt`、`agent/TaskState.kt`：`Closeout.TokenLimit`、`FailKind.TokenLimit` 及配套文案
  - `usage/RunUsageTally.kt`：`total()`（input+output+cached）
  - `resources/prompts/feedback/loop.md`：收尾轮提示词条
  - 与 `FailKind.StepLimit` 并列的既有分支补齐：`skills/SkillOutcomes`、`knowledge/AppKnowledgeLearning`、`eval/Scoring`、`record/RunEvent`（TaskStarted 新字段，旧记录缺省 null）
- `:app`
  - `settings/FeatureFlags.kt`：`taskTokenBudget()` / `setTaskTokenBudget()`
  - `task/TaskRun.kt`：读设置 → `RunSpec`
  - `task/RemoteTaskRecord.kt`：`token_limit` 结局编码
  - `ui/SettingsSubPages.kt`、`ui/SettingsScreen.kt`：设置行 + 预算对话框
  - `ui/RunTimelineViewModel.kt`、`ui/RunTimelineScreen.kt`：预算展示
  - `res/values*/strings_settings.xml`（10 语言）、timeline 字符串
- 单测：`:core`（AgentLoop 预算到限、RunUsageTally.total、StopRules/TurnPolicy、RunEvent 兼容）与 `:app`（FeatureFlags、RemoteTaskRecord）。
