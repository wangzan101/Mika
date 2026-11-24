package com.wangzan101.mika.task

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real Job cancellation plus the service's independent recovery/cleanup components. */
class TaskRecoveryIntegrationTest {
    @Test fun terminalIsPublishedBeforeNativeExitAndHealthyProbeCannotReleaseItsResources() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CompletableDeferred<Unit>()
        val nativeExited = AtomicBoolean(false)
        val resourcesReleased = AtomicBoolean(false)
        val terminal = AtomicBoolean(false)
        val recoveryAttempts = AtomicInteger()
        val needsRestart = AtomicBoolean(false)
        val quarantine = PhoneQuarantine()
        val native = launch(Dispatchers.Default) {
            started.countDown()
            try {
                while (true) try { if (release.await(5, TimeUnit.MILLISECONDS)) break } catch (_: InterruptedException) { }
            } finally { nativeExited.set(true); finished.complete(Unit) }
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val task = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { finished.await(); resourcesReleased.set(true) } }
        }
        task.invokeOnCompletion { quarantine.remove("run-a") }
        terminal.set(true)
        quarantine.add("run-a")
        quarantine.add("run-b")
        task.cancel()
        val recovery = launch {
            TaskWatchdog(
                isWorkerActive = { !nativeExited.get() || !task.isCompleted },
                onTimeout = {}, onStillStuck = {},
                recover = { recoveryAttempts.incrementAndGet(); true },
                onRecovered = { quarantine.remove("run-a") },
                onNeedsRestart = { needsRestart.set(true) },
                stuckAfterMs = 1, recoverAfterMs = 2, wait = { yield() },
            ).recoverStuck()
        }
        try {
            withTimeout(2000) { recovery.join() }
            assertTrue(terminal.get())
            assertFalse(task.isActive)
            assertFalse(task.isCompleted)
            assertFalse(resourcesReleased.get())
            assertTrue(quarantine.blocked())
            assertEquals(setOf("run-a", "run-b"), quarantine.snapshot())
            assertEquals(2, recoveryAttempts.get())
            assertTrue(needsRestart.get())
        } finally { release.countDown(); withTimeout(2000) { native.join(); task.join() } }
        assertTrue(resourcesReleased.get())
        assertEquals(setOf("run-b"), quarantine.snapshot())
        quarantine.remove("run-b")
        assertFalse(quarantine.blocked())
    }
}