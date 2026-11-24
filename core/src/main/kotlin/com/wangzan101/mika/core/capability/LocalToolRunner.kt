package com.wangzan101.mika.core.capability

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive

/** Marks work already running inside a local tool's non-cancellable worker. */
internal class LocalToolExecutionScope(val runner: LocalToolRunner, private val worker: Job) : AbstractCoroutineContextElement(Key) {
    fun checkActive() = worker.ensureActive()
    companion object Key : CoroutineContext.Key<LocalToolExecutionScope>
}

/**
 * A capability's optional hard boundary for local tools that may block in native/non-cancellable code.
 * The tool engine depends only on this small contract, never on a concrete capability.
 */
internal interface LocalToolRunner {
    val localToolCapability: String
    val localToolStuck: Boolean
    val localToolBusy: Boolean
    var onLocalToolRecovered: (() -> Unit)?
    var onLocalToolCancelled: (() -> Unit)?

    suspend fun <T> runLocalTool(timeoutMs: Long, block: suspend () -> T): LocalToolResult<T>
    suspend fun awaitLocalToolIdle()
    fun closeLocalTools()
}

internal sealed interface LocalToolResult<out T> {
    data class Done<T>(val value: T) : LocalToolResult<T>
    data object TimedOut : LocalToolResult<Nothing>
    data object Stuck : LocalToolResult<Nothing>
    data class Failed(val error: Throwable) : LocalToolResult<Nothing>
}