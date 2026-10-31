package com.wangzan101.mika.cap.phone.shell

import android.os.Looper
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.exitProcess

/** 在真实 shell uid 下调用生产代码;不依赖已运行的助手是否还是旧版本。 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class ShellClipboardDeviceTest {
    @Test
    fun shellCanRoundTripChineseClipboardAndRestoreOriginal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val classpath = listOf(instrumentation.targetContext, instrumentation.context)
            .joinToString(":") { it.applicationInfo.sourceDir }
        val output = sh("CLASSPATH=$classpath app_process / ${ShellClipboardProbe::class.java.name} 2>&1")
        assertTrue(output, output.lineSequence().any { it == ShellClipboardProbe.PASSED })
    }
}

/** app_process 从被测 APK 加载生产代码,不依赖已运行的助手是否还是旧版本。 */
object ShellClipboardProbe {
    const val PASSED = "shell-clipboard-passed"

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            check(Process.myUid() == ShellContext.SHELL_UID)
            if (Looper.myLooper() == null) Looper.prepareMainLooper()
            val clipboard = ShellClipboard(ShellContext.create())
            val originalText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            val shell = ShellService(ShellContext.SHELL_UID)
            try {
                val text = "Mika 中文输入回归"
                val result = shell.setClipText(text)
                check(result == ShellService.CLIP_SET) { "setClipText returned $result" }
                check(clipboard.primaryClip?.getItemAt(0)?.text?.toString() == text)
            } finally {
                // 复用生产代码的保存/还原,不输出用户剪贴板内容。
                shell.restoreClip()
            }
            check(clipboard.primaryClip?.getItemAt(0)?.text?.toString() == originalText)
            println(PASSED)
            exitProcess(0)
        } catch (t: Throwable) {
            t.printStackTrace()
            exitProcess(1)
        }
    }
}
