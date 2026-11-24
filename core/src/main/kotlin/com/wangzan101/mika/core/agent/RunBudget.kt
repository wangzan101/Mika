package com.wangzan101.mika.core.agent

/**
 * Immutable limits captured once when a task starts.
 *
 * Token accounting stays in [com.wangzan101.mika.core.usage.RunUsageTally]; this type only carries
 * time, step and liveness limits shared by the watchdog, agent loop and child runs.
 */
data class RunBudget(
    val maxSteps: Int? = null,
    val timeBudgetMs: Long? = null,
    val modelCallTimeoutMs: Long = DEFAULT_MODEL_CALL_TIMEOUT_MS,
    val phoneToolTimeoutMs: Long? = null,
    val noEventWarnMs: Long = DEFAULT_NO_EVENT_WARN_MS,
    val noEventStopMs: Long = DEFAULT_NO_EVENT_STOP_MS,
) {
    init {
        require(maxSteps == null || maxSteps > 0)
        require(timeBudgetMs == null || timeBudgetMs > 0)
        require(modelCallTimeoutMs > 0)
        require(phoneToolTimeoutMs == null || phoneToolTimeoutMs > 0)
        require(noEventWarnMs > 0)
        require(noEventStopMs > noEventWarnMs)
    }

    fun timeRemainingMs(startedAtMs: Long, nowMs: Long): Long? =
        timeBudgetMs?.let { (it - (nowMs - startedAtMs).coerceAtLeast(0)).coerceAtLeast(0) }

    fun softTimeLimitReached(startedAtMs: Long, nowMs: Long): Boolean {
        val total = timeBudgetMs ?: return false
        return nowMs - startedAtMs >= total * 4 / 5
    }

    companion object {
        const val DEFAULT_MODEL_CALL_TIMEOUT_MS = 180_000L
        const val DEFAULT_NO_EVENT_WARN_MS = 60_000L
        const val DEFAULT_NO_EVENT_STOP_MS = 120_000L
        val DEFAULT = RunBudget()
    }
}