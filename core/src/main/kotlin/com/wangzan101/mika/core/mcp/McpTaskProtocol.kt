package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.longOrNull

/** 无会话的 MCP 任务入口。长任务立即返回编号,后续查询不会重新执行任务。 */
class McpTaskProtocol(private val tasks: McpTaskHost, private val version: String) {
    suspend fun receive(body: String): JsonObject? {
        val message = try { Json.parseToJsonElement(body) as? JsonObject }
        catch (_: Exception) { return error(JsonNull, -32700, "Parse error") }
            ?: return error(JsonNull, -32600, "Invalid request")
        val id = message["id"] ?: JsonNull
        val method = (message["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (message["jsonrpc"] != JsonPrimitive("2.0") || method == null ||
            (id != JsonNull && (id !is JsonPrimitive || (!id.isString && id.longOrNull == null)))) {
            return error(JsonNull, -32600, "Invalid request")
        }
        // 通知没有响应,也不能借没有 id 的 tools/call 悄悄执行动作。
        if ("id" !in message) return null
        if ("params" in message && message["params"] !is JsonObject) return error(id, -32602, "params must be an object")
        val params = message["params"] as? JsonObject ?: buildJsonObject {}
        return try {
            val result = when (method) {
                "initialize" -> buildJsonObject {
                    val requested = (params["protocolVersion"] as? JsonPrimitive)?.content
                    put("protocolVersion", requested?.takeIf { it in VERSIONS } ?: VERSIONS.first())
                    put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
                    put("serverInfo", buildJsonObject { put("name", "Mika"); put("version", version) })
                    put("instructions", "Delegate Android tasks with mika_task, then poll mika_status using task_id. " +
                        "Use mika_reply to answer a question or add instructions while the task runs. " +
                        "Use mika_cancel to stop it. Login, payment approval and takeover stay on the phone. " +
                        "Report the returned status and evidence; an accepted task is not a completed task.")
                }
                "ping" -> buildJsonObject {}
                "tools/list" -> buildJsonObject { put("tools", definitions) }
                "tools/call" -> call(params)
                else -> return error(id, -32601, "Method not found")
            }
            buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }
        } catch (e: InvalidParams) {
            error(id, -32602, e.message ?: "Invalid params")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            buildJsonObject {
                put("jsonrpc", "2.0"); put("id", id)
                put("result", content(buildJsonObject { put("error", "Task operation failed. Check Mika on the phone.") }, true))
            }
        }
    }

    private suspend fun call(params: JsonObject): JsonObject {
        val name = string(params, "name")
        if (name !in NAMES) throw InvalidParams("Unknown tool: $name")
        val args = params["arguments"] as? JsonObject ?: throw InvalidParams("arguments must be an object")
        val allowed = when (name) {
            "mika_task" -> setOf("goal")
            "mika_reply" -> setOf("task_id", "message")
            else -> setOf("task_id")
        }
        if (args.keys.any { it !in allowed }) throw InvalidParams("Unexpected argument")
        if (name == "mika_task") {
            val goal = string(args, "goal")
            val id = RunId(UUID.randomUUID().toString())
            tasks.submit(RunSpec(id, goal, id.value, provenance = RunProvenance(RunOrigin.Mcp, Attendance.Reachable)))
            return content(tasks.status(id) ?: buildJsonObject { put("task_id", id.value); put("status", "queued") })
        }
        val id = RunId(string(args, "task_id"))
        val status = tasks.status(id)
            ?: return content(buildJsonObject { put("error", "Unknown or expired task_id. Handles are local to this Mika process.") }, true)
        return when (name) {
            "mika_status" -> content(status)
            "mika_reply" -> {
                val accepted = tasks.reply(id, string(args, "message"))
                content(buildJsonObject { put("task_id", id.value); put("accepted", accepted) }, !accepted)
            }
            else -> {
                val requested = tasks.cancel(id)
                content(buildJsonObject { put("task_id", id.value); put("cancel_requested", requested) }, !requested)
            }
        }
    }

    private fun string(obj: JsonObject, key: String): String =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?: throw InvalidParams("$key must be a non-empty string")

    private class InvalidParams(message: String) : IllegalArgumentException(message)

    companion object {
        val VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26")
        private val NAMES = setOf("mika_task", "mika_status", "mika_reply", "mika_cancel")
        private val definitions = buildJsonArray {
            fun tool(name: String, description: String, fields: Map<String, String>, readOnly: Boolean = false) {
                add(buildJsonObject {
                    put("name", name); put("description", description)
                    put("inputSchema", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            fields.forEach { (key, desc) -> put(key, buildJsonObject { put("type", "string"); put("description", desc) }) }
                        })
                        put("required", JsonArray(fields.keys.map(::JsonPrimitive)))
                        put("additionalProperties", false)
                    })
                    put("annotations", buildJsonObject {
                        put("readOnlyHint", readOnly)
                        put("destructiveHint", !readOnly)
                        put("openWorldHint", true)
                    })
                })
            }
            val taskId = "Task handle returned by mika_task."
            tool("mika_task", "Start an Android task using Mika's configured model, apps, browser and sandbox. Returns immediately; poll mika_status for progress and the result.", mapOf("goal" to "What to do, including any limits or stopping point."))
            tool("mika_status", "Get a task's current status, progress, question or final result. Does not restart the task. Poll every few seconds while running.", mapOf("task_id" to taskId), readOnly = true)
            tool("mika_reply", "Answer a task's question or add instructions while it runs. Phone takeover and payment approval must be handled on the phone.", mapOf("task_id" to taskId, "message" to "Answer or additional instructions."))
            tool("mika_cancel", "Request cancellation of a running or queued task. Check mika_status for its final state; completed actions remain completed.", mapOf("task_id" to taskId))
        }

        private fun content(value: JsonObject, failed: Boolean = false) = buildJsonObject {
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", value.toString()) }) })
            put("structuredContent", value)
            put("isError", failed)
        }

        private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id)
            put("error", buildJsonObject { put("code", code); put("message", message) })
        }
    }
}
