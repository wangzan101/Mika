package com.wangzan101.mika.cap.phone.a11y

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.core.access.AppAccessDenied
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppAccessDeviceTest {
    @Test fun blockedLaunchIsDeniedWhileShellRemainsAvailable() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MikaApp
        val pkg = "com.android.settings"
        val original = app.appAccess.current()
        val script = "/sdcard/Android/data/${app.packageName}/files/usb-start.sh"
        DeviceSupport.sh("sh $script")
        val shell = DeviceSupport.shell()
        val display = AgentDisplay(shell, 720, 1600, 320)
        try {
            val screen = AndroidScreen(DeviceSupport.service(), shell, DeviceSupport.ready(display), appAccess = app.appAccess)
            app.appAccess.setBlocked(pkg, true)
            try { screen.openApp(pkg); fail("Blacklisted launch must be denied") }
            catch (e: AppAccessDenied) { assertEquals("app_blocked", e.code) }
            val command = screen.systemCommand("printf blacklist-shell-ok")
            assertEquals(0, command?.exitCode)
            assertTrue(command?.output?.contains("blacklist-shell-ok") == true)
            app.appAccess.setBlocked(pkg, false)
            assertNull(app.appAccess.current().denial(pkg))
        } finally {
            app.appAccess.setBlocked(pkg, pkg in original.blocked)
            app.appAccess.setShareAllowed(pkg, pkg in original.shareAllowed)
            display.release()
        }
    }

    /**
     * 从没设置过的应用默认允许:能打开、能读屏。任务进行中把它设为禁用,下一次读屏立刻被拒;
     * 再允许后又能读 —— 权限每次都读最新状态,不用重启任务。
     */
    @Test fun blockingWhileRunningStopsTheNextRead() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MikaApp
        val pkg = "com.android.settings"
        val original = app.appAccess.current()
        DeviceSupport.sh("sh /sdcard/Android/data/${app.packageName}/files/usb-start.sh")
        val shell = DeviceSupport.shell()
        val display = AgentDisplay(shell, 720, 1600, 320)
        try {
            val screen = AndroidScreen(DeviceSupport.service(), shell, DeviceSupport.ready(display), appAccess = app.appAccess)
            app.appAccess.setBlocked(pkg, false)
            app.appAccess.setShareAllowed(pkg, false)
            assertNull("a package nobody configured must be allowed", app.appAccess.current().denial(pkg))
            screen.openApp(pkg)
            assertTrue("the app must be readable while allowed", screen.previewAllowed())
            screen.checkScreen()

            app.appAccess.setBlocked(pkg, true)
            try { screen.checkScreen(); fail("Blocked app must not be read mid-task") }
            catch (e: AppAccessDenied) { assertEquals("app_blocked", e.code) }
            assertFalse(screen.previewAllowed())

            app.appAccess.setBlocked(pkg, false)
            screen.checkScreen()
            assertTrue("allowing again must take effect on the next call", screen.previewAllowed())
        } finally {
            app.appAccess.setBlocked(pkg, pkg in original.blocked)
            app.appAccess.setShareAllowed(pkg, pkg in original.shareAllowed)
            display.release()
        }
    }
}
