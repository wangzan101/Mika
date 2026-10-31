package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.device.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class McpDeviceToolsTest {
    private var writes = 0
    private val store = object : DeviceAccessStore {
        override val enabled = true
        override val credentialConfigured = false
        override val attemptBlocked = false
        override fun credential(): DeviceCredential? = null
        override fun beginAttempt(credential: DeviceCredential) = false
        override fun completeAttempt(credential: DeviceCredential) = Unit
    }
    private val port = object : DeviceAccessPort {
        override suspend fun state() = DeviceState(true, false, false, false, true, true)
        override suspend fun wake(): Boolean { writes++; return true }
        override suspend fun dismiss(): Boolean { writes++; return true }
        override suspend fun credentialReady(kind: CredentialKind) = false
        override suspend fun enter(credential: DeviceCredential): Boolean { writes++; return false }
    }
    private val tools = McpTaskTools(FakeRemoteTasks(), device = DeviceAccess(port, store))

    @Test fun productionToolSetAddsOnlyThreeCredentialFreeDeviceTools() = runTest {
        assertEquals(9, tools.tools.size)
        assertTrue(tools.has("mika_device_state"))
        assertTrue(tools.has("mika_wake_device"))
        assertTrue(tools.has("mika_unlock_device"))
        val result = tools.call("mika_unlock_device", JsonObject(emptyMap()))
        assertEquals(JsonPrimitive("unlocked"), result["status"])
        assertEquals(0, writes)
        assertFalse(result.toString().contains("password"))
    }

    @Test fun credentialsAndUnknownArgumentsAreRejectedBeforeExecution() = runTest {
        for (name in listOf("mika_device_state", "mika_wake_device", "mika_unlock_device")) {
            try {
                tools.call(name, JsonObject(mapOf("pin" to JsonPrimitive("123456"))))
                fail("unexpected argument accepted")
            } catch (e: McpTaskTools.ToolError) { assertFalse(e.message!!.contains("123456")) }
        }
        assertEquals(0, writes)
    }

    @Test fun deviceToolsUseTheExistingTokenAndBrowserGuards() {
        val host = McpHost(FakeRemoteTasks(), { "test-token" }, 0, false, "test", device = DeviceAccess(port, store))
        val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"mika_unlock_device","arguments":{}}}""".toByteArray()
        fun call(token: String?, origin: String? = null): HttpResponse {
            val headers = mutableMapOf("content-type" to "application/json")
            token?.let { headers["authorization"] = "Bearer $it" }
            origin?.let { headers["origin"] = it }
            return host.handle(HttpRequest("POST", "/mcp", headers, body, java.net.InetAddress.getLoopbackAddress()))
        }
        assertEquals(401, call(null).status)
        assertEquals(401, call("incorrect").status)
        assertEquals(403, call("test-token", "https://example.com").status)
        assertEquals(200, call("test-token").status)
        assertEquals(0, writes)
    }
}
