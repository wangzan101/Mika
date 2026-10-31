package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.adb.OwnListeningPorts
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancel

/** Streamable HTTP 的 JSON 响应形式,只监听本机,经 adb forward 连接。不开 GET 事件流。 */
class McpHttpServer(
    private val protocol: McpTaskProtocol,
    token: String,
    port: Int = DEFAULT_PORT,
) : Closeable {
    private val authorization = "Bearer $token".also { require(token.isNotBlank()) }.toByteArray(Charsets.UTF_8)
    private val server = ServerSocket(port, 8, InetAddress.getByName(HOST))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    val port: Int get() = server.localPort

    init {
        OwnListeningPorts.add(server.localPort)
        scope.launch {
            while (isActive) {
                val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                val accepted = synchronized(clients) {
                    if (!scope.isActive || clients.size >= 8) false else { clients += socket; true }
                }
                if (!accepted) { socket.close(); continue }
                launch {
                    try { socket.use { serve(it) } }
                    catch (e: CancellationException) { throw e }
                    catch (_: java.io.IOException) { }
                    finally { clients -= socket }
                }
            }
        }
    }

    private suspend fun serve(socket: Socket) {
        socket.soTimeout = 5_000
        val input = BufferedInputStream(socket.getInputStream())
        val request = line(input)?.split(' ') ?: return
        if (request.size != 3) { respond(socket, 400); return }
        val headers = mutableMapOf<String, String>()
        var headerBytes = 0
        while (true) {
            val text = line(input) ?: throw EOFException()
            headerBytes += text.length
            if (headerBytes > 16_384) { respond(socket, 431); return }
            if (text.isEmpty()) break
            val key = text.substringBefore(':').lowercase(Locale.ROOT)
            if (':' !in text || key in headers) { respond(socket, 400); return }
            headers[key] = text.substringAfter(':').trim()
        }
        if (!MessageDigest.isEqual(authorization, headers["authorization"].orEmpty().toByteArray(Charsets.UTF_8))) {
            respond(socket, 401); return
        }
        // 本机协议客户端不发送 Origin;浏览器来源一律拒绝,不开放 CORS。
        if ("origin" in headers) { respond(socket, 403); return }
        if (request[1] != "/mcp") { respond(socket, 404); return }
        if (request[0] != "POST") { respond(socket, 405); return }
        if (headers["mcp-protocol-version"]?.let { it !in McpTaskProtocol.VERSIONS } == true) {
            respond(socket, 400); return
        }
        if (headers["content-type"]?.substringBefore(';')?.trim() != "application/json") {
            respond(socket, 415); return
        }
        val body = try { body(input, headers) }
        catch (e: HttpError) { respond(socket, e.code); return }
        val result = protocol.receive(body.toString(Charsets.UTF_8))
        respond(socket, if (result == null) 202 else 200, result?.toString().orEmpty())
    }

    private fun body(input: BufferedInputStream, headers: Map<String, String>): ByteArray {
        if ("transfer-encoding" in headers) {
            if (headers["transfer-encoding"] != "chunked" || "content-length" in headers) throw HttpError(400)
            val out = ByteArrayOutputStream()
            while (true) {
                val size = line(input)?.substringBefore(';')?.toIntOrNull(16) ?: throw HttpError(400)
                if (size < 0 || size > MAX_BODY - out.size()) throw HttpError(413)
                if (size == 0) {
                    // 本入口不用 trailer,不接受它携带额外的认证或协议元数据。
                    if (line(input) != "") throw HttpError(400)
                    return out.toByteArray()
                }
                out.write(read(input, size))
                if (line(input) != "") throw HttpError(400)
            }
        }
        val size = headers["content-length"]?.toIntOrNull() ?: throw HttpError(411)
        if (size < 0) throw HttpError(400)
        if (size > MAX_BODY) throw HttpError(413)
        return read(input, size)
    }

    private fun read(input: BufferedInputStream, size: Int): ByteArray = ByteArray(size).also { bytes ->
        var offset = 0
        while (offset < size) {
            val n = input.read(bytes, offset, size - offset)
            if (n < 0) throw EOFException()
            offset += n
        }
    }

    private fun line(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream()
        while (out.size() <= 8_192) {
            val byte = input.read()
            if (byte < 0) return if (out.size() == 0) null else throw EOFException()
            if (byte == 10) return out.toString("ISO-8859-1").removeSuffix("\r")
            out.write(byte)
        }
        throw java.io.IOException("HTTP line too long")
    }

    private fun respond(socket: Socket, code: Int, body: String = "") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val extra = when (code) {
            401 -> "WWW-Authenticate: Bearer\r\n"
            405 -> "Allow: POST\r\n"
            else -> ""
        }
        socket.getOutputStream().apply {
            write(("HTTP/1.1 $code ${if (code == 200) "OK" else "Response"}\r\n" +
                "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n$extra\r\n").toByteArray(Charsets.ISO_8859_1))
            write(bytes)
            flush()
        }
    }

    override fun close() {
        OwnListeningPorts.remove(port)
        scope.cancel()
        server.close()
        synchronized(clients) {
            clients.forEach { runCatching { it.close() } }
            clients.clear()
        }
    }

    private class HttpError(val code: Int) : Exception()

    companion object {
        const val HOST = "127.0.0.1"
        const val DEFAULT_PORT = 18765
        private const val MAX_BODY = 131_072
    }
}
