package com.wangzan101.mika.settings

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.core.shell.PhoneBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「手机操作方式」:经本机无线调试(默认)还是 Shizuku。非秘密,SharedPreferences 就够;
 * 后端选择是手机操作这一域的顶层决定,和 adb 平台私有的 paired/usbStarted、功能开关分开存。
 */
class PhoneBackendSettings(private val prefs: SharedPreferences) {
    private val _backend = MutableStateFlow(read())
    /** 当前选的后端,之后每次改动再发一次。 */
    val backend: StateFlow<PhoneBackend> = _backend.asStateFlow()

    fun setBackend(backend: PhoneBackend) {
        prefs.edit().putString(BACKEND, backend.name).commit()
        _backend.value = backend
    }

    private fun read(): PhoneBackend =
        prefs.getString(BACKEND, null)?.let { n -> PhoneBackend.entries.firstOrNull { it.name == n } } ?: PhoneBackend.Wireless

    companion object {
        private const val PREFS = "phone_backend"
        private const val BACKEND = "backend"

        /** 产品里的那份(由 [com.wangzan101.mika.cap.phone.PhoneServices] 建)读写的偏好文件。 */
        fun prefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
