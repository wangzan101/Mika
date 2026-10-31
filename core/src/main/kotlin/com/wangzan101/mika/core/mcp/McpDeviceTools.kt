package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.device.DeviceAccess
import com.wangzan101.mika.core.device.DeviceAccessResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** 主屏准备是独立的确定性流程。工具不接收凭据,凭据只能在手机设置。沿用 MCP 的认证门口。 */
internal class McpDeviceTools(private val device: DeviceAccess) {
    private val schema = buildJsonObject {
        put("type", "object"); put("properties", JsonObject(emptyMap()))
        put("required", buildJsonArray {}); put("additionalProperties", false)
    }
    val tools = listOf(
        McpTaskTools.Tool(STATE, "Read phone screen/lock state and whether local remote-unlock permission is enabled. No credentials are returned.", schema, true),
        McpTaskTools.Tool(WAKE, "Wake the main phone screen. Requires the phone's remote wake/unlock setting. Does not unlock it.", schema, false),
        McpTaskTools.Tool(UNLOCK, "Wake and unlock the phone using only permission and credentials configured locally on the phone. " +
            "Call before mika_run_task or when waiting_unlock. A failed credential attempt blocks further attempts until reconfigured on the phone. " +
            "Cannot approve payments or bypass authentication.", schema, false),
    )

    suspend fun call(name: String, args: JsonObject): JsonObject {
        if (args.isNotEmpty()) throw McpTaskTools.ToolError("Device tools accept no arguments; configure credentials on the phone.")
        val r = when (name) {
            STATE -> device.state()
            WAKE -> device.wake()
            UNLOCK -> device.unlock()
            else -> throw McpTaskTools.ToolError("Unknown tool: $name")
        }
        return describe(r)
    }

    private fun describe(r: DeviceAccessResult) = buildJsonObject {
        put("status", r.status); put("enabled", r.enabled)
        put("credential_configured", r.credentialConfigured); put("attempt_blocked", r.attemptBlocked)
        r.state?.let { s -> putJsonObject("device") {
            put("interactive", s.interactive); put("keyguard_showing", s.keyguardShowing)
            put("device_locked", s.deviceLocked); put("secure", s.secure)
            put("helper_available", s.helperAvailable); put("user_unlocked", s.userUnlocked); put("ready", s.ready)
        } }
    }

    companion object {
        const val STATE = "mika_device_state"
        const val WAKE = "mika_wake_device"
        const val UNLOCK = "mika_unlock_device"
    }
}
