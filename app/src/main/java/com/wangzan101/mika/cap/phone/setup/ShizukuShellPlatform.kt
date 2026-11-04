package com.wangzan101.mika.cap.phone.setup

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import com.wangzan101.mika.cap.phone.shell.HelperHub
import com.wangzan101.mika.core.shell.BuiltinShellPlatform
import com.wangzan101.mika.core.shell.HelperHandoff
import com.wangzan101.mika.core.shell.ShizukuUnavailableException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/** 授权请求码:ShizukuShellPlatform 发起授权和 [ShizukuSetup] 的回调监听共用同一个码。 */
internal const val SHIZUKU_REQUEST_CODE = 1000

/**
 * Shizuku 后端:经 Shizuku 提供的进程以和无线调试完全相同的命令起助手。
 *
 * ⚠️ 过渡实现:这里用 Shizuku.newProcess 起助手,官方已把它从公开 API 收成 private 并宣布要移除,
 * 推荐改用 UserService(见 Shizuku-API README)。过渡期内 [launchHelper] 只反射调用这一处,便于以后替换;
 * 新代码不要再散落 newProcess 的调用。
 *
 * 校验链和无线调试一致:助手仍以 [HelperHandoff.command] 起的 HelperMain 经 [HelperHub.offer] 交接。
 * 两个 uid 各管各的:启动命令的 `id -u` 守卫核对 Shizuku 执行方身份([Shizuku.getUid],root=0,ADB=2000),
 * 而交接时 [HelperHandoff.check] 核对的期望 uid 恒为 shell(2000)——助手一进进程就把自己降到 shell(见 HelperPrivileges),
 * 和无线调试路径一致;uid/pid/APK 路径/口令四重校验一条不少。
 */
class ShizukuShellPlatform(
    private val app: Context,
    private val hub: HelperHub,
) : BuiltinShellPlatform {

    // ---- 可用性:paired 是「现在能不能自己起助手」的活判断 ----

    /**
     * Shizuku 已授权 = 等价于「配对过」:Mika 能自己起助手。
     *
     * 不把 pingBinder 放进这里:服务掉线时 paired 仍为 true(Shizuku 会把授权结果缓存住),引擎才会去
     * [launchHelper] 试一次,在那儿发现 pingBinder 为 false 抛 [ShizukuUnavailableException],状态落到
     * [com.wangzan101.mika.core.shell.BuiltinShellFailure.ShizukuUnavailable] 而不是坍缩成「没设置」。
     * 服务没起、授权结果也没缓存时(比如进程刚起来还没连上),checkSelfPermission 会抛异常,按未配对处理。
     */
    override fun paired(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 引擎配对成功后写 true(见 BuiltinShellEngine.startPairing)。Shizuku 的授权状态以 [paired] 实时为准,
     * App 单方「记住 true」没有意义(服务被杀/用户撤销都会让它失效),所以这里不落盘。
     */
    override fun setPaired(paired: Boolean) = Unit

    // ---- 引导里的那几步:Shizuku 路径没有无线调试 / 配对码 ----

    /** Shizuku 不关心无线调试开关;读不到(null)表示「不拦着往下走」,引擎只在读到 false 时停。 */
    override fun wirelessDebugging(): Boolean? = null

    /** Shizuku 不依赖 Wi-Fi。恒 true,免得引擎把「没 Wi-Fi」当障碍。 */
    override fun wifiConnected(): Boolean = true

    /** Shizuku 没有「配对端口」概念。 */
    override suspend fun findPairingPort(timeoutMs: Long): Int? = null

    /**
     * 发起授权请求。Shizuku 授权是**异步**的(结果经 Shizuku 的授权回调),这里不抛异常也不标记配对;
     * 授权回来由监听方调 engine.retry() 驱动起助手。引擎的 startPairing 假定 pair() 同步成功,Shizuku 路径不走它。
     */
    override fun pair(port: Int, code: String) {
        if (!Shizuku.pingBinder()) return
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        }
    }

    // ---- 起助手 ----

    override suspend fun launchHelper() {
        // 引擎 launchOnce 已查过 paired(),但 Shizuku 可能在这两步之间被杀:服务没了就报「不可用」,不无限重试
        if (!Shizuku.pingBinder()) throw ShizukuUnavailableException("Shizuku binder unavailable")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            error("Shizuku permission not granted")
        }
        val serverUid = Shizuku.getUid()
        if (serverUid < 0) throw ShizukuUnavailableException("Shizuku uid unknown")
        // 助手总会降到 shell(见 HelperProcess/HelperPrivileges):交接核对的期望 uid 恒为 2000,
        // 而启动命令的 id -u 守卫核对的是 Shizuku 执行方身份(serverUid,root=0,ADB=2000)。
        hub.expectedUid = HelperHandoff.SHELL_UID

        // 和无线调试路径完全相同的启动命令;只有启动方 uid 换成 Shizuku 实际执行的 uid
        val command = HelperHandoff.command(
            app.applicationInfo.sourceDir, app.packageName, Process.myUid(), ownCertPin(app), launchUid = serverUid,
        )
        val before = hub.shell.value
        // newProcess 按 argv 执行(不是 sh -c 字符串),所以包一层 sh;CLASSPATH 已内联在 command 里
        val process = newProcess.invoke(null, arrayOf("sh", "-c", command), null, null) as java.lang.Process
        try {
            // 口令不进命令行(同为 shell 的进程能读 /proc/<pid>/cmdline):开流后写一行 launch nonce,
            // 对应 command 里的第一个 `read n`。交接完成前不要关 outputStream,否则 sh 的第二个 `read _` 立刻 EOF。
            process.outputStream.write(HelperHandoff.stdinLine(hub.issueLaunchNonce()))
            process.outputStream.flush()
            // 起没起来不看进程退出码(助手被 setsid 挪到后台,sh 包装进程的退出码没意义),
            // 看 HelperHub 有没有收到 Binder 交接。这里把 stdin 流保持到交接完成(或超时)。
            withTimeoutOrNull(HANDOFF_WAIT_MS) { hub.shell.first { s -> s != null && s !== before } }
        } finally {
            // 交接完(或超时)才关 stdin,让 sh 的 `read _` 读到 EOF 自己退出;再销毁包装进程兜底。
            // 助手已经 setsid 进独立会话,不受这里关闭影响。
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.destroy() }
        }
    }

    // ---- 停助手 / 解除 ----

    /** 停助手走 Binder(IMikaShell.destroy),与传输方式无关。 */
    override fun stopHelper() {
        runCatching { hub.shell.value?.destroy() }
    }

    /** 交接口令作废、放掉手里的 Binder。Shizuku 授权本身只能由用户去 Shizuku App 撤销。 */
    override fun forget() {
        hub.forget()
    }

    // ---- 电脑经 USB 起助手:Shizuku 路径没有这条路 ----

    override fun usbStarted(): Boolean = false
    override fun setUsbStarted(on: Boolean) = Unit

    private companion object {
        /** 与 BuiltinShellManager 的 HANDOFF_WAIT_MS 保持一致;引擎里还有一层 handoffTimeoutMs(默认 15s)。 */
        const val HANDOFF_WAIT_MS = 15_000L

        /**
         * Shizuku 13.1.1 起把 Shizuku.newProcess 从公开 API 收成 private(官方推荐 UserService 取代)。
         * 本轮是过渡实现,反射调用它;过渡到 UserService 前它只在这里出现一次。
         */
        private val newProcess by lazy {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }
        }
    }
}
