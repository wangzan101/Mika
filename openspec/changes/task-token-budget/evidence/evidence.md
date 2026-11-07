# task-token-budget 的验证取证（issue #158 剩余部分）

分支 `feat/158-token-budget`（基于 main `188ffe42`）。

## 单元测试（全绿）

- `:core:test :core:cliTest :app:testDebugUnitTest` 全部通过（3019+ 用例）。
- 新增/更新用例：
  - `AgentLoopTokenBudgetTest`：到限停（FailKind.TokenLimit、收尾轮只剩 end_not_done、
    收尾提示含 "token budget"、TaskStarted 事件带预算）/ 预算内不干预 / usage=null 不触发 /
    不设预算时 TaskStarted.tokenBudget 为 null。
  - `RunUsageTallyTest`：total() = input+output+cached，未报用量不加。
  - `LoopPoliciesTest`：tokenLimit 文案与 kind、closeoutFailed/afterFinalTurn 按 Closeout 落结局、
    tokenBudgetText 的万/亿写法。
  - `AgentLoopRecordTest`：旧记录（无 tokenBudget 字段）解码为 null，新记录往返不丢。
  - `FeatureFlagsTokenBudgetTest`（app）：默认不限、保存、越界收敛。
  - `RemoteTaskRecordTest`（app）：盘上编码 `token_limit`。
- 提示词快照 `prompt-surface/sizes.txt` 随 loop.md 新词条更新（49128 → 49509 字节）。

## 真机验证（华为 Mate X2，TET-AN50，HarmonyOS 4.2 底座 / API 31，2026-11-07）

- 包：`:app:assemblePerf` 的 arm64 perf 包，`adb install -r` 覆盖安装（原 0.1.37 → 本分支）。
- 主模型：设置里已配置的服务商（智谱 Coding Plan），usage 由服务商真实上报——
  预算走的是产品真实链路（MeteredModel → RunUsageTally → 主循环检查）。

### 时间线（截图在本目录）

| # | 步骤 | 结果 | 截图 |
| --- | --- | --- | --- |
| 1 | 设置 › 任务运行（干活时） | 「每个任务的 Token 预算」行出现在步数上限下方，默认「不限制」 | 02-during-work.png |
| 2 | 点预算行开对话框 | 不限制 / 50 万 / 100 万 / 500 万 / 1000 万 / 自定义（1 万–1 亿），副说明写明口径与局限；输入法弹出会压住「使用」，收起键盘后应用成功 | 03-budget-dialog.png |
| 3 | 自定义 10000 应用 | 行值显示「1 万 Token」（与 Token 用量页同一个数字格式） | 04-budget-set.png |
| 4 | 发任务 "Remember this preference of mine: my favorite test number is 42" | 第 1 步 memory_save 消耗 1.8 万 token；第 2 步开头预算检查（1.8 万 ≥ 1 万）→ 到限收尾，任务结束「没有完成」，对话行显示「做了 1 步 · 36 秒 · 1.8 万 Token」 | 05-budget-stop.png |
| 5 | 打开该运行的「查看过程」 | 时间线统计区出现 **Token「1.8 万 / 1 万」** 行（与步数「已用/上限」并列）；第 2 步即收尾轮，模型交代没办成的原因（保存记忆被内容校验拦下）——与步数上限的同款收尾语义 | 06-timeline-budget.png |
| 6 | 预算改回「不限制」，发同类任务 "What is 1 plus 1? Answer directly." | 消耗 1.22 万 token（超过第 4 步的预算档）正常完成，回答「1 加 1 等于 2。」——预算关闭后无任何干预 | 07-unlimited-ok.png |

### 说明

- 到限后的可见结局走的是收尾轮模型自己的交代（本例 memory_save 已被内容校验拦下，模型如实
  说明），这和步数上限的行为一致；「已达到本任务 N token 的预算上限」的 Failed 文案在收尾轮
  没给出有效收尾时出现（单测 AgentLoopTokenBudgetTest 覆盖）。
- 截图仅含 Mika 自身界面与本次测试内容，无个人信息；已逐张目检。
- 验证后已把预算恢复为「不限制」，未在手机上留下其他状态变更。
