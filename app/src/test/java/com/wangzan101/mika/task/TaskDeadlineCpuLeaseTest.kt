package com.wangzan101.mika.task

import org.junit.Assert.*
import org.junit.Test

class TaskDeadlineCpuLeaseTest {
    @Test fun absoluteDeadlineBoundsCpuHoldAndRepeatedChecksDoNotRenewIt() {
        var now = 1_000L
        val acquired = mutableListOf<Long>()
        var released = 0
        val lease = TaskDeadlineCpuLease({ now }, { acquired += it }, { released++ })
        lease.update(601_000)
        assertEquals(listOf(630_000L), acquired)
        now = 501_000
        repeat(10) { lease.update(601_000) }
        assertEquals(1, acquired.size)
        lease.update(null); lease.update(null)
        assertEquals(1, released)
    }

    @Test fun newAndCompletedConcurrentTasksAdjustCapAndPastDeadlineCannotReacquire() {
        var now = 1_000L
        val acquired = mutableListOf<Long>()
        var released = 0
        val lease = TaskDeadlineCpuLease({ now }, { acquired += it }, { released++ })
        lease.update(601_000)
        now = 101_000
        lease.update(901_000)
        lease.update(601_000)
        assertEquals(listOf(630_000L, 830_000L, 530_000L), acquired)
        now = 700_000
        lease.update(null)
        lease.update(601_000)
        assertEquals(3, acquired.size)
        assertEquals(1, released)
    }

    @Test fun failedAcquireCanRetryAndDoesNotPretendToHoldALease() {
        var attempts = 0
        val lease = TaskDeadlineCpuLease({ 1_000L }, { if (++attempts == 1) error("power service unavailable") }, {})
        try { lease.update(601_000); fail() } catch (_: IllegalStateException) { }
        lease.update(601_000)
        assertEquals(2, attempts)
    }
}
