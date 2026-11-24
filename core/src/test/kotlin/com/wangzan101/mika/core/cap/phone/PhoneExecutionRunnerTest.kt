package com.wangzan101.mika.core.cap.phone

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneExecutionRunnerTest {
    @Test
    fun blockingToolReturnsAtDeadlineAndOnlyThisRunnerBecomesStuck() = runBlocking {
        val runner = PhoneExecutionRunner()
        val started = System.nanoTime()
        val outcome = runner.execute(50) {
            val end = System.nanoTime() + 500_000_000L
            while (System.nanoTime() < end) {
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    // A real Binder/native call may ignore interruption.
                }
            }
            "late"
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("blocked for ${elapsedMs}ms", elapsedMs < 350)
        assertTrue(outcome is PhoneExecutionRunner.Result.TimedOut)
        assertEquals(PhoneExecutionRunner.Result.Stuck, runner.execute(50) { "queued" })

        val other = PhoneExecutionRunner()
        assertEquals(PhoneExecutionRunner.Result.Done("ok"), other.execute(50) { "ok" })
        delay(550)
    }

    @Test
    fun cancellingTheCallerRegistersTheDetachedWorkerAsStuck() = runBlocking {
        var cancelled = false
        val runner = PhoneExecutionRunner(onCancelled = { cancelled = true })

        kotlinx.coroutines.withTimeoutOrNull(50) {
            runner.execute(1_000) {
                val end = System.nanoTime() + 500_000_000L
                while (System.nanoTime() < end) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                        // The underlying blocking call may ignore interruption.
                    }
                }
                "late"
            }
        }

        assertTrue(cancelled)
        assertTrue(runner.isStuck)
        delay(550)
    }

    @Test
    fun closeShutsDownTheWorkerThreadSoIdleRunnersDoNotAccumulate() = runBlocking {
        val before = Thread.getAllStackTraces().keys.count { it.name == "mika-phone-tool" }
        val runner = PhoneExecutionRunner()
        assertEquals(PhoneExecutionRunner.Result.Done("ok"), runner.execute(1_000) { "ok" })
        // Worker thread exists while the runner is live.
        assertTrue(Thread.getAllStackTraces().keys.any { it.name == "mika-phone-tool" })

        runner.close()
        // Give the executor a moment to tear its idle worker down.
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline &&
            Thread.getAllStackTraces().keys.count { it.name == "mika-phone-tool" } > before
        ) {
            delay(20)
        }
        val after = Thread.getAllStackTraces().keys.count { it.name == "mika-phone-tool" }
        assertEquals("closed runner's worker thread should be gone", before, after)
    }
}