package com.wangzan101.mika.core.feishu

import com.wangzan101.mika.core.run.SendOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class FeishuTest {
    @Test fun interactiveCardIsSentAsSerializedJsonWithStableUuid() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":0,"tenant_access_token":"token","expire":7200}"""))
            server.enqueue(MockResponse().setBody("""{"code":0}"""))
            val api = FeishuApi("cli_test", "secret", server.url("/"))
            val card = FeishuCards.menu(listOf(FeishuWindow("window", "任务标题", "running")), null, "nonce", 0)
            assertEquals(SendOutcome.Delivered, api.sendCard("chat", card, "stable-card"))
            server.takeRequest()
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("interactive", body["msg_type"]!!.jsonPrimitive.content)
            assertEquals("stable-card", body["uuid"]!!.jsonPrimitive.content)
            val sent = Json.parseToJsonElement(body["content"]!!.jsonPrimitive.content).jsonObject
            assertTrue(sent.toString().contains("select_static"))
        }
    }
    @Test fun envelopeRoundTripPreservesRoutingFields() {
        val frame=FeishuFrame(17,88,123,1,listOf("type" to "event","message_id" to "id"),payload="{}".toByteArray(),logNew="new-log")
        val decoded=FeishuFrame.decode(frame.encode())
        assertEquals(frame.seq,decoded.seq); assertEquals(frame.logNew,decoded.logNew)
        assertEquals(frame.headers,decoded.headers); assertArrayEquals(frame.payload,decoded.payload)
    }

    @Test fun fragmentedEventsCanArriveOutOfOrderAndAreBounded() {
        val fragments=FeishuFragments()
        fun frame(seq: Int, body: String)=FeishuFrame(headers=listOf("sum" to "2","seq" to "$seq","message_id" to "event"),payload=body.toByteArray())
        assertNull(fragments.accept(frame(1,"world")))
        assertArrayEquals("hello world".toByteArray(),fragments.accept(frame(0,"hello ")))
        assertThrows(IllegalArgumentException::class.java) { fragments.accept(FeishuFrame(headers=listOf("sum" to "999"))) }
    }

    @Test fun signedEndpointMustBeOfficialWssWithoutUserInfo() {
        assertEquals("ws-msg.feishu.cn",FeishuSocket.connectionUrl("wss://ws-msg.feishu.cn/ws?service_id=1").host)
        for(url in listOf("ws://ws-msg.feishu.cn/ws","wss://evil.example/ws","wss://ws-msg.feishu.cn.evil.example/ws","wss://user:pass@ws-msg.feishu.cn/ws"))
            assertThrows(IllegalArgumentException::class.java) { FeishuSocket.connectionUrl(url) }
    }

    @Test fun sendGetsTokenAndUsesStableMessageUuidWithoutFollowingRedirects() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":0,"tenant_access_token":"token","expire":7200}"""))
            server.enqueue(MockResponse().setBody("""{"code":0}"""))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","/redirect"))
            val api=FeishuApi("cli_test","secret",server.url("/"))
            assertEquals(SendOutcome.Delivered,api.send("chat","hello","stable-id"))
            val token=server.takeRequest(); assertTrue(token.path!!.contains("tenant_access_token"))
            val message=server.takeRequest(); assertEquals("Bearer token",message.getHeader("Authorization"))
            assertEquals("stable-id",Json.parseToJsonElement(message.body.readUtf8()).jsonObject["uuid"]!!.jsonPrimitive.content)
            assertEquals(SendOutcome.Uncertain,api.send("chat","hello","another-id"))
            assertEquals(3,server.requestCount)
        }
    }
}
