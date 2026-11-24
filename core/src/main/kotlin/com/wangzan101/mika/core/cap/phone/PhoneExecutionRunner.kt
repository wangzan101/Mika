package com.wangzan101.mika.core.cap.phone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.ContinuationInterceptor

/** One task's local I/O worker. Cancellation revokes work; only the Callable's finally releases it. */
internal class PhoneExecutionRunner(
    private val onRecovered: () -> Unit = {},
    private val onCancelled: () -> Unit = {},
) {
    sealed interface Result<out T> {
        data class Done<T>(val value: T) : Result<T>
        data object TimedOut : Result<Nothing>
        data object Stuck : Result<Nothing>
        data class Failed(val error: Throwable) : Result<Nothing>
    }

    private class Call {
        val job = Job()
        val finished = CompletableDeferred<Unit>()
        @Volatile var thread: Thread? = null
        @Volatile var quarantined = false
    }

    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "mika-phone-tool").apply { isDaemon = true } }
    @Volatile private var active: Call? = null
    @Volatile private var closed = false

    val isStuck: Boolean get() = active?.quarantined == true
    val isBusy: Boolean get() = active != null

    suspend fun <T> execute(timeoutMs: Long, block: suspend () -> T): Result<T> {
        val context = currentCoroutineContext()
        context.ensureActive()
        val call = synchronized(lock) {
            if (closed || active != null) return Result.Stuck
            Call().also { active = it }
        }
        // Preserve inbox, token tally and manual-control lease, with an independent worker Job.
        val workerContext = context.minusKey(Job).minusKey(ContinuationInterceptor) + call.job
        val task = try {
            worker.submit(Callable {
                call.thread = Thread.currentThread()
                try { runBlocking(workerContext) { block() } }
                finally {
                    synchronized(lock) {
                        active = null
                        call.job.complete()
                        if (call.quarantined) runCatching(onRecovered)
                        call.finished.complete(Unit)
                    }
                    Thread.interrupted()
                }
            })
        } catch (e: Exception) {
            synchronized(lock) { active = null; call.job.complete(); call.finished.complete(Unit) }
            return Result.Failed(e)
        }
        return try {
            Result.Done(runInterruptible(Dispatchers.IO) { task.get(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS) })
        } catch (e: CancellationException) {
            quarantine(call)
            throw e
        } catch (_: TimeoutException) {
            quarantine(call)
            Result.TimedOut
        } catch (e: Exception) {
            Result.Failed(e.cause ?: e)
        }
    }

    private fun quarantine(call: Call) = synchronized(lock) {
        if (active !== call || call.quarantined) return@synchronized
        call.quarantined = true
        // Publish STUCK before cancellation can finish the worker and publish recovery.
        runCatching(onCancelled)
        call.job.cancel()
        call.thread?.interrupt()
        // Never cancel the Future: cancellation before start would skip the finally block.
    }

    suspend fun awaitIdle() {
        while (true) (synchronized(lock) { active?.finished } ?: return).await()
    }

    /** Revoke admission immediately; queued Callables still run their finally block. */
    fun close() = synchronized(lock) {
        closed = true
        active?.let(::quarantine)
        worker.shutdown()
    }
}