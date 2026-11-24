package com.wangzan101.mika.task

import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException

internal class PhoneExecutionStuckException(message: String) : Exception(message)

/**
 * 任务墙钟守卫:到点先让调用方发布终态;工作协程若仍未退出,再进入手机资源隔离并尝试恢复。
 * 所有时间都相对任务真正开始执行计算。
 */
internal class TaskWatchdog(
    private val isWorkerActive: () -> Boolean,
    private val onTimeout: suspend () -> Unit,
    private val onStillStuck: suspend (Long) -> Unit,
    private val recover: suspend () -> Boolean,
    private val onRecovered: () -> Unit,
    private val onNeedsRestart: () -> Unit,
    private val stuckAfterMs: Long = STUCK_AFTER_MS,
    private val recoverAfterMs: Long = RECOVER_AFTER_MS,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    init {
        require(stuckAfterMs > 0)
        require(recoverAfterMs > stuckAfterMs)
    }

    suspend fun run(timeLimitMs: Long) {
        require(timeLimitMs > 0)
        wait(timeLimitMs)
        if (!isWorkerActive()) return
        onTimeout()

        recoverStuck()
    }

    /** Also used for a tool timeout before the overall task budget expires. */
    suspend fun recoverStuck() {
        wait(stuckAfterMs)
        if (!isWorkerActive()) return
        onStillStuck(stuckAfterMs)

        wait(recoverAfterMs - stuckAfterMs)
        repeat(RECOVERY_ATTEMPTS) { attempt ->
            if (!isWorkerActive()) return
            val healthy = try { recover() } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            if (healthy) {
                // A healthy connection is not enough: the old Binder/tool worker must also stop.
                wait(WORKER_EXIT_WAIT_MS)
                if (!isWorkerActive()) {
                    onRecovered()
                    return
                }
            }
            if (attempt + 1 < RECOVERY_ATTEMPTS) wait(RECOVERY_RETRY_MS)
        }
        onNeedsRestart()
    }

    companion object {
        const val STUCK_AFTER_MS = 30_000L
        const val RECOVER_AFTER_MS = 60_000L
        const val WORKER_EXIT_WAIT_MS = 5_000L
        const val RECOVERY_RETRY_MS = 5_000L
        const val RECOVERY_ATTEMPTS = 2
    }
}