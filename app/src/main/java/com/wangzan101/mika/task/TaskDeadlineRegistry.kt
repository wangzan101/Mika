package com.wangzan101.mika.task

import com.wangzan101.mika.core.run.RunDeadline

internal enum class DeadlineAlarmMode { Exact, Inexact, Unavailable, None }

/** One earliest-due alarm covers every run, avoiding PendingIntent collisions. System idle alarm quotas still apply. */
internal class TaskDeadlineRegistry(
    private val store: TaskDeadlineStore,
    private val now: () -> DeadlineTime,
    private val schedule: (elapsedDueMs: Long?) -> DeadlineAlarmMode,
    private val abandoned: (TaskDeadlineEntry) -> Unit,
    private val keepCpuUntil: (elapsedDueMs: Long?) -> Unit = {},
) {
    private val lock = Any()
    private val guards = linkedMapOf<String, RunDeadline>()
    private val delivered = hashSetOf<String>()
    @Volatile var alarmMode = DeadlineAlarmMode.None
        private set
    private var suspended = false

    fun start(entry: TaskDeadlineEntry, onExpired: () -> Unit): RunDeadline = synchronized(lock) {
        check(entry.runKey !in guards) { "deadline already registered" }
        val guard = RunDeadline({ entry.remaining(now()) }, onExpired)
        store.put(entry)
        guards[entry.runKey] = guard
        rearmLocked()
        guard
    }

    fun finish(key: String) = synchronized(lock) {
        store.remove(key)
        guards.remove(key)
        delivered.remove(key)
        rearmLocked()
    }

    /** PendingIntent identity is not authority to stop a run: always re-read its persisted deadline. */
    fun fire() {
        val due = synchronized(lock) {
            if (suspended) return
            val clock = now()
            store.all().filter { it.runKey !in delivered && it.remaining(clock) == 0L }.map {
                delivered += it.runKey
                it to guards[it.runKey]
            }.also { rearmLocked() }
        }
        var failure: Exception? = null
        for ((entry, guard) in due) {
            try {
                if (guard != null) guard.expire() else { abandoned(entry); finish(entry.runKey) }
            } catch (e: Exception) {
                synchronized(lock) { delivered -= entry.runKey }
                failure?.addSuppressed(e) ?: run { failure = e }
            }
        }
        synchronized(lock) { rearmLocked() }
        failure?.let { throw it }
    }

    /** Process restart never resumes old actions. Expired runs retain TimeLimit attribution. */
    fun recover() {
        for (entry in store.all()) {
            if (synchronized(lock) { entry.runKey in guards }) continue
            if (entry.remaining(now()) == 0L) abandoned(entry)
            finish(entry.runKey)
        }
    }

    fun rearm(allowed: () -> Boolean = { true }) = synchronized(lock) {
        // Check under the same lock as suspendAlarms: a queued refresh cannot undo explicit quit.
        if (!allowed()) return@synchronized
        suspended = false
        rearmLocked()
    }
    fun suspendAlarms() = synchronized(lock) { suspended = true; rearmLocked() }

    private fun rearmLocked() {
        if (suspended) { keepCpuUntil(null); alarmMode = schedule(null); return }
        val clock = now()
        val entries = store.all()
        // Only live, time-bounded runs hold CPU awake. Persisted orphans rely on the system alarm.
        // Keep the lease through expiry publication; finish removes it after terminal ownership.
        val liveDue = entries.filter { it.runKey in guards }.maxOfOrNull {
            if (it.boot == clock.boot) it.elapsedDueMs else clock.elapsedMs + it.remaining(clock)
        }
        keepCpuUntil(liveDue)
        val remaining = entries.filter { it.runKey !in delivered }.minOfOrNull { it.remaining(clock) }
        alarmMode = schedule(remaining?.let { clock.elapsedMs + it })
    }
}
