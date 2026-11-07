## ADDED Requirements

### Requirement: 用户可设置每个任务的 token 预算
设置 › 干活时 SHALL 提供「每个任务的 token 预算」：不限制（默认）/ 50 万 / 100 万 / 500 万 / 1000 万 / 自定义（1 万–1 亿 token）。设置对下一个任务起生效，条件检查（readOnly）任务不设预算。预算按一次任务内经计量的全部模型调用（含旁路调用与失败重试）的 input+output+cachedInput token 合计。

#### Scenario: 默认不限制
- **WHEN** 用户从未设置过 token 预算
- **THEN** 任务照常运行，不因 token 消耗停止

#### Scenario: 设了预算后从下一个任务起生效
- **WHEN** 任务运行中用户把预算从「不限制」改为 100 万
- **THEN** 正在运行的任务不受影响，下一个任务按新预算检查

#### Scenario: 条件检查任务不受预算约束
- **WHEN** 一条定时/条件触发器做只读条件检查（readOnly 运行）
- **THEN** 该运行不因 token 预算停止

### Requirement: 运行中达到预算即收尾
主循环每一步调模型之前 SHALL 按当前任务的 token 合计检查预算；合计达到预算时停止调度新的模型调用，进入与步数上限相同的收尾轮（工具只剩 end_not_done），结局为 `FailKind.TokenLimit`，说明里写明预算值与已用 token。服务商不报用量的调用（usage 为 null）不估算、不计入。

#### Scenario: 累计用量达到预算
- **WHEN** 预算为 10 万，前几步累计（input+output+cached）已到 10 万，任务尚未完成
- **THEN** 下一步不再调主模型，直接进入收尾轮；任务以 `FailKind.TokenLimit` 结束，失败说明包含预算值

#### Scenario: 预算内正常完成
- **WHEN** 预算为 100 万，任务累计 5 万 token 后正常交出回答
- **THEN** 任务照常成功，预算不产生任何干预

#### Scenario: 不报用量的调用不计入
- **WHEN** 服务商对所有调用都不返回 usage，累计估算因此为 0
- **THEN** 预算检查不触发（不为 null 的预算也不凭空估算）

#### Scenario: 子任务与旁路调用共享同一份预算
- **WHEN** 任务派了只读调研子任务，子任务的模型调用消耗 token
- **THEN** 这些消耗计入主任务的预算合计，主循环检查时一并算

### Requirement: 运行时间线展示 token 预算
任务详情页的运行时间线 SHALL 在设了预算的运行里显示「token 已用 / 预算」；预算未设或旧记录没有该字段时 SHALL 不显示该行。

#### Scenario: 设了预算的运行
- **WHEN** 查看一次带 token 预算的运行记录
- **THEN** 时间线统计区显示 token 已用量与预算上限

#### Scenario: 旧记录向后兼容
- **WHEN** 查看预算功能发布前的运行记录
- **THEN** 时间线照常展示，不出现预算行，也不报错
