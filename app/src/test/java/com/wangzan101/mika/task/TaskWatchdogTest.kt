package com.wangzan101.mika.task

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskWatchdogTest {
    @Test
    fun timeoutSurvivesWorkerThenRecoversAfterSixtySeconds() = runTest {
        val events = mutableListOf<String>()
        var active = true
        val watchdog = TaskWatchdog(
            isWorkerActive = { active },
            onTimeout = { events += "timeout" },
            onStillStuck = { events += "stuck:$it" },
            recover = { events += "recover"; active = false; true },
            onRecovered = { events += "recovered" },
            onNeedsRestart = { events += "restart" },
        )

        watchdog.run(10_000)

        assertEquals(listOf("timeout", "stuck:30000", "recover", "recovered"), events)
    }

    @Test
    fun failedRecoveryRequestsARestartInsteadOfReleasingTheQuarantine() = runTest {
        val events = mutableListOf<String>()
        var attempts = 0
        val watchdog = TaskWatchdog(
            isWorkerActive = { true },
            onTimeout = { events += "timeout" },
            onStillStuck = { events += "stuck:$it" },
            recover = { attempts++; events += "recover"; false },
            onRecovered = { events += "recovered" },
            onNeedsRestart = { events += "restart" },
        )

        watchdog.run(1_000)

        assertEquals(2, attempts)
        assertEquals(listOf("timeout", "stuck:30000", "recover", "recover", "restart"), events)
    }

    @Test
    fun healthyConnectionDoesNotReleaseQuarantineWhileWorkerStillRuns() = runTest {
        val events = mutableListOf<String>()
        val watchdog = TaskWatchdog(
            isWorkerActive = { true },
            onTimeout = { events += "timeout" },
            onStillStuck = { events += "stuck:$it" },
            recover = { events += "recover"; true },
            onRecovered = { events += "recovered" },
            onNeedsRestart = { events += "restart" },
        )

        watchdog.run(1_000)

        assertEquals(listOf("timeout", "stuck:30000", "recover", "recover", "restart"), events)
    }
    @Test fun recoveryExceptionsCountAsFailedAttempts() = runTest {
        var attempts = 0
        var restart = false
        TaskWatchdog(
            isWorkerActive = { true }, onTimeout = {}, onStillStuck = {},
            recover = { attempts++; throw IllegalStateException("connection failed") },
            onRecovered = { error("worker has not exited") }, onNeedsRestart = { restart = true },
        ).recoverStuck()
        assertEquals(2, attempts)
        org.junit.Assert.assertTrue(restart)
    }

}