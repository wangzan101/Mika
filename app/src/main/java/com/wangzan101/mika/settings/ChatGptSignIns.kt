package com.wangzan101.mika.settings

import com.wangzan101.mika.about.Diagnostics
import com.wangzan101.mika.core.chatgpt.SignInException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 最近一次登录失败,只给诊断信息用:时间、原因的类别、服务端的错误代码、一小段说明(已脱敏)。 */
data class ChatGptSignInFailureRecord(val atMs: Long, val kind: String, val code: String?, val detail: String)

/**
 * 「用 ChatGPT 登录」在进程里的那一份。登录要等用户在外部浏览器里输密码、过两步验证,期间 Mika 在后台:
 * 跑在 App 的作用域 [scope] 里(界面重建、退到后台都不断),开始时调 [keepAlive] 撑住进程(前台服务,
 * 免得进程被冻住、本机回调没人接);它起不来只记一句,登录照常。前台服务看着 [running],变回假就自己停,
 * 所以成功、失败、取消、超时都会停。同一时刻只有一次登录;[cancel] 放弃(通知上的「取消」也走这里)。
 */
class ChatGptSignIns(
    private val scope: CoroutineScope,
    private val keepAlive: () -> Unit = {},
    private val log: (String, Throwable) -> Unit = { _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var job: Job? = null

    /** 最近一次失败(见 [recordFailure]);只在内存里。 */
    @Volatile var lastFailure: ChatGptSignInFailureRecord? = null
        private set

    /** 开始一次登录,跑 [block];已有一次在跑时不开,返回假。 */
    fun start(block: suspend CoroutineScope.() -> Unit): Boolean = synchronized(this) {
        if (_running.value) return false
        _running.value = true
        runCatching(keepAlive).onFailure { log("sign-in keep-alive could not start", it) }
        // App 的作用域没有异常处理:漏出来的异常只记下,不让进程崩
        val j = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("sign-in ended with an unexpected error", e)
            }
        }
        job = j
        j.invokeOnCompletion { synchronized(this) { if (job === j) { job = null; _running.value = false } } }
        true
    }

    fun cancel() {
        synchronized(this) { job }?.cancel()
    }

    fun recordFailure(e: SignInException) {
        val detail = Diagnostics.sanitize(e.message.orEmpty()).take(MAX_DETAIL)
        lastFailure = ChatGptSignInFailureRecord(now(), e.reason.name, e.code, detail)
    }

    private companion object {
        const val MAX_DETAIL = 160
    }
}
