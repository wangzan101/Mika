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

## 真机验证（华为 Mate X2，TET-AN50，HarmonyOS 4.2 底座 / API 31）

- 包：`:app:assemblePerf` 的 arm64 perf 包，`adb install -r` 覆盖安装（原 0.1.37 → 本分支）。

（真机时间线待补：验证时手机被 PIN 锁定，解锁后补做以下三步并把截图放本目录。）

- [ ] 设置 › 干活时出现「每个任务的 Token 预算」，预设与自定义对话框可用（02/03 号截图）。
- [ ] 设 1 万 token 的预算，跑一个会调工具的任务 → 任务停在
      「已达到本任务 1 万 token 的预算上限,任务没有完成。」，时间线出现 Token「已用 / 预算」行（04/05 号截图）。
- [ ] 改回不限制，同一任务正常跑完（06 号截图）。
