package com.wangzan101.mika.task

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.run.RunDeadline
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunPhase
import com.wangzan101.mika.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** System wakeup and durable deadline ownership live outside the task service's Job. */
class TaskDeadlineRuntime internal constructor(private val app: MikaApp) {
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val boot by lazy {
        Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }?.toString()
            ?: "unknown:${android.os.Process.myPid()}:${SystemClock.elapsedRealtime()}"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cpuWake by lazy {
        app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mika:task-deadline")
            .apply { setReferenceCounted(false) }
    }
    private val cpuLease = TaskDeadlineCpuLease(SystemClock::elapsedRealtime,
        acquire = { cpuWake.acquire(it) }, release = { if (cpuWake.isHeld) cpuWake.release() })
    private val registry = TaskDeadlineRegistry(TaskDeadlineStore(File(app.filesDir, "task-deadlines-v1.json")),
        ::clock, ::schedule, ::expiredAfterRestart, ::keepCpuUntil)

    internal fun clock() = DeadlineTime(System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot)
    internal val alarmMode: DeadlineAlarmMode get() = registry.alarmMode

    internal fun start(entry: TaskDeadlineEntry, expired: () -> Unit): RunDeadline = registry.start(entry, expired)
    fun finish(key: String) {
        // Metadata I/O must not abort the caller before it cancels the expired task's workers.
        runCatching { registry.finish(key) }.onFailure { Log.e(TAG, "deadline cleanup failed", it) }
    }

    /** Called before generic interrupted-run recovery can discard the timeout reason. */
    fun recoverBeforeRuns() = registry.recover()

    fun checkAndRearm() {
        if (com.wangzan101.mika.settings.FeatureFlags.get(app).quitRequested()) return
        scope.launch { runCatching { dispatchDue() }.onFailure { Log.e(TAG, "deadline refresh failed", it) } }
    }
    fun suspendAlarms() = registry.suspendAlarms()
    internal fun dispatchDue() {
        if (com.wangzan101.mika.settings.FeatureFlags.get(app).quitRequested()) return
        registry.fire()
        registry.rearm { !com.wangzan101.mika.settings.FeatureFlags.get(app).quitRequested() }
    }

    private fun keepCpuUntil(due: Long?) {
        // Screen may sleep. Holding only CPU prevents a live watchdog from losing the budget to
        // suspend; AlarmManager remains necessary for process death and OS/OEM scheduling limits.
        runCatching { cpuLease.update(due) }.onFailure { Log.e(TAG, "deadline CPU lease unavailable", it) }
    }

    private fun pending(): PendingIntent = PendingIntent.getBroadcast(app, 0,
        Intent(app, TaskDeadlineReceiver::class.java).setAction(ACTION).setData(Uri.parse("mika://task-deadline")),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun schedule(due: Long?): DeadlineAlarmMode {
        val intent = pending()
        alarms.cancel(intent)
        if (due == null) return DeadlineAlarmMode.None
        val at = due.coerceAtLeast(SystemClock.elapsedRealtime() + 1L)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intent)
                return DeadlineAlarmMode.Exact
            }
        } catch (e: RuntimeException) {
            // Permission or the system alarm service can become unavailable after the check.
            Log.w(TAG, "exact deadline alarm unavailable", e)
        }
        return try {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intent)
            Log.w(TAG, "exact alarms unavailable; deadline wakeup is inexact, admission checks remain active")
            DeadlineAlarmMode.Inexact
        } catch (e: RuntimeException) {
            Log.e(TAG, "deadline alarm unavailable; admission checks remain active", e)
            DeadlineAlarmMode.Unavailable
        }
    }

    private fun expiredAfterRestart(entry: TaskDeadlineEntry) {
        val run = app.runStore.get(RunId(entry.runKey)) ?: return
        if (run.phase == RunPhase.Ended || run.phase == RunPhase.Finishing || run.phase == RunPhase.Aftercare) return
        val reason = reason(app, entry)
        val end = TaskState.Failed(reason, FailKind.TimeLimit)
        val at = System.currentTimeMillis()
        run.recordId?.let { id ->
            val records = com.wangzan101.mika.record.RunStore(app)
            val terminal = records.events(id).filterIsInstance<RunEvent.TaskEnded>().lastOrNull()
            // A crash between normal completion and delivery must not turn success into timeout.
            if (terminal != null && terminal.kind != "timelimit") return
            if (terminal == null) records.append(id, RunEvent.TaskEnded(at, "failed", reason, "timelimit"))
        }
        entry.remoteId?.let { app.remoteTasks.ended(it, end, emptyList(), run.recordId) }
        val turnId = run.recordId ?: "deadline:${entry.runKey}"
        if (app.conversations.turns(run.threadId).none { it.runId == turnId }) {
            app.conversations.append(run.threadId, Turn(run.userText ?: run.goal, reason, "failed", runId = turnId))
        }
        // Existing recovery handles delivery to the original channel without replaying any tools.
        app.runStore.save(run.copy(phase = RunPhase.Interrupted, reply = reason,
            deliveryId = "deadline:${entry.runKey}", roundInProgress = false, runSucceeded = false))
    }

    companion object {
        internal const val ACTION = "com.wangzan101.mika.task.DEADLINE"
        private const val TAG = "MikaDeadline"
        internal fun reason(context: Context, entry: TaskDeadlineEntry): String = tr(
            if (entry.triggered) R.string.task_trigger_time_limit else R.string.task_run_time_limit,
            (entry.budgetMs / 60_000L).coerceAtLeast(1L).toInt(),
        )
    }
}

/** A persisted PendingIntent can recreate the app. Receiver work owns a short CPU-only wake lock. */
class TaskDeadlineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TaskDeadlineRuntime.ACTION) return
        if (com.wangzan101.mika.settings.FeatureFlags.get(context).quitRequested()) return
        val pending = goAsync()
        val wake = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mika:deadline")
        wake.acquire(30_000L)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { (context.applicationContext as MikaApp).taskDeadlines.dispatchDue() }
            catch (e: Exception) { Log.e("MikaDeadline", "deadline delivery failed", e) }
            finally { if (wake.isHeld) wake.release(); pending.finish() }
        }
    }
}
