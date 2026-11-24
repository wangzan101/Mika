package com.wangzan101.mika.task

import android.os.PowerManager
import android.os.SystemClock
import android.app.UiAutomation
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Runs the production PendingIntent receiver with no coroutine watchdog fallback. */
@RunWith(AndroidJUnit4::class)
class TaskDeadlineAlarmDeviceTest {
    @Test fun systemAlarmExpiresWithoutAProcessTimer() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MikaApp>()
        assumeTrue("exact-alarm access must be explicitly granted", TriggerRuntime.canScheduleExactly(app))
        assertFalse("do not interfere with an active user task", app.taskState.busy.value)
        val key = "deadline-test:" + UUID.randomUUID()
        val runtime = app.taskDeadlines
        val done = CompletableDeferred<Long>()
        val start = runtime.clock()
        val duration = InstrumentationRegistry.getArguments().getString("deadlineMs")?.toLongOrNull()?.coerceIn(2_000L, 600_000L) ?: 2_000L
        val sleep = InstrumentationRegistry.getArguments().getString("sleep") == "true"
        val interactive = app.getSystemService(PowerManager::class.java).isInteractive
        val automation = if (sleep) InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) else null
        val guard = runtime.start(TaskDeadlineEntry.start(key, duration, start)) {
            done.complete(SystemClock.elapsedRealtime())
        }
        try {
            assertEquals(DeadlineAlarmMode.Exact, runtime.alarmMode)
            if (sleep) automation!!.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
            val stoppedAt = withTimeout(duration + 30_000L) { done.await() }
            assertTrue("early alarm", stoppedAt >= start.elapsedMs + duration)
            assertTrue("alarm late by ${stoppedAt - start.elapsedMs - duration}ms", stoppedAt - start.elapsedMs - duration < 5_000L)
            try { guard.check(); fail("expired execution must be revoked") } catch (_: com.wangzan101.mika.core.run.RunDeadline.Exceeded) { }
        } finally {
            runtime.finish(key)
            if (sleep && interactive) automation!!.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        }
    }
}