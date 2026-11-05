package com.wangzan101.mika.core.feishu

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/** Feishu's documented protobuf envelope, using Mika's existing protobuf-lite dependency. */
internal data class FeishuFrame(
    val seq: Long = 0, val log: Long = 0, val service: Int = 0, val method: Int = 0,
    val headers: List<Pair<String, String>> = emptyList(), val encoding: String = "",
    val type: String = "", val payload: ByteArray = byteArrayOf(), val logNew: String = "",
) {
    fun header(key: String) = headers.lastOrNull { it.first == key }?.second
    fun encode(): ByteArray = output { out ->
        out.writeUInt64(1, seq); out.writeUInt64(2, log); out.writeInt32(3, service); out.writeInt32(4, method)
        headers.forEach { (key, value) -> out.writeByteArray(5, output { it.writeString(1,key); it.writeString(2,value) }) }
        if (encoding.isNotEmpty()) out.writeString(6,encoding)
        if (type.isNotEmpty()) out.writeString(7,type)
        if (payload.isNotEmpty()) out.writeByteArray(8,payload)
        if (logNew.isNotEmpty()) out.writeString(9,logNew)
    }

    companion object {
        private fun output(write: (CodedOutputStream) -> Unit): ByteArray {
            val bytes = ByteArrayOutputStream(); val out = CodedOutputStream.newInstance(bytes)
            write(out); out.flush(); return bytes.toByteArray()
        }
        fun decode(bytes: ByteArray): FeishuFrame {
            require(bytes.size <= 1024 * 1024) { "Frame too large" }
            val input = CodedInputStream.newInstance(bytes)
            var frame = FeishuFrame()
            val headers = mutableListOf<Pair<String,String>>()
            while (!input.isAtEnd) {
                when (val tag = input.readTag()) {
                    8 -> frame = frame.copy(seq=input.readUInt64())
                    16 -> frame = frame.copy(log=input.readUInt64())
                    24 -> frame = frame.copy(service=input.readInt32())
                    32 -> frame = frame.copy(method=input.readInt32())
                    42 -> {
                        require(headers.size < 64)
                        val h = CodedInputStream.newInstance(input.readByteArray()); var key=""; var value=""
                        while (!h.isAtEnd) when(val t=h.readTag()) { 10 -> key=h.readStringRequireUtf8(); 18 -> value=h.readStringRequireUtf8(); else -> require(h.skipField(t)) }
                        headers += key to value
                    }
                    50 -> frame = frame.copy(encoding=input.readStringRequireUtf8())
                    58 -> frame = frame.copy(type=input.readStringRequireUtf8())
                    66 -> frame = frame.copy(payload=input.readByteArray())
                    74 -> frame = frame.copy(logNew=input.readStringRequireUtf8())
                    else -> require(input.skipField(tag))
                }
            }
            return frame.copy(headers=headers)
        }
    }
}

internal class FeishuFragments(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Parts(val created: Long, val chunks: Array<ByteArray?>)
    private val pending = linkedMapOf<String, Parts>()
    fun accept(frame: FeishuFrame): ByteArray? {
        pending.entries.removeAll { clock() - it.value.created > 30_000 }
        val total = frame.header("sum")?.toIntOrNull() ?: 1
        val seq = frame.header("seq")?.toIntOrNull() ?: 0
        require(total in 1..128 && seq in 0 until total)
        if (total == 1) return frame.payload
        val id = requireNotNull(frame.header("message_id"))
        require(id.length <= 256)
        val parts = pending[id] ?: run {
            require(pending.size < 8)
            Parts(clock(), arrayOfNulls(total)).also { pending[id]=it }
        }
        require(parts.chunks.size == total)
        parts.chunks[seq]?.let { require(it.contentEquals(frame.payload)) }
        parts.chunks[seq] = frame.payload
        require(pending.values.sumOf { p -> p.chunks.sumOf { it?.size ?: 0 } } <= 4 * 1024 * 1024)
        if (parts.chunks.any { it == null }) return null
        pending.remove(id)
        return ByteArrayOutputStream().apply { parts.chunks.forEach { write(it!!) } }.toByteArray()
    }
}

/** Outbound-only WSS. Event callback returns only after a durable inbox write, not model execution. */
class FeishuSocket(
    private val api: FeishuApi,
    private val event: suspend (JsonObject) -> Unit,
    private val status: (Boolean) -> Unit = {},
    client: OkHttpClient = OkHttpClient(),
) {
    private val client=client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    suspend fun run(): Unit = coroutineScope {
        var backoff = 1L
        while (isActive) {
            val started=System.currentTimeMillis()
            try {
                connect()
                if (System.currentTimeMillis()-started > 60_000) backoff = 1
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { status(false) }
            delay(backoff * 1000)
            backoff = (backoff * 2).coerceAtMost(60)
        }
    }

    private suspend fun connect(): Unit = supervisorScope {
        val data = api.endpoint()
        val endpoint = requireNotNull(data["URL"]?.jsonPrimitive?.content)
        val url = connectionUrl(endpoint)
        val service = requireNotNull(url.queryParameter("service_id")?.toIntOrNull())
        var pingSeconds = data["ClientConfig"]?.jsonObject?.get("PingInterval")?.jsonPrimitive?.longOrNull?.coerceIn(5,300) ?: 120
        val closed = CompletableDeferred<Unit>()
        val received=AtomicLong(System.currentTimeMillis())
        val frames = Channel<ByteArray>(32)
        val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { status(true) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                received.set(System.currentTimeMillis())
                if (bytes.size > 1024*1024 || frames.trySend(bytes.toByteArray()).isFailure) webSocket.cancel()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(Unit) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code,null); closed.complete(Unit) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
        })
        val ping = launch {
            try {
            while (isActive) {
                delay(pingSeconds*1000)
                if (System.currentTimeMillis()-received.get() > pingSeconds*3000) { socket.cancel(); closed.complete(Unit); break }
                check(socket.send(FeishuFrame(service=service, headers=listOf("type" to "ping")).encode().toByteString()))
            }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { closed.complete(Unit) }
        }
        val reader = launch {
            try {
            val fragments = FeishuFragments()
            for (bytes in frames) {
                val frame = FeishuFrame.decode(bytes)
                if (frame.method == 0 && frame.header("type") == "pong") {
                    if (frame.payload.isNotEmpty()) pingSeconds = Json.parseToJsonElement(frame.payload.decodeToString()).jsonObject["PingInterval"]?.jsonPrimitive?.longOrNull?.coerceIn(5,300) ?: pingSeconds
                    continue
                }
                if (frame.method != 1) continue
                val payload = fragments.accept(frame) ?: continue
                var code = 200
                try {
                    if (frame.header("type") in setOf("event", "card")) event(Json.parseToJsonElement(payload.decodeToString()).jsonObject)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { code=500 }
                val acknowledgement = frame.copy(payload="{\"code\":$code}".encodeToByteArray())
                check(socket.send(acknowledgement.encode().toByteString()))
            }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { closed.complete(Unit) }
        }
        reader.invokeOnCompletion { closed.complete(Unit) }
        ping.invokeOnCompletion { closed.complete(Unit) }
        try { closed.await() }
        finally { ping.cancel(); reader.cancel(); frames.close(); socket.cancel(); status(false) }
    }

    companion object {
        internal fun connectionUrl(value: String): HttpUrl {
            require(value.startsWith("wss://")) { "WSS required" }
            val url = ("https://" + value.removePrefix("wss://")).toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null)
            require(url.host.endsWith(".feishu.cn") && url.port == 443) { "Untrusted connection host" }
            return url
        }
    }
}
