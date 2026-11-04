package com.wangzan101.mika.core.cap.mcp

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.ConfirmDecision
import com.wangzan101.mika.core.action.Decision
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.observation.Trust
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpClientTest {
    private class FakeEndpoint : McpEndpoint {
        override val serverId = "demo"
        var offered = listOf(McpToolDescriptor("search", "Search", buildJsonObject { put("type", "object") }, McpAnnotations(readOnlyHint = true)))
        var result = McpResult("found")
        var calls = 0
        var offeredResources: List<McpResourceDescriptor> = emptyList()
        var offeredPrompts: List<McpPromptDescriptor> = emptyList()
        override fun tools() = offered
        override fun resources() = offeredResources
        override fun prompts() = offeredPrompts
        override suspend fun call(name: String, arguments: JsonObject): McpResult { calls++; return result }
        override suspend fun readResource(uri: String) = McpResult("resource:$uri")
        override suspend fun getPrompt(name: String, arguments: JsonObject) =
            McpResult("prompt:$name\n$arguments")
    }

    @Test fun annotationRiskDefaultsToWriteAndEscalatesOpenWorldAndDestructive() {
        assertEquals(ActionRisk.Change, mcpApprovalRisk(McpAnnotations()).level)
        assertEquals(ActionRisk.Read, mcpApprovalRisk(McpAnnotations(readOnlyHint = true)).level)
        assertEquals(ActionRisk.External, mcpApprovalRisk(McpAnnotations(readOnlyHint = true, openWorldHint = true)).level)
        assertEquals(ActionRisk.Irreversible, mcpApprovalRisk(McpAnnotations(destructiveHint = true)).level)
    }

    @Test fun runCatalogIsFrozenAndCapabilitySubsetFiltersMcp() {
        val endpoint = FakeEndpoint()
        val spec = RunSpec(RunId("r"), "task", "thread")
        val tools = toolDispatcher(FakeScreen(), runSpec = spec, mcpServers = listOf(McpConnection(endpoint)))
        val first = tools.definitions().map { it.name }
        assertTrue("mcp_demo__search" in first)
        assertEquals(ActionRisk.Change, tools.approvalRisk("mcp_demo__search")?.level)
        val trusted = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint, trust = McpTrust.TrustAnnotations)))
        assertEquals(ActionRisk.Read, trusted.approvalRisk("mcp_demo__search")?.level)
        endpoint.offered = listOf(McpToolDescriptor("changed", "Changed", buildJsonObject {}))
        assertEquals(first, tools.definitions().map { it.name })
        val subset = toolDispatcher(FakeScreen(), runSpec = spec.copy(capabilities = setOf("phone")), mcpServers = listOf(McpConnection(endpoint)))
        assertFalse(subset.definitions().any { it.name.startsWith("mcp_") })
    }

    @Test fun allowedToolsLimitsExposedTools() {
        val endpoint = FakeEndpoint().apply {
            offered = listOf(
                McpToolDescriptor("search", "Search", buildJsonObject { put("type", "object") }),
                McpToolDescriptor("write", "Write", buildJsonObject { put("type", "object") }),
            )
        }
        fun mcpNames(connection: McpConnection) =
            toolDispatcher(FakeScreen(), mcpServers = listOf(connection)).definitions().map { it.name }.filter { it.startsWith("mcp_") }
        assertEquals(listOf("mcp_demo__search", "mcp_demo__write"), mcpNames(McpConnection(endpoint)))
        assertEquals(listOf("mcp_demo__write"), mcpNames(McpConnection(endpoint, allowedTools = setOf("write"))))
        assertEquals(emptyList<String>(), mcpNames(McpConnection(endpoint, allowedTools = emptySet())))
    }

    @Test fun resourcesAndPromptsBecomeReadOnlyTools() = runBlocking {
        val endpoint = FakeEndpoint().apply {
            offeredResources = listOf(McpResourceDescriptor("file://a", "A", "first"))
            offeredPrompts = listOf(
                McpPromptDescriptor("summarize", "Summarize", listOf(McpPromptArgument("topic", "Topic", true))),
            )
        }
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
        val names = tools.definitions().map { it.name }
        assertTrue("mcp_demo__resource_read" in names)
        assertTrue("mcp_demo__prompt_get" in names)
        assertEquals(ActionRisk.Read, tools.approvalRisk("mcp_demo__resource_read")?.level)
        assertEquals(ActionRisk.Read, tools.approvalRisk("mcp_demo__prompt_get")?.level)

        val resource = tools.execute(
            Part.ToolCall("c", "mcp_demo__resource_read", """{"uri":"file://a"}"""),
            ActionPass(),
        )
        assertEquals("resource:file://a", resource.observations.single().text)
        assertEquals(Trust.Untrusted, resource.observations.single().trust)

        val prompt = tools.execute(
            Part.ToolCall("c", "mcp_demo__prompt_get", """{"name":"summarize","arguments":{"topic":"x"}}"""),
            ActionPass(),
        )
        assertTrue(prompt.observations.single().text.contains("prompt:summarize"))
    }

    @Test fun largeResultUsesReferenceAndUntrustedObservation() = runBlocking {
        val endpoint = FakeEndpoint().apply { result = McpResult("x".repeat(40)) }
        var saved: String? = null
        val store = McpResultStore { _, _, body -> saved = body; "result://one" }
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint, store, previewChars = 10)))
        val result = tools.execute(Part.ToolCall("c", "mcp_demo__search", "{}"), ActionPass())
        assertEquals("x".repeat(40), saved)
        assertEquals("result://one", result.content["fullResult"]?.toString()?.trim('"'))
        assertTrue(result.content["text"].toString().contains("<untrusted>"))
        assertEquals(Trust.Untrusted, result.observations.single().trust)
        assertEquals(10, result.observations.single().text.length)
    }

    @Test fun malformedInputSchemasAreNormalizedInsteadOfBreakingDispatch() {
        // #279: JSON Schema 允许/生成器可能产出的这几种写法,此前会在工具装配时抛
        // IllegalArgumentException,让所有任务在开局失败。
        val endpoint = FakeEndpoint()
        endpoint.offered = listOf(
            McpToolDescriptor("q1", "properties is null", buildJsonObject {
                put("type", "object"); put("properties", JsonNull)
            }),
            McpToolDescriptor("q2", "boolean property schema", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("q", true) })
            }),
            McpToolDescriptor("q3", "required as string", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("q", buildJsonObject { put("type", "string") }) })
                put("required", "q")
            }),
            McpToolDescriptor("q4", "required with non-string item", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("q", buildJsonObject { put("type", "string") }) })
                put("required", buildJsonArray {
                    add(JsonPrimitive(true)); add(JsonPrimitive("q"))
                })
            }),
            // properties 为 null 且 required 列了参数(#279 残留形态):required 指向的
            // 参数会被归一补回空宽松 schema,工具必须仍然可调用
            McpToolDescriptor("q5", "null properties with required", buildJsonObject {
                put("type", "object"); put("properties", JsonNull); put("required", "q")
            }),
            McpToolDescriptor("q6", "null properties with required array", buildJsonObject {
                put("type", "object"); put("properties", JsonNull)
                put("required", buildJsonArray { add(JsonPrimitive("q")) })
            }),
            // properties 是数组(非对象非 null)→ 按无参数处理
            McpToolDescriptor("q7", "properties as array", buildJsonObject {
                put("type", "object"); put("properties", buildJsonArray { add(JsonPrimitive("x")) })
            }),
            // required 显式 null → 忽略
            McpToolDescriptor("q8", "required null", buildJsonObject {
                put("type", "object"); put("properties", JsonNull); put("required", JsonNull)
            }),
            // required 里的空字符串不指向任何属性 → 按无效项丢弃
            McpToolDescriptor("q9", "required with empty string", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("q", buildJsonObject { put("type", "string") }) })
                put("required", buildJsonArray { add(JsonPrimitive("")); add(JsonPrimitive("q")) })
            }),
        )
        val defs = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
            .definitions().filter { it.name.startsWith("mcp_demo__q") }
        assertEquals(9, defs.size)
        fun paramsOf(name: String) = Json.parseToJsonElement(
            defs.first { it.name == "mcp_demo__$name" }.parametersJsonSchema,
        ).jsonObject
        // properties 不是对象 → 按无参数处理(spec 会自动追加 reason 字段,断言除它之外为空)
        assertEquals(listOf("reason"), paramsOf("q1")["properties"]?.jsonObject?.keys?.toList())
        // 布尔 schema 属性 → 空的宽松 schema
        assertEquals(JsonObject(emptyMap()), paramsOf("q2")["properties"]?.jsonObject?.get("q")?.jsonObject)
        // required 不是数组 → 忽略
        assertEquals(emptyList<String>(), paramsOf("q3")["required"]?.jsonArray?.map { it.jsonPrimitive.content })
        // required 里的非字符串项 → 丢弃,字符串项保留
        assertEquals(listOf("q"), paramsOf("q4")["required"]?.jsonArray?.map { it.jsonPrimitive.content })
        // q5:required 字符串形态随 properties:null 一起归一 → 无参数可调
        assertEquals(setOf("reason"), paramsOf("q5")["properties"]?.jsonObject?.keys)
        assertEquals(emptyList<String>(), paramsOf("q5")["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        // q6:幽灵 required 被补回空宽松 schema,工具可调用
        assertTrue("q" in (paramsOf("q6")["properties"]?.jsonObject?.keys ?: emptySet()))
        assertEquals(listOf("q"), paramsOf("q6")["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        // q7:properties 是数组 → 按无参数处理
        assertEquals(listOf("reason"), paramsOf("q7")["properties"]?.jsonObject?.keys?.toList())
        // q8:required 显式 null → 忽略
        assertEquals(emptyList<String>(), paramsOf("q8")["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList<String>())
        // q9:required 里的空字符串不指向任何属性 → 丢弃,有效项保留
        assertEquals(listOf("q"), paramsOf("q9")["required"]?.jsonArray?.map { it.jsonPrimitive.content })
    }

    @Test fun unattendedExternalMcpCallIsNotAskedAndNotCalledByTheJudge() = runBlocking {
        val endpoint = FakeEndpoint().apply {
            offered = listOf(McpToolDescriptor("send", "Send", buildJsonObject { put("type", "object") },
                McpAnnotations(openWorldHint = true)))
        }
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
        // 不花钱的对外调用:无人值守也直接做(只记审批),不在判定时就调服务端
        val decision = ConfirmDecision(tools, null, provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended))
            .judge("mcp_demo__send", buildJsonObject {}, Origin.Model(1))
        assertEquals(Decision.Clear, decision)
        assertEquals(0, endpoint.calls)
    }
}
