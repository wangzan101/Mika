package com.wangzan101.mika.task

/** A bounded CPU lease follows absolute live deadlines; repeated checks cannot extend its cap. */
internal class TaskDeadlineCpuLease(
    private val elapsedMs: () -> Long,
    private val acquire: (timeoutMs: Long) -> Unit,
    private val release: () -> Unit,
) {
    private var heldUntil: Long? = null

    @Synchronized fun update(deadlineMs: Long?) {
        val until = deadlineMs?.let { if (it > Long.MAX_VALUE - CLEANUP_MS) Long.MAX_VALUE else it + CLEANUP_MS }
        if (until == heldUntil) return
        val remaining = until?.minus(elapsedMs())?.coerceAtLeast(0L) ?: 0L
        if (remaining == 0L) {
            if (heldUntil != null) release()
            heldUntil = null
        } else {
            acquire(remaining)
            heldUntil = until
        }
    }

    companion object { private const val CLEANUP_MS = 30_000L }
}
