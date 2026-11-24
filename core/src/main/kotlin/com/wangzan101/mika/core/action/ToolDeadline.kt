package com.wangzan101.mika.core.action

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Wall-clock deadline propagated from the action entry to potentially blocking local tools. */
internal data class ToolDeadline(val timeoutMs: Long) : AbstractCoroutineContextElement(Key) {
    init {
        require(timeoutMs > 0)
    }

    companion object Key : CoroutineContext.Key<ToolDeadline>
}