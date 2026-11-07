# 设计：每个任务的 token 预算上限

## Context

步数上限（#193）的链路是现成的模板：`FeatureFlags.taskStepLimit()` → `TaskRun` 组装 `RunSpec(stepBudget)` → `AgentLoop(maxSteps)`，步数用完走 `finalTurn(Closeout.StepLimit)` 收尾，结局 `FailKind.StepLimit`，时间线按 `RunEvent.TaskStarted.stepLimit` 展示「已用 / 上限」。

token 侧的记账也已经存在：`TaskRun.execute` 里 `withContext(tally) { agent.run(...) }`，`RunUsageTally` 作为协程上下文元素由 `MeteredModel` 在每次模型调用（含旁路、重试）后累加 input/output/cached token——主循环内部随时可读，无需新增传参管道。

## Goals / Non-Goals

**Goals:**
- 每任务 token 预算可设置（默认不限），到限即停，收尾语义与步数上限一致。
- 预算覆盖一次任务内全部模型消耗（主循环、子任务、旁路调用、失败重试）。
- 时间线展示「token 已用 / 预算」。

**Non-Goals:**
- 每服务商每日预算（#158 第 3 点，涉及跨任务账本查询，另做）。
- 到限时 ask_user 问「继续吗」（与 #193 硬收尾一致；定时任务无人应答）。
- 对不报用量的服务商做 token 估算（记账原则一直是「不估 token」）。
- 预算内提前警告（进度自查提示词已有步数版，token 版不做）。

## Decisions

1. **预算值经 `RunSpec.tokenBudget: Long?` 传递，检查在 AgentLoop 循环内、每步调模型之前。**
   备选：构造参数。`RunSpec` 已承载 stepBudget 且 `agent.run(runSpec, inbox)` 是现成入口，改 RunSpec 改动面最小。检查点放在步开头（`appendSteers` 之后、调模型之前）：累计值读自协程上下文里的 `RunUsageTally`（加一个 `total(): Long` = input+output+cached），`total() >= tokenBudget` 即 `return finalTurn(..., closeout = Closeout.TokenLimit)`。
   不在单次调用前后包一层「这次会超就别发」：单次调用的 token 量不可预知，只能事后累计。

2. **到限收尾复用 `finalTurn`，新增 `Closeout.TokenLimit` 与 `FailKind.TokenLimit`，不重用 StepLimit。**
   收尾轮机制完全照搬（只剩 end_not_done、最多重试三轮、插话处理），只换触发原因；提示词条在 `prompts/feedback/loop.md` 新增 `token_limit_closeout`（英文，与 `final_turn` 同结构，说明 token 预算用完、只可交出部分结果）。结局区分 StepLimit/TokenLimit 让时间线、评测归因（`Scoring`）、远端记录（`RemoteTaskRecord` → `"token_limit"`）能各自识别。
   `FailKind` 注释里「只有 GaveUp、StepLimit 算」的学习语义：TokenLimit 同等对待——`SkillOutcomes`、`AppKnowledgeLearning` 里与 StepLimit 并列（预算到限＝任务没办成但不是模型做砸）。

3. **合计口径 = input + output + cachedInput。**
   三者都是真实消耗（缓存命中也计费，只是便宜），保护额度的目的下按总量计最直白，也与「Token 用量」页的口径一致。`RunUsageTally` 里三者分开存，新增 `total()` 即可。

4. **预算只挂主循环；子任务不传预算。**
   子循环与父循环共享同一份 tally（同一协程上下文），子任务消耗天然计入父预算、由父循环在步边界拦停；给子任务再各设一份预算只会把同一池子切小，无额外保护价值。这与「子任务用自己的 stepBudget」不同——步数是每个循环自己的调度维度，token 是整个任务的经济维度。

5. **设置存储与 UI 照抄步数上限。**
   `FeatureFlags`：prefs 存 Long，`0 = 不限`，范围 1 万–1 亿；`taskTokenBudget(): Long?` / `setTaskTokenBudget(Long?)`。UI：`DuringWorkScreen` 步数行下加一行 + 对话框（不限 / 50 万 / 100 万 / 500 万 / 1000 万 / 自定义），自定义输入数字（单位 token）。预设按「截图密集型任务一步 1–5 万 token、60 步任务常见几十万到几百万」取档。

6. **`RunEvent.TaskStarted` 加 `tokenBudget: Long? = null`（kotlinx 序列化，旧记录缺省 null）。**
   时间线 `TimelineHeader` 加 `tokenBudget` 与已用合计（来自 `RunEvent.ModelCalls` 事件的累计），显示逻辑与 stepLimit 行并列；null 不显示。运行中的任务页已按秒重读，超限停下的瞬间自然刷新。

## Risks / Trade-offs

- [单次大调用可能让实际消耗明显超出预算（如一次 96K 的读屏请求）] → 说明里如实写「达到预算后停止，实际消耗可能略超」；检查点在步边界，最多超「最后一次越界调用的量 + 收尾轮」。
- [服务商不报 usage 时预算完全失效] → 设置项副文案写明「按服务商报告的用量计」；记账原则不破例。
- [预算把正常长任务误停] → 默认不限制；预设档从 50 万起；到限收尾交代部分结果，用户可加预算重发。
- [cached 计入让缓存命中率高的 ChatGPT 登录方案提前触顶] → 与「Token 用量」页口径一致比另造口径更重要；真按净计费额限流属于「每服务商每日预算」的范畴。
- [时间线只读记录加大一个字段] → 序列化缺省 null，旧记录照读；新记录旧版本读到未知字段由 kotlinx 忽略（RunEvent 已是忽略未知字段的配置，见既有新字段先例）。

## Migration Plan

纯新增、默认关闭（不限制），无数据迁移；回滚即还原代码。prefs 新键无旧值，首次读取为不限。
