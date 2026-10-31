package com.wangzan101.mika.cap.phone.shell

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.wangzan101.mika.core.device.CredentialKind
import com.wangzan101.mika.core.device.DeviceCredential

/** 只由已认证的 Mika Binder 调用。主屏专用;凭据从不进命令行、剪贴板、截图或日志。 */
internal class DeviceUnlockInput(context: Context, private val inject: (InputEvent) -> Boolean,
    private val command: (List<String>) -> Boolean) {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    fun wake(): Boolean = key(KeyEvent.KEYCODE_WAKEUP) || command(listOf("input", "keyevent", "224"))

    fun dismiss(): Boolean = command(listOf("wm", "dismiss-keyguard"))

    fun enter(kind: Int, bytes: ByteArray): Boolean {
        var chars = CharArray(0)
        val deadline = SystemClock.uptimeMillis() + 8_000
        try {
            if (!power.isInteractive || !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked) return false
            val type = CredentialKind.entries.getOrNull(kind) ?: return false
            if (bytes.any { it.toInt() !in 32..126 }) return false
            chars = CharArray(bytes.size) { bytes[it].toInt().toChar() }
            if (!DeviceCredential.valid(type, chars)) return false
            val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
            val events = if (type == CredentialKind.Password) map.getEvents(chars) ?: return false else null
            // 已由调用方确认 SystemUI 的凭据输入框。清掉可能残留的半截输入,只尝试一次完整凭据。
            if (type == CredentialKind.Pin) {
                repeat(16) { if (!key(KeyEvent.KEYCODE_DEL)) return false }
            } else if (!key(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON) || !key(KeyEvent.KEYCODE_DEL)) return false
            if (type == CredentialKind.Pin) {
                for (c in chars) {
                    if (SystemClock.uptimeMillis() > deadline || !keyguard.isKeyguardLocked || !power.isInteractive) return false
                    if (!key(KeyEvent.KEYCODE_0 + (c - '0'))) return false
                }
            } else {
                for (event in events!!) {
                    event.source = android.view.InputDevice.SOURCE_KEYBOARD
                    if (SystemClock.uptimeMillis() > deadline || !keyguard.isKeyguardLocked || !power.isInteractive || !inject(event)) return false
                }
            }
            // 某些系统在最后一位自动提交;不要在已经解锁的 App 上发送 Enter。
            SystemClock.sleep(200)
            return !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked || key(KeyEvent.KEYCODE_ENTER)
        } finally {
            bytes.fill(0); chars.fill('\u0000')
            // 密码里的符号可能用到修饰键;中断后也只释放虚拟键盘按键,不留下按住状态。
            for (code in intArrayOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT)) {
                val now = SystemClock.uptimeMillis()
                inject(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                    android.view.InputDevice.SOURCE_KEYBOARD))
            }
        }
    }

    private fun key(code: Int, meta: Int = 0): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
            android.view.InputDevice.SOURCE_KEYBOARD)
        return inject(down) && inject(KeyEvent.changeAction(down, KeyEvent.ACTION_UP))
    }
}
