package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunEventBus
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.run.RunOrigin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class McpTaskProtocolTest {
    internal class Tasks : McpTaskHost {
        val submitted = mutableListOf<RunSpec>()
        var message: String? = null
        var cancelled: RunId? = null
        override fun submit(spec: RunSpec): McpTaskLink {
            submitted += spec
            return McpTaskLink(spec.id, RunEventBus())
        }
        override fun status(id: RunId) = submitted.firstOrNull { it.id == id }?.let {
            buildJsonObject { put("task_id", id.value); put("status", "running") }
        }
        override suspend fun reply(id: RunId, text: String): Boolean { message = text; return true }
        override fun cancel(id: RunId): Boolean { cancelled = id; return true }
    }

    @Test fun negotiatesVersionsAndListsOnlyTaskTools() = runBlocking {
        val protocol = McpTaskProtocol(Tasks(), "test")
        for (version in McpTaskProtocol.VERSIONS + "unsupported") {
            val reply = protocol.receive("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$version"}}""")!!["result"]!!.jsonObject
            assertEquals(if (version == "unsupported") McpTaskProtocol.VERSIONS.first() else version, reply["protocolVersion"]!!.jsonPrimitive.content)
            assertTrue("tools" in reply["capabilities"]!!.jsonObject)
            assertFalse("resources" in reply["capabilities"]!!.jsonObject)
        }
        val tools = protocol.receive(request("tools/list"))!!["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(setOf("mika_task", "mika_status", "mika_reply", "mika_cancel"), tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet())
        assertTrue(tools.single { it.jsonObject["name"] == JsonPrimitive("mika_status") }.jsonObject["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.boolean)
    }

    @Test fun delegatesOnceAndPreservesMcpProvenanceAcrossPollingAndReplies() = runBlocking {
        val tasks = Tasks()
        val protocol = McpTaskProtocol(tasks, "test")
        val result = value(protocol.receive(call("mika_task", """{"goal":"总结今天的群聊"}"""))!!)
        val id = result["task_id"]!!.jsonPrimitive.content
        assertEquals(1, tasks.submitted.size)
        assertEquals(RunOrigin.Mcp, tasks.submitted.single().provenance.origin)
        assertEquals(Attendance.Reachable, tasks.submitted.single().provenance.attendance)
        repeat(3) { assertEquals("running", value(protocol.receive(call("mika_status", """{"task_id":"$id"}"""))!!)["status"]!!.jsonPrimitive.content) }
        assertEquals(1, tasks.submitted.size)
        protocol.receive(call("mika_reply", """{"task_id":"$id","message":"只看今天"}"""))
        assertEquals("只看今天", tasks.message)
        protocol.receive(call("mika_cancel", """{"task_id":"$id"}"""))
        assertEquals(RunId(id), tasks.cancelled)
    }

    @Test fun notificationsNeverStartTasks() = runBlocking {
        val tasks = Tasks()
        val protocol = McpTaskProtocol(tasks, "test")
        assertNull(protocol.receive("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertNull(protocol.receive("""{"jsonrpc":"2.0","method":"tools/call","params":{"name":"mika_task","arguments":{"goal":"do something"}}}"""))
        assertTrue(tasks.submitted.isEmpty())
    }

    @Test fun rejectsMalformedRequestsAndMissingArgumentsWithoutStartingWork() = runBlocking {
        val tasks = Tasks()
        val protocol = McpTaskProtocol(tasks, "test")
        assertEquals(-32700, errorCode(protocol.receive("{")))
        assertEquals(-32600, errorCode(protocol.receive("[]")))
        assertEquals(-32600, errorCode(protocol.receive("""{"jsonrpc":"1.0","id":1,"method":"ping"}""")))
        assertEquals(-32601, errorCode(protocol.receive(request("unknown"))))
        for (args in listOf("{}", """{"goal":1}""", """{"goal":" "}""", """{"goal":"buy","autonomous":true}""")) {
            assertEquals(-32602, errorCode(protocol.receive(call("mika_task", args))))
        }
        assertEquals(-32602, errorCode(protocol.receive(call("unknown", "{}"))))
        assertEquals(-32602, errorCode(protocol.receive("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":[]}""")))
        assertTrue(tasks.submitted.isEmpty())
    }

    @Test fun unknownHandlesAreToolErrorsAndDoNotReachOtherTasks() = runBlocking {
        val tasks = Tasks()
        val reply = McpTaskProtocol(tasks, "test").receive(call("mika_reply", """{"task_id":"another-conversation","message":"send"}"""))!!
        assertTrue(reply["result"]!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        assertNull(tasks.message)
    }

    companion object {
        internal fun request(method: String) = """{"jsonrpc":"2.0","id":"request-1","method":"$method"}"""
        internal fun call(name: String, args: String) = """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"$name","arguments":$args}}"""
        internal fun value(response: JsonObject): JsonObject = response["result"]!!.jsonObject.let {
            val structured = it["structuredContent"]!!.jsonObject
            assertEquals(structured, Json.parseToJsonElement(it["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content))
            structured
        }
        private fun errorCode(response: JsonObject?) = response!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.int
    }
}
