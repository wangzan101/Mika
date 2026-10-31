package com.wangzan101.mika.mcp

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 开关和令牌只跟本次服务走;重启后需在手机上重新打开。 */
class McpConnection(private val context: Context) {
    data class State(val starting: Boolean = false, val token: String? = null, val error: String? = null) {
        val enabled get() = starting || token != null
    }

    private val current = MutableStateFlow(State())
    val state = current.asStateFlow()

    fun enable() {
        if (current.value.enabled) return
        current.value = State(starting = true)
        try { context.startForegroundService(Intent(context, McpService::class.java)) }
        catch (e: Exception) { current.value = State(error = e.message) }
    }

    fun disable() { context.stopService(Intent(context, McpService::class.java)) }
    internal fun opened(token: String) { current.value = State(token = token) }
    internal fun closed(error: String? = null) { current.value = State(error = error) }
}
