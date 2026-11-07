package com.wangzan101.mika.core.trigger

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** 手动编辑器支持的重复方式；更复杂的多钟点规则仍由后续数据模型扩展承接。 */
enum class PlanRepeat { Once, Interval, Daily, Weekly }

enum class PlanValidation {
    MissingContent,
    ContentTooLong,
    ConditionTooLong,
    ReminderWithCondition,
    InvalidFirstTime,
    TimeNotFuture,
    InvalidInterval,
    InvalidClock,
    InvalidZone,
    InvalidWeekdays,
    InvalidCooldown,
}

class PlanValidationException(val reason: PlanValidation) : IllegalArgumentException(reason.name)

/** 页面上的原始输入集中在这里，预览和最终保存必须走同一次转换规则。 */
data class PlanDraft(
    val content: String,
    val reminder: Boolean = false,
    val condition: String = "",
    val repeat: PlanRepeat = PlanRepeat.Once,
    val firstAt: String,
    val intervalMinutes: String = "60",
    /** 一个或多个当地钟点，用逗号或空格分隔；实际解析在 [editedTrigger] 里。 */
    val clocks: String = "09:00",
    val zone: String,
    val weekdays: Set<Int> = emptySet(),
    val whenIdle: Boolean = false,
    val enabled: Boolean = true,
    val notifyWhen: ConditionNotify = ConditionNotify.BecomesMet,
    val cooldownMinutes: String = "",
)

/** 每天的钟点上限；再多就该用间隔计划。 */
const val MAX_CLOCK_TIMES = 12

/** 把「08:00, 12:30」这类输入拆成钟点表：去空白、去重、按时间排序；有任何一项不合法就返回 null。 */
fun parseClockTimes(raw: String): List<String>? {
    val out = LinkedHashSet<String>()
    for (part in raw.split(',', '，', ' ', '\t').filter { it.isNotBlank() }) {
        val text = part.trim()
        val time = try { LocalTime.parse(text) } catch (e: Exception) { return null }
        // 只认 HH:mm，别让 9:00 这类写法在比较和排序里变成另一种形状
        if (CLOCK_FORMAT.format(time) != text) return null
        out += text
    }
    if (out.isEmpty() || out.size > MAX_CLOCK_TIMES) return null
    return out.sorted()
}

private val EDIT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
    .withResolverStyle(ResolverStyle.STRICT)

fun formatPlanEditorTime(atMs: Long, zone: String): String =
    EDIT_TIME.format(Instant.ofEpochMilli(atMs).atZone(ZoneId.of(zone)))

fun newPlanDraft(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): PlanDraft {
    val first = Instant.ofEpochMilli(nowMs).atZone(zone).plusHours(1).withSecond(0).withNano(0)
    return PlanDraft(
        content = "",
        firstAt = EDIT_TIME.format(first),
        zone = zone.id,
        weekdays = setOf(first.dayOfWeek.value),
    )
}

fun TriggerDefinition.toPlanDraft(): PlanDraft {
    val zone = calendarZone ?: ZoneId.systemDefault().id
    val times = clockTimes
    return PlanDraft(
        content = reminderText ?: goal,
        reminder = reminderText != null,
        condition = conditionGoal.orEmpty(),
        repeat = when {
            times.isNotEmpty() && calendarDays.isEmpty() -> PlanRepeat.Daily
            times.isNotEmpty() -> PlanRepeat.Weekly
            intervalMs != null -> PlanRepeat.Interval
            else -> PlanRepeat.Once
        },
        firstAt = formatPlanEditorTime(nextAtMs, zone),
        intervalMinutes = intervalMs?.let { (it / 60_000).toString() } ?: "60",
        clocks = times.joinToString(", ").ifEmpty { "09:00" },
        zone = zone,
        weekdays = calendarDays.toSet(),
        whenIdle = idleOnly,
        enabled = enabled,
        notifyWhen = notifyWhen,
        cooldownMinutes = cooldownMs?.let { (it / 60_000).toString() }.orEmpty(),
    )
}

/**
 * 把表单变成可持久化的计划。编辑时只替换表单负责的字段，保留 ID、对话和执行账本；
 * 条件语义改变后旧值不能继续作为新条件的比较基线。
 */
fun editedTrigger(
    draft: PlanDraft,
    original: TriggerDefinition?,
    newId: String,
    newThreadId: String,
    nowMs: Long,
): TriggerDefinition {
    val content = draft.content.trim()
    if (content.isEmpty()) invalid(PlanValidation.MissingContent)
    if (content.length > if (draft.reminder) 2_000 else 4_000) invalid(PlanValidation.ContentTooLong)
    val condition = draft.condition.trim().ifEmpty { null }
    if ((condition?.length ?: 0) > 2_000) invalid(PlanValidation.ConditionTooLong)
    if (draft.reminder && condition != null) invalid(PlanValidation.ReminderWithCondition)
    val zone = try { ZoneId.of(draft.zone.trim()) } catch (e: Exception) {
        invalid(PlanValidation.InvalidZone)
    }
    val nextAt: Long
    var intervalMs: Long? = null
    var calendarTime: String? = null
    var calendarTimes: List<String> = emptyList()
    var calendarZone: String? = null
    var calendarDays: List<Int> = emptyList()
    when (draft.repeat) {
        PlanRepeat.Once, PlanRepeat.Interval -> {
            nextAt = try {
                LocalDateTime.parse(draft.firstAt.trim(), EDIT_TIME).atZone(zone).toInstant().toEpochMilli()
            } catch (e: Exception) {
                invalid(PlanValidation.InvalidFirstTime)
            }
            if (nextAt <= nowMs) invalid(PlanValidation.TimeNotFuture)
            if (draft.repeat == PlanRepeat.Interval) {
                val minutes = draft.intervalMinutes.trim().toLongOrNull()
                if (minutes == null || minutes !in 1..525_600) invalid(PlanValidation.InvalidInterval)
                intervalMs = minutes * 60_000
            }
        }
        PlanRepeat.Daily, PlanRepeat.Weekly -> {
            val days = if (draft.repeat == PlanRepeat.Weekly) draft.weekdays.sorted() else emptyList()
            if (draft.repeat == PlanRepeat.Weekly && (days.isEmpty() || days.any { it !in 1..7 })) {
                invalid(PlanValidation.InvalidWeekdays)
            }
            val times = parseClockTimes(draft.clocks) ?: invalid(PlanValidation.InvalidClock)
            nextAt = try {
                nextCalendarAt(times, zone.id, days, nowMs)
            } catch (e: Exception) {
                invalid(PlanValidation.InvalidClock)
            }
            // 只有一个钟点时写旧字段，旧版本也能读；多个才进 sidecar
            if (times.size == 1) calendarTime = times.single() else calendarTimes = times
            calendarZone = zone.id
            calendarDays = days
        }
    }
    val cooldownMs = if (condition != null && draft.cooldownMinutes.isNotBlank()) {
        val minutes = draft.cooldownMinutes.trim().toLongOrNull()
        if (minutes == null || minutes !in 1..525_600) invalid(PlanValidation.InvalidCooldown)
        minutes * 60_000
    } else null
    val reminderText = content.takeIf { draft.reminder }
    val conditionChanged = original != null && (
        original.conditionGoal != condition || original.notifyWhen != draft.notifyWhen ||
            original.cooldownMs != cooldownMs || original.reminderText != reminderText
        )
    val base = original ?: TriggerDefinition(newId, newThreadId, content, nextAt)
    return base.copy(
        goal = content,
        nextAtMs = nextAt,
        intervalMs = intervalMs,
        conditionGoal = condition,
        enabled = draft.enabled,
        idleOnly = draft.whenIdle,
        reminderText = reminderText,
        calendarTime = calendarTime,
        calendarTimes = calendarTimes,
        calendarZone = calendarZone,
        calendarDays = calendarDays,
        notifyWhen = draft.notifyWhen,
        cooldownMs = cooldownMs,
        lastConditionMet = if (conditionChanged) null else base.lastConditionMet,
        lastValue = if (conditionChanged) null else base.lastValue,
        baselineValue = if (conditionChanged) null else base.baselineValue,
        baselineAtMs = if (conditionChanged) null else base.baselineAtMs,
        lastFiredAtMs = if (conditionChanged) null else base.lastFiredAtMs,
    )
}

private fun invalid(reason: PlanValidation): Nothing = throw PlanValidationException(reason)
