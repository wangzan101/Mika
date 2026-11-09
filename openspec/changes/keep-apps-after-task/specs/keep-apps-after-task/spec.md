## ADDED Requirements

### Requirement: 用户可选择任务结束后保留被操作的 App
设置 › 干活时 SHALL 提供开关「任务结束后保留被操作的 App」，默认关闭（与现状一致）。开启后，任务收尾时正在出声的 App SHALL 与其它 App 一样保留（挪到主屏后面），不再因出声被关闭；该开关对下一个任务起生效。

#### Scenario: 出声的 App 被保留
- **WHEN** 开关开启，任务结束时被操作的 App 正在播放音频
- **THEN** 该 App 被挪到主屏后面保留，静音还原后声音继续；运行记录的 apps_after_task 事件把它记为留下（原因 user_setting）

#### Scenario: 默认行为不变
- **WHEN** 开关关闭，任务结束时被操作的 App 正在播放音频
- **THEN** 该 App 随虚拟屏关闭（现状不变）

#### Scenario: 显式关闭标记仍然生效
- **WHEN** 开关开启，但模型按用户要求把某 App 标为 close
- **THEN** 该 App 照样关闭；Mika 自己的页面与非普通 App 页面也照样关闭

#### Scenario: 挪回复查不误伤
- **WHEN** 开关开启，某 App 挪到主屏后才开始出声
- **THEN** 不把它挪回关闭（用户已接受保留出声的 App）
