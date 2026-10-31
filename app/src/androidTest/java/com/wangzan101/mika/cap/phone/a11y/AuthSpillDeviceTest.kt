package com.wangzan101.mika.cap.phone.a11y

import android.accounts.AccountManager
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.shell.MainScreenWatch
import com.wangzan101.mika.core.uistate.ActionResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机测试:测试包登记成账号认证器(见 TestAuthenticatorService),它的整屏登录页没有对话框主题、
 * 没有密码框、也没有 excludeFromRecents,形状上和普通 App 页面一样;只有「所在的包是账号认证器」这条
 * 能认出它要用户亲手处理,应该留在主屏,不能被挪到 Mika 的屏上。
 */
@RunWith(AndroidJUnit4::class)
class AuthSpillDeviceTest {
    // 和任务里一样,屏的尺寸、密度同主屏:挪过来的页面不必因尺寸变了重建
    private val agent = run {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val m = android.util.DisplayMetrics().also {
            @Suppress("DEPRECATION")
            ctx.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
        val shell = DeviceSupport.shell()
        val d = com.wangzan101.mika.cap.phone.display.AgentDisplay(shell, m.widthPixels, m.heightPixels, m.densityDpi)
        DeviceSupport.Agent(d, AndroidScreen(DeviceSupport.service(), shell, DeviceSupport.ready(d)))
    }
    private val screen get() = agent.screen
    private val shell = DeviceSupport.shell()

    @After
    fun tearDown() {
        val main = shell.topTaskId(Display.DEFAULT_DISPLAY)
        if (main >= 0 && shell.packageOfTask(main) == DeviceSupport.TEST_PACKAGE) DeviceSupport.sh("input keyevent 4")
        agent.release()
    }

    /** 测试包必须已经出现在系统的账号认证器表里,这条用例才有意义。 */
    private fun testAuthenticatorRegistered(): Boolean {
        val am = AccountManager.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val found = DeviceSupport.pollUntil(10_000) {
            runCatching { am.authenticatorTypes.any { it.packageName == DeviceSupport.TEST_PACKAGE } }.getOrDefault(false)
        }
        return found == true
    }

    @Test
    fun authenticatorFullScreenPageStaysOnTheMainScreen() = runBlocking<Unit> {
        assertTrue("the test package is not registered as an account authenticator", testAuthenticatorRegistered())
        screen.ensureDisplay()
        val vd = agent.display.displayId
        DeviceSupport.sh(
            "am start --display $vd -n ${DeviceSupport.TEST_PACKAGE}/${SpillTestActivity::class.java.name}" +
                " --es ${SpillTestActivity.EXTRA_AUTH} ${FullScreenAuthTestActivity::class.java.name}",
        )
        val s = DeviceSupport.pollUntil(5_000) {
            runBlocking { screen.snapshot() }.takeIf { it.elements.any { e -> e.name == "open auth" } }
        }
        assertNotNull("spill page did not come up", s)
        val vdBefore = shell.topTaskId(vd)
        val since = android.os.SystemClock.uptimeMillis()
        val button = s!!.elements.first { it.name == "open auth" }
        val r = screen.tapRef(button.ref, s.snapshotId)
        println("tap result: $r")

        // 服务的进程可能是冷的,弹窗会晚一两秒才出来;等它进了助手的记录
        val b = DeviceSupport.pollUntil(5_000) {
            val arr = shell.mainArrivals(since)
            val pkgs = arr?.getStringArray(MainScreenWatch.KEY_PACKAGE)
            if (pkgs?.contains(DeviceSupport.TEST_PACKAGE) == true) arr else null
        }
        assertNotNull("no arrival on the main screen was reported for the auth page", b)
        delay(500)
        val now = screen.snapshot()
        delay(500)
        val opened = now.opened ?: (r as? ActionResult.Ok)?.opened
        val stillThere = DeviceSupport.service().windowsOn(Display.DEFAULT_DISPLAY)
            .any { it.root?.packageName?.toString() == DeviceSupport.TEST_PACKAGE }
        println("after: opened=$opened stillOnMain=$stillThere vd top=${shell.topTaskId(vd)} (before $vdBefore)")
        assertTrue("the authenticator page was moved off the user's screen", stillThere)
        assertEquals("Mika's screen changed", vdBefore, shell.topTaskId(vd))
        assertNotNull("the auth page was not reported to the model", opened)
        assertTrue("the auth page was reported as pulled back: $opened",
            !opened!!.pulledBack && opened.onUserScreen && opened.needsUser)
    }
}
