package com.wangzan101.mika.task

import android.app.KeyguardManager
import android.os.Looper
import com.wangzan101.mika.cap.phone.shell.ShellContext
import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.system.exitProcess

/** 手动真机探针:通过 app_process 加载 APK,无需覆盖用户安装。仅用于无安全锁的测试手机。 */
object UnlockGateDeviceProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        var watcher: DeviceStateWatcher? = null
        var canWake = false
        var exitCode = 0
        try {
            Looper.prepareMainLooper()
            val context = ShellContext.create()
            check(!context.getSystemService(KeyguardManager::class.java).isKeyguardSecure) {
                "Use a test device without a secure keyguard"
            }
            canWake = true
            runBlocking {
                sh("input", "keyevent", "223")
                val states = DeviceStateWatcher(context) { -1 }.also { watcher = it }
                withTimeout(5_000) { states.currentLockState.first { it } }
                val held = MutableStateFlow(false)
                val gate = TaskGate(states.currentLockState, MutableStateFlow(true)) { true }
                val operation = async {
                    gate.pass { if (it is TaskState.Held) held.value = true }
                }
                withTimeout(5_000) { held.first { it } }
                check(!operation.isCompleted)
                sh("input", "keyevent", "224")
                sh("wm", "dismiss-keyguard")
                withTimeout(5_000) { operation.await() }
                check(!states.currentLockState.first())
                // 主线程未运行 Looper,广播回调没有机会刷新缓存;门禁仍须按真实状态恢复。
                check(states.locked.value)
                println("unlock-gate-passed-without-broadcast")
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            exitCode = 1
        } finally {
            watcher?.close()
            if (canWake) {
                sh("input", "keyevent", "224")
                sh("wm", "dismiss-keyguard")
            }
        }
        exitProcess(exitCode)
    }

    private fun sh(vararg command: String) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
    }
}
