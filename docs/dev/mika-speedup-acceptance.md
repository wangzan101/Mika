# Mika 提速实现与验收

更新日期：2026-11-24。worktree：`/Users/lidashuai5/.codex/worktrees/mika-speedup/Mika-repo`；分支：`codex/mika-speedup`，基于 `2f0a73ec`。另一核验会话已整合提交 `ab0c28c4`；本轮CPU唤醒锁及后续验证补充保留在工作区。

## 当前结论

R1–R6 的执行隔离、失败熔断、缓存归因、前缀失效及自动化回归已落地。P1.2 的系统闹钟与持久化截止已通过60秒设备用例，但十分钟真实等待任务仍迟到49621ms，严格息屏截止未通过。本轮增加有界CPU唤醒锁，正在重新验证，不能提前标为完整通过。P2.1 官方直连 provider 缓存 A/B 仍未完成。

## 规划对应

| 项目 | 当前实现与验证范围 |
| --- | --- |
| P0.1 读屏诊断 | 窗口、根节点、节点属性/遍历分段计时，不保存页面文字；详细 trace 或慢读记录。 |
| P0.2 / R2 等待超时 | 每任务 worker、等待与普通手机工具统一隔离。未建立端口返回 no_app_open/phone_unavailable，不伪报 timeout。真实10000ms等待实测10046ms，后续读屏374ms成功。 |
| P0.3 / R4 失败熔断 | 12个结果滚动窗，同目标按 Place + frameHash + action + error 累计。普通成功读屏不清零；目标成功、页面变化或窗口过期才清除。 |
| P0.4 无限步安全网 | 无限步不用于计算停滞阈值，仍保留有限安全门槛。 |
| P1.1/1.3/1.4 预算配置 | 启动时冻结，默认60步/10分钟，独立迁移键保留显式不限制，普通任务时长设置和12语言资源齐全。 |
| P1.2a / R3 工具边界 | 权限/目标检查、执行、前后读屏、截图、组合和嵌套动作、批量条件等待、稳定等待、文字、核查/回放旁路均受worker边界保护。过期RunDeadline也在NonCancellable中阻止新动作。 |
| P1.2b / R1/R2 隔离/收尾 | STUCK先登记，实际Callable退出才释放。父子worker清理屏障，TaskCenter保留尚未清理的资源所有者；30/60秒恢复、两次失败提示重启。连接健康不代替旧worker退出。 |
| P1.2 休眠截止（本轮新增） | TaskDeadlineStore先原子持久化预算和绝对截止；TaskDeadlineRegistry统一排最早到期闹钟；AlarmManager ELAPSED_REALTIME_WAKEUP + setExactAndAllowWhileIdle；无精确权限时明确降级。恢复/模型/工具入口再次检查绝对截止。 |
| P2.0 / R5 缓存归因 | 仅cacheReported=true计入已上报，含明确零命中；false/null归未上报。provider、事件、summary、导出白名单贯通。 |
| P2.2 / R6 稳定前缀 | steer、溢出、模型/App/页面结构切换与事实矛盾使摘要失效。无旧摘要不提前压缩。最终prompt/tool/message变换后生成digest/epoch，保留prefixMessages/model/reason。 |
| P3.1 模型超时 | 生产模型独立墙钟180秒、心跳及流停顿监测；单调时钟不受日志时钟冻结影响；宿主截止检查也覆盖计量模型和内部重试。 |
| P3.2 收敛/活性 | 80%预算提醒一次；无事件60秒警告/120秒停止，等待用户/手动接管等状态豁免。 |
| 终态一致性 | TaskTerminalRecord立即记录宿主认领的TimeLimit，迟到取消不能覆盖。 |

## 新的截止时间语义

- 本轮新增CPU唤醒锁仅随有时限的活动运行持有，屏幕仍可关闭；上限为最晚活动截止加30秒收尾余量，重复检查不顺延上限。正常完成、取消及显式退出释放，崩溃后遗留记录不持锁。会增加运行/等待期间耗电；无限时任务不因此持锁。
- 每次运行单独保存runKey、budgetMs、wallDueMs、elapsedDueMs、boot、remoteId、triggered；不保存提示词、页面或凭据。
- 同一开机周期使用包含休眠的elapsedRealtime；设备重启后用wallDueMs判定。系统时间前后调整不会改变当前开机周期的剩余预算。
- 一个最早截止闹钟服务全部运行，避免PendingIntent覆盖。早到/重复广播先重读截止信息；一个任务到期回调失败不丢弃其他任务。
- TaskService独立终态发布与资源隔离保持原实现；原delay作为进程内后备，不再是唯一触发源。
- 进程被重建时：已过期且无已完成终态的运行补记TimeLimit并走原渠道交付；未过期但进程丢失的运行仍按原恢复规则记中断，不自动重放手机操作。
- 显式退出撤销闹钟；暂停期间到期广播不交付，恢复检查在注册表同一把锁内确认退出标记，防止迟到检查重新排闹钟。退出后被系统拉起时不补交任务结果，用户重新进入才恢复。正常结束删除截止记录；清理元数据失败不会阻断任务取消。
- 精确闹钟权限不可用时使用非精确唤醒并记录`state=task_deadline_armed`的降级模式。绝对截止仍阻止恢复后新增动作，但不能承诺准点唤醒。系统idle闹钟频率限制仍适用，最早截止合并不能消除它。此实现不能绕过用户强行停止、系统拒绝调度或任意OEM冻结策略。

## 当前自动化结果

2026-11-24 19:30，本会话针对最终代码执行全量测试并成功构建，耗时6分28秒：

- core：4326项，5项跳过，0失败，0错误。
- app：3589项，0跳过，0失败，0错误。
- Dev主APK与设备测试APK构建成功，Android测试编译通过。
- 12种UI语言、4251个资源检查通过；speedup stats 2项测试通过；git diff --check通过。
- 新增截止注册表10项和核心截止4项均包含在上述全量中，覆盖休眠时钟、多任务、重复广播、重启、权限降级、退出竞态、并发到期回调失败，以及过期后模型/工具入口拒绝。

首次全量曾因文档将状态事件写成工具名而失败；修正文档为明确的state标识后，重新执行全部core/app测试通过。
```sh
./gradlew -I /private/tmp/mika-mirror.init.gradle \
  :core:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --offline
python3 tools/i18n/translations.py --res-dir app/src/main/res check
python3 -B -m unittest discover -s tools -p test_speedup_stats.py -v
git diff --check
```

本轮使用独立临时build目录，避免两个智能体同时写同一Kotlin增量编译目录。`settings.gradle.kts`未修改。

## 本轮真机状态（CPU补强前）

2026-11-24，华为LIO-AN00，精确闹钟权限已有，Dev在电池优化白名单；没有强制深度Doze，没有修改省电策略。主APK与测试APK的SHA-256均核对一致。

- 系统闹钟独立用例：TaskDeadlineAlarmDeviceTest，sleep=true、deadlineMs=60000，1项通过、总耗时60.070秒；无任务协程delay后备，到期检查容差小于5秒，记录清理成功。
- 第一轮真实ask_user任务收到“可以”回答后正常结束，不计作硬停通过；模型声称测试通过的文本不作验收依据。
- 第二轮真实任务：0403163e-1780-4488-8c7b-196b9d4ffb38，运行20261124-194716-cxdkz0，预算600000ms，持久化截止19:57:15.909。截止后15秒和45秒读取时屏幕仍Asleep且无终态；19:58:05.530才记录TimeLimit，实际649621ms、迟到49621ms。第三次观察屏幕已Awake，观察脚本未发唤醒命令。严格截止失败，亮屏原因未确定。
- API、事件和summary均为唯一TimeLimit，deadline元数据已删除，后续运行可启动。
- 硬停后京东只读：22060ms完成，打开真实App并读到秒送/超市秒送/外卖。
- 硬停后phone_wait：appear不存在的探针文本，声明10000ms，实际10071ms、timedOut=true；后续phone_observe为250ms且成功。另一次stable条件仅510ms完成，不作为十秒超时验收。

长时间息屏与短设备用例的差异已保留。系统闹钟统计显示本次唤醒，但未抓到完整截止时刻日志，不能仅凭当前证据确定是OEM调度、进程冻结还是应用处理延迟。本轮CPU唤醒锁补强后将重新执行真实十分钟任务，结果另记。

本轮日志、XML摘要、任务导出和APK SHA-256保存于工作区 `mika-real-validation-2026-11-24/sleep-deadline/`；首次失败结果为real-result.json。主APK及设备测试APK位于 `/private/tmp/mika-sleep-deadline-tabuwpu1/builds/app/outputs/apk/`。

## 已有真机证据（历史基线，不是新闹钟验收）

- 京东App只读：真实phone_launch_app(com.jingdong.app.mall)成功，读到秒送/超市秒送/外卖入口，任务墙钟21361ms，无下单支付或网页降级。
- phone_wait：声明10000ms、实际10046ms，随后phone_observe成功；错误语义修复后的空端口明确返回no_app_open、timedOut=false、executed=false。
- 旧实现仅delay的受控任务：600000ms预算实际729525ms结束，晚129525ms，点亮屏幕后才落盘。TimeLimit终态归因正确，但严格休眠墙钟不通过，这是本轮改动要解决的基线问题。
- 注入不可中断ScreenPort的Android测试通过，证明worker边界和延后释放；不等于真实永久Binder卡死注入。

## 仍需外部实验

P2.1 官方直连provider A/B未执行。已配置provider openapi.wtsht.cn的真实回复均未上报缓存字段，不能据cachedTokens=0推断未命中，更不能宣称提速幅度。

```sh
python3 tools/speedup_stats.py before.jsonl after.jsonl
```

对比需保持模型、任务和设备条件一致，多次运行。统计工具输出模型延迟median/p95、墙钟、phone_wait超时比和缓存命中率；命中率分母只含明确上报缓存字段的调用。digest相等也不能单独证明provider缓存命中。
