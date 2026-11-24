package com.wangzan101.mika.core.run

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A host's absolute, monotonic deadline; retained across detached workers and NonCancellable. */
class RunDeadline(
    private val remainingMs: () -> Long,
    private val onExpired: () -> Unit,
) : AbstractCoroutineContextElement(Key) {
    private val expired = AtomicBoolean(false)

    fun expire() {
        if (expired.compareAndSet(false, true)) {
            try { onExpired() } catch (e: Exception) { expired.set(false); throw e }
        }
    }

    fun check() {
        if (!expired.get() && remainingMs() <= 0L) expire()
        if (expired.get()) throw Exceeded()
    }

    class Exceeded : CancellationException("task deadline exceeded")
    companion object Key : CoroutineContext.Key<RunDeadline> {
        suspend fun checkCurrent() { currentCoroutineContext()[Key]?.check() }
    }
}