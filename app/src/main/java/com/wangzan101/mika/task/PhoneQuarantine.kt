package com.wangzan101.mika.task

/** Phone executions that timed out but have not confirmed termination. */
internal class PhoneQuarantine {
    private val stuck = linkedSetOf<String>()

    @Synchronized fun add(runKey: String) {
        if (runKey.isNotBlank()) stuck += runKey
    }

    @Synchronized fun remove(runKey: String) {
        stuck -= runKey
    }

    @Synchronized fun blocked(): Boolean = stuck.isNotEmpty()

    @Synchronized fun snapshot(): Set<String> = stuck.toSet()
}