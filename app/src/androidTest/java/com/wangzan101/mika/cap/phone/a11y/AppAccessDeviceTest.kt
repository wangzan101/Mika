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
}
