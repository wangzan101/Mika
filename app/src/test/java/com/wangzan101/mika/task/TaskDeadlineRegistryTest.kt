package com.wangzan101.mika.task

import com.wangzan101.mika.core.run.RunDeadline
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TaskDeadlineRegistryTest {
    @get:Rule val temp = TemporaryFolder()
    private var time = DeadlineTime(10_000, 1_000, "boot-a")
    private var scheduled: Long? = null
    private var mode = DeadlineAlarmMode.Exact
    private val expired = mutableListOf<String>()
    private val file by lazy { temp.root.resolve("deadlines.json") }
    private fun registry() = TaskDeadlineRegistry(TaskDeadlineStore(file), { time }, {
        scheduled = it
        if (it == null) DeadlineAlarmMode.None else mode
    }, { expired += it.runKey })
    private fun entry(id: String, duration: Long = 600_000) = TaskDeadlineEntry.start(id, duration, time)

    @Test fun systemAlarmFiresWhileCoroutineSchedulerHasNotAdvanced() {
        val r = registry()
        var calls = 0
        val guard = r.start(entry("one")) { calls++; r.finish("one") }
        assertEquals(601_000L, scheduled)
        assertEquals(DeadlineAlarmMode.Exact, r.alarmMode)
        // No coroutine delay is run: simulate elapsedRealtime advancing through deep sleep.
        time = time.copy(wallMs = 610_000, elapsedMs = 601_000)
        r.fire(); r.fire()
        assertEquals(1, calls)
        assertNull(scheduled)
        try { guard.check(); fail("expired actions must not resume") } catch (_: RunDeadline.Exceeded) { }
    }

    @Test fun manyRunsUseEarliestAlarmAndStaleDeliveryCannotExpireANewRun() {
        val r = registry()
        r.start(entry("late", 80)) { expired += "late"; r.finish("late") }
        r.start(entry("early", 20)) { expired += "early"; r.finish("early") }
        assertEquals(1020L, scheduled)
        time = time.copy(elapsedMs = 1020)
        r.fire()
        assertEquals(listOf("early"), expired)
        assertEquals(1080L, scheduled)
        r.finish("late")
        r.start(entry("next", 500)) { expired += "next" }
        r.fire()
        assertEquals(listOf("early"), expired)
        assertEquals(1520L, scheduled)
    }

    @Test fun sameBootIgnoresWallClockChangesAndRebootUsesWallDeadline() {
        val e = entry("one", 100)
        assertEquals(90L, e.remaining(time.copy(wallMs = 99_000_000, elapsedMs = 1010)))
        assertEquals(90L, e.remaining(time.copy(wallMs = 0, elapsedMs = 1010)))
        assertEquals(0L, e.remaining(DeadlineTime(10_101, 1, "boot-b")))
        assertEquals(50L, e.remaining(DeadlineTime(10_050, 1, "boot-b")))
    }

    @Test fun restartPersistsExpiredReasonButNeverRestartsUnexpiredWork() {
        val r = registry()
        r.start(entry("expired", 100)) { fail("previous process callback must not run") }
        r.start(entry("unfinished", 1000)) { fail("must not resume") }
        time = time.copy(elapsedMs = 1200, wallMs = 10_200)
        val reopened = registry()
        reopened.recover(); reopened.recover()
        assertEquals(listOf("expired"), expired)
        assertTrue(TaskDeadlineStore(file).all().isEmpty())
        assertNull(scheduled)
    }

    @Test fun earlyDeliveryAndPermissionLossNeverExtendTheDeadline() {
        val r = registry()
        var calls = 0
        val guard = r.start(entry("one", 100)) { calls++; r.finish("one") }
        r.fire()
        assertEquals(0, calls)
        mode = DeadlineAlarmMode.Inexact
        r.rearm()
        assertEquals(DeadlineAlarmMode.Inexact, r.alarmMode)
        assertEquals(1100L, scheduled)
        time = time.copy(elapsedMs = 1101)
        try { guard.check(); fail("late alarm must not allow another action") } catch (_: RunDeadline.Exceeded) { }
        assertEquals(1, calls)
    }

    @Test fun savedRecordsContainOnlyDeadlineMetadataAndCompletedRunsAreRemoved() {
        val r = registry()
        r.start(entry("one")) { }
        assertEquals(1, TaskDeadlineStore(file).all().size)
        assertFalse(file.readText().contains("goal"))
        r.finish("one")
        assertTrue(TaskDeadlineStore(file).all().isEmpty())
        registry().recover()
        assertTrue(expired.isEmpty())
    }

    @Test fun explicitQuitCancelsAlarmsAndResumeRearmsTheOriginalDeadline() {
        val r = registry()
        r.start(entry("one", 100)) { }
        r.suspendAlarms()
        assertNull(scheduled)
        assertEquals(1, TaskDeadlineStore(file).all().size)
        time = time.copy(elapsedMs = 1050)
        r.rearm()
        assertEquals(1100L, scheduled)
    }

    @Test fun corruptedMetadataIsPreservedWithoutReplayingAnything() {
        file.writeText("[broken")
        registry().recover()
        assertEquals("[broken", file.resolveSibling(file.name + ".corrupt").readText())
        assertTrue(expired.isEmpty())
    }

    @Test fun oneFailedPublicationDoesNotLoseOtherSimultaneousDeadlines() {
        val r = registry()
        var attempts = 0
        r.start(entry("retry", 100)) {
            if (++attempts == 1) throw IllegalStateException("temporary write failure")
            expired += "retry"
            r.finish("retry")
        }
        r.start(entry("other", 100)) { expired += "other"; r.finish("other") }
        time = time.copy(elapsedMs = 1100)
        try { r.fire(); fail("publication failed") } catch (_: IllegalStateException) { }
        assertEquals(listOf("other"), expired)
        r.fire()
        assertEquals(listOf("other", "retry"), expired)
        assertTrue(TaskDeadlineStore(file).all().isEmpty())
    }
    @Test fun aQueuedAlarmCannotPublishOrRearmAfterExplicitQuit() {
        val r = registry()
        var calls = 0
        r.start(entry("one", 100)) { calls++; r.finish("one") }
        r.suspendAlarms()
        time = time.copy(elapsedMs = 1200)
        r.fire()
        r.rearm { false }
        assertEquals(0, calls)
        assertNull(scheduled)
        assertEquals(DeadlineAlarmMode.None, r.alarmMode)
        // Reopening the app applies the original deadline without extending it.
        r.rearm { true }
        r.fire()
        assertEquals(1, calls)
        assertNull(scheduled)
    }

    @Test fun cpuLeaseTracksOnlyLiveRunsAndIsReleasedByCompletionExpiryAndQuit() {
        var cpuDue: Long? = null
        val r = TaskDeadlineRegistry(TaskDeadlineStore(file), { time }, { DeadlineAlarmMode.Exact }, {}, { cpuDue = it })
        r.start(entry("short", 100)) { r.finish("short") }
        r.start(entry("long", 500)) { r.finish("long") }
        assertEquals(1500L, cpuDue)
        r.finish("long")
        assertEquals(1100L, cpuDue)
        r.suspendAlarms()
        assertNull(cpuDue)
        r.rearm()
        assertEquals(1100L, cpuDue)
        time = time.copy(elapsedMs = 1100)
        r.fire()
        assertNull(cpuDue)
        r.start(entry("last", 500)) {}
        r.finish("last")
        assertNull(cpuDue)
    }

    @Test fun persistedOrphansDoNotAcquireLiveCpuLeases() {
        val store = TaskDeadlineStore(file)
        store.put(entry("orphan", 100))
        val calls = mutableListOf<Long?>()
        val r = TaskDeadlineRegistry(store, { time }, { DeadlineAlarmMode.Exact }, {}, { calls += it })
        r.rearm()
        assertEquals(listOf<Long?>(null), calls)
        r.recover()
        assertTrue(calls.all { it == null })
    }

}