package com.wangzan101.mika.cap.phone.setup

import android.content.Context
import android.content.pm.PackageManager
import com.wangzan101.mika.core.shell.BuiltinShellControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/** 引导页上 Shizuku 三件事的真实状态:装没装、服务起没起、授没授权。 */
data class ShizukuState(val installed: Boolean, val running: Boolean, val authorized: Boolean)

/**
 * Shizuku 后端的活状态与回调协调器。
 *
 * 只在用户选中 Shizuku 后端时 [start];无线调试后端不注册任何监听。授权结果、binder 收到/掉线三个回调
 * 驱动引擎:授权允许或服务回来 → [BuiltinShellControl.retry];服务掉线 → [BuiltinShellControl.shizukuUnavailable]。
 * 监听器成对注册/移除([start]/[stop]),不泄漏。
 */
class ShizukuSetup(context: Context, private val control: BuiltinShellControl) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val onPermission = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST_CODE) {
            refresh()
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                // 授权允许:引擎该起助手了
                scope.launch { control.retry() }
            }
        }
    }
    private val onBinderReceived = Shizuku.OnBinderReceivedListener {
        refresh()
        // 服务起来了:之前停在「去处理 Shizuku」就重新试着起助手
        scope.launch { control.retry() }
    }
    private val onBinderDead = Shizuku.OnBinderDeadListener {
        refresh()
        // 服务掉了:状态与看门狗都反映成需要用户去处理 Shizuku
        control.shizukuUnavailable()
    }

    @Volatile private var registered = false

    /** 选中 Shizuku 后端时注册三个监听,并立即刷一遍状态。重复调用无害。 */
    @Synchronized
    fun start() {
        if (registered) return
        registered = true
        Shizuku.addRequestPermissionResultListener(onPermission)
        Shizuku.addBinderReceivedListener(onBinderReceived)
        Shizuku.addBinderDeadListener(onBinderDead)
        refresh()
    }

    /** 不再用 Shizuku 后端时成对移除监听。 */
    @Synchronized
    fun stop() {
        if (!registered) return
        registered = false
        Shizuku.removeRequestPermissionResultListener(onPermission)
        Shizuku.removeBinderReceivedListener(onBinderReceived)
        Shizuku.removeBinderDeadListener(onBinderDead)
    }

    /** 「授权」按钮:服务在跑时发起 Shizuku 授权请求,结果由 [onPermission] 接住再驱动引擎。 */
    fun requestPermission() {
        if (Shizuku.pingBinder()) Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        refresh()
    }

    private fun refresh() {
        _state.value = read()
    }

    private fun read(): ShizukuState {
        val installed = SHIZUKU_PACKAGES.any { runCatching { app.packageManager.getPackageInfo(it, 0) }.isSuccess }
        val running = installed && runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        // 服务没跑时 checkSelfPermission 会抛异常;只有在跑时才问得出授权
        val authorized = running && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return ShizukuState(installed, running, authorized)
    }

    private companion object {
        /** 官方包名是 moe.shizuku.privileged.api;老版本用过 moe.shizuku.manager,两个都认。 */
        val SHIZUKU_PACKAGES = listOf("moe.shizuku.privileged.api", "moe.shizuku.manager")
    }
}
