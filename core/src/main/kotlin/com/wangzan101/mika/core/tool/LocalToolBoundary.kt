package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.action.ToolDeadline
import com.wangzan101.mika.core.capability.LocalToolExecutionScope
import com.wangzan101.mika.core.capability.LocalToolResult
import com.wangzan101.mika.core.capability.LocalToolRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Covers a capability's complete operation, including screen preparation and result capture. */
internal class LocalToolBoundary(private val runners: List<LocalToolRunner>) {
    var timeoutMs: Long? = null
    var onStuck: ((Long) -> Unit)? = null
    var onRecovered: (() -> Unit)? = null
    @Volatile private var closed = false

    fun supports(capability: String?) = runners.any { it.localToolCapability == capability }
    val busy: Boolean get() = runners.any { it.localToolBusy }

    suspend fun <T> run(capability: String?, capMs: Long? = null, block: suspend () -> T): T {
        com.wangzan101.mika.core.run.RunDeadline.checkCurrent()
        val context = currentCoroutineContext()
        context[LocalToolExecutionScope]?.checkActive()
        context.ensureActive()
        val runner = runners.firstOrNull { it.localToolCapability == capability } ?: return block()
        if (closed) throw CancellationException("local tools closed")
        val limit = listOfNotNull(capMs, context[ToolDeadline]?.timeoutMs, timeoutMs).minOrNull()
        runner.onLocalToolRecovered = { onRecovered?.invoke() }
        runner.onLocalToolCancelled = { onStuck?.invoke(limit ?: 0L) }
        val parent = context[LocalToolExecutionScope]
        if (runner.localToolStuck) throw LocalToolTimeout(limit ?: 0L, 0L, false)
        // Composite sub-actions keep the original worker Job even in a NonCancellable block.
        if (limit == null || parent?.runner === runner) return block()
        val started = System.nanoTime()
        return when (val result = runner.runLocalTool(limit) {
            withContext(LocalToolExecutionScope(runner, currentCoroutineContext()[Job]!!)) { block() }
        }) {
            is LocalToolResult.Done -> result.value
            LocalToolResult.Stuck -> throw LocalToolTimeout(limit, (System.nanoTime() - started) / 1_000_000, false)
            LocalToolResult.TimedOut -> throw LocalToolTimeout(limit, (System.nanoTime() - started) / 1_000_000, true)
            is LocalToolResult.Failed -> throw result.error
        }
    }

    suspend fun awaitIdle() = runners.forEach { it.awaitLocalToolIdle() }

    fun close() {
        closed = true
        runners.forEach { it.closeLocalTools() }
    }
}

internal class LocalToolTimeout(val deadlineMs: Long, val durationMs: Long, timedOut: Boolean) : Exception("phone_execution_stuck") {
    val execution = ToolExecution(buildJsonObject {
        put("ok", false)
        put("error", "phone_execution_stuck")
        put("message", "Phone execution is isolated until its previous operation exits.")
        put("recoverable", true)
        put("counts_as_failure", true)
        put("timedOut", timedOut)
        put("screenReadStuck", true)
        put("declaredDeadlineMs", deadlineMs)
        put("actualDurationMs", durationMs)
    })
}