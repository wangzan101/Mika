package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.adb.OwnListeningPorts
import com.wangzan101.mika.core.mcp.McpTaskProtocolTest.Companion.call
import com.wangzan101.mika.core.mcp.McpTaskProtocolTest.Companion.request
import com.wangzan101.mika.core.mcp.McpTaskProtocolTest.Companion.value
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class McpHttpServerTest {
    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()

    @Test fun authenticatedHttpClientCanDiscoverAndDelegateWithoutAGetStream() {
        val tasks = McpTaskProtocolTest.Tasks()
        McpHttpServer(McpTaskProtocol(tasks, "test"), "secret", 0).use { server ->
            send(server, request("initialize")).use { response ->
                assertEquals(200, response.code)
                assertEquals("Mika", Json.parseToJsonElement(response.body!!.string()).jsonObject["result"]!!.jsonObject["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            }
            send(server, request("tools/list")).use { assertEquals(200, it.code) }
            send(server, call("mika_task", """{"goal":"读一下今天的讨论"}""")).use { response ->
                val id = value(Json.parseToJsonElement(response.body!!.string()).jsonObject)["task_id"]!!.jsonPrimitive.content
                send(server, call("mika_status", """{"task_id":"$id"}""")).use { assertEquals(200, it.code) }
            }
            assertEquals(1, tasks.submitted.size)
            client.newCall(base(server).get().build()).execute().use { assertEquals(405, it.code); assertEquals("POST", it.header("Allow")) }
        }
    }

    @Test fun rejectsUnauthorizedBrowserAndUnsupportedProtocolRequestsBeforeExecution() {
        val tasks = McpTaskProtocolTest.Tasks()
        McpHttpServer(McpTaskProtocol(tasks, "test"), "secret", 0).use { server ->
            val body = call("mika_task", """{"goal":"send a message"}""").toRequestBody(json)
            for (token in listOf("", "wrong")) {
                client.newCall(base(server).header("Authorization", token).post(body).build()).execute().use { assertEquals(401, it.code) }
            }
            for (origin in listOf("https://evil.example", "null", "http://127.0.0.1")) {
                client.newCall(base(server).header("Origin", origin).post(body).build()).execute().use { assertEquals(403, it.code) }
            }
            client.newCall(base(server).header("MCP-Protocol-Version", "unsupported").post(body).build()).execute().use { assertEquals(400, it.code) }
            assertTrue(tasks.submitted.isEmpty())
        }
    }

    @Test fun acceptsNotificationsAndChunkedUtf8Requests() {
        McpHttpServer(McpTaskProtocol(McpTaskProtocolTest.Tasks(), "test"), "secret", 0).use { server ->
            send(server, """{"jsonrpc":"2.0","method":"notifications/initialized"}""").use { assertEquals(202, it.code); assertEquals("", it.body!!.string()) }
            val body = object : RequestBody() {
                override fun contentType() = json
                override fun writeTo(sink: BufferedSink) { sink.writeUtf8(call("mika_task", """{"goal":"总结聊天 😀"}""")) }
            }
            client.newCall(base(server).post(body).build()).execute().use { assertEquals(200, it.code) }
        }
    }

    @Test fun rejectsOversizedBodiesWrongPathsAndWrongContentTypes() {
        McpHttpServer(McpTaskProtocol(McpTaskProtocolTest.Tasks(), "test"), "secret", 0).use { server ->
            send(server, " ".repeat(131_073)).use { assertEquals(413, it.code) }
            client.newCall(base(server).url("http://127.0.0.1:${server.port}/other").post("{}".toRequestBody(json)).build()).execute().use { assertEquals(404, it.code) }
            client.newCall(base(server).post("{}".toRequestBody("text/plain".toMediaType())).build()).execute().use { assertEquals(415, it.code) }
        }
    }

    @Test fun closeReleasesListenerAndInFlightSockets() {
        val server = McpHttpServer(McpTaskProtocol(McpTaskProtocolTest.Tasks(), "test"), "secret", 0)
        val port = server.port
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("POST /mcp HTTP/1.1\r\n".toByteArray())
            server.close()
            socket.soTimeout = 2_000
            try { assertEquals(-1, socket.getInputStream().read()) } catch (_: java.net.SocketException) { }
        }
        assertFalse(port in OwnListeningPorts.snapshot())
        try { Socket("127.0.0.1", port).use { fail("listener still open") } } catch (_: java.net.ConnectException) { }
    }

    private fun base(server: McpHttpServer) = Request.Builder().url("http://127.0.0.1:${server.port}/mcp")
        .header("Authorization", "Bearer secret").header("Accept", "application/json, text/event-stream")
    private fun send(server: McpHttpServer, body: String) = client.newCall(base(server).post(body.toRequestBody(json)).build()).execute()
}
