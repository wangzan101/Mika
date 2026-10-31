package com.wangzan101.mika.core.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.net.InetSocketAddress

/** 设置页上显示的一点点动静:最近连过的客户端有几个、最后一次请求是什么时候。 */
data class McpActivity(val recentClients: Int = 0, val lastRequestAtMs: Long? = null)

/**
 * 电脑连接的整套服务:HTTP 监听 → 门口([McpAccess])→ 协议([McpRpc])→ 任务工具([McpTaskTools])。
 * 只监听 [port];[allowAllInterfaces] 为 false 时只绑本机回环 127.0.0.1(经 USB 的电脑连接、测试),为 true 时绑所有网卡,
 * 但只收两端都是私有地址的连接(局域网、私有组网),公网来的当场断开。
 * [token] 哪里来的连接都认;[loopbackToken] 只认本机回环来的(电脑经 `adb forward` 进来就是回环)。
 */
class McpHost(
    tasks: RemoteTasks,
    private val token: () -> String?,
    private val port: Int,
    private val allowAllInterfaces: Boolean,
    serverVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onError: (Throwable) -> Unit = {},
    loopbackToken: () -> String? = { null },
) {
    private val access = McpAccess(token, clock, loopbackToken = loopbackToken)
    private val clients = LinkedHashMap<String, Long>()
    private val _activity = MutableStateFlow(McpActivity())
    val activity: StateFlow<McpActivity> = _activity.asStateFlow()
    private val rpc = McpRpc(McpTaskTools(tasks), serverVersion) { remote, name -> seen(remote, name) }
    private var server: MiniHttpServer? = null

    val boundPort: Int get() = server?.port ?: -1

    fun handle(req: HttpRequest): HttpResponse = when (val v = access.check(req)) {
        is McpAccess.Verdict.Reject -> v.response
        McpAccess.Verdict.Pass -> rpc.handle(req)
    }

    /** 同一客户端(地址 + 自报的名字)算一个;十分钟内来过的算「最近」。 */
    private fun seen(remote: InetAddress, name: String?) {
        val now = clock()
        synchronized(this) {
            val who = remote.hostAddress ?: "?"
            // 老一代客户端只在握手时报名字,之后的请求按地址归到已有的那一个
            val key = if (name != null) "$who|$name" else clients.keys.lastOrNull { it.startsWith("$who|") } ?: "$who|"
            clients.remove(key)
            clients[key] = now
            clients.entries.removeAll { now - it.value > RECENT_MS }
            _activity.value = McpActivity(clients.size, now)
        }
    }

    /** 开始监听;端口被占等绑定失败时抛出异常。 */
    fun start() {
        val s = MiniHttpServer(bindAddress(allowAllInterfaces, port), ::handle, accept = { local, remote ->
            McpAccess.privateAddress(local) && McpAccess.privateAddress(remote)
        }, onError = onError)
        s.start()
        server = s
    }

    fun stop() {
        server?.stop()
        server = null
    }

    companion object {
        const val RECENT_MS = 10 * 60_000L

        /**
         * 监听的地址:所有网卡,或只有 IPv4 回环 127.0.0.1。回环写死成 IPv4:安卓上 `InetAddress.getLoopbackAddress()` 可能给 ::1,
         * 而 `adb forward` 连的是 127.0.0.1。
         */
        fun bindAddress(allowAllInterfaces: Boolean, port: Int): InetSocketAddress =
            if (allowAllInterfaces) InetSocketAddress(port) else InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port)
    }
}
