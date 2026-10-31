package com.wangzan101.mika.core.oauth

import com.wangzan101.mika.core.model.BearerToken
import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 订阅账号登录没成的原因。界面按它说人话。异常文字里只有类别和错误代码,不放令牌、授权码、设备码。
 * [NoSubscription] 是账号没有这项订阅;[BadIssuer] 是发现到的地址不在允许的主机上,请求不会发出去。
 */
enum class AccountSignInFailure { Denied, Expired, TimedOut, Rejected, NoSubscription, Network, BadIssuer }

/** [code] 是服务端的错误代码(不是机密)。 */
class AccountSignInException(
    val reason: AccountSignInFailure,
    val code: String? = null,
    cause: Throwable? = null,
) : Exception(buildString {
    append(reason.name)
    if (!code.isNullOrBlank()) append(": ").append(code)
}, cause)

/** 换令牌得到的一组令牌。刷新没给的字段保持 null,由调用方沿用旧的。 */
data class OauthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val idToken: String? = null,
    val expiresInSec: Long = 3600,
    val scopes: List<String> = emptyList(),
    /** Copilot 换到的接口根;别的登录为 null。 */
    val apiBase: String? = null,
)

/** 换令牌的结果。被拒时 [terminal] 为真表示这份授权不能再用,该清掉重新登录。 */
sealed interface OauthTokenResult {
    data class Ok(val tokens: OauthTokens) : OauthTokenResult
    data class Rejected(val code: String, val terminal: Boolean) : OauthTokenResult
    data class Failed(val error: ModelError) : OauthTokenResult
}

/**
 * 一个订阅账号的登录,整份按 API Key 的方式加密保存。
 * [accessToken] 是调用模型用的令牌(xAI 的访问令牌,或 Copilot 的短期接口令牌);
 * [refreshToken] 是 xAI 的刷新令牌;Copilot 的 GitHub 授权放在 [grantToken],用它去换短期接口令牌。
 * 退出登录后令牌清空,账号名字留着给人看。
 */
@Serializable
data class AccountSession(
    val provider: String,
    val accountId: String = "",
    val label: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtMs: Long = 0,
    val scopes: List<String> = emptyList(),
    val grantToken: String = "",
    val apiBase: String = "",
    val clientId: String = "",
    val issuer: String = "",
) {
    val signedIn: Boolean get() = accessToken.isNotEmpty() || refreshToken.isNotEmpty() || grantToken.isNotEmpty()

    fun signedOut() = copy(accessToken = "", refreshToken = "", grantToken = "", expiresAtMs = 0)

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String?): AccountSession? =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/**
 * 调用时用的访问令牌。快过期或上一个被拒了就经 [refresh] 换新的,同一时刻只有一个在换。
 * 换到的令牌先 [save] 再拿去用。授权已不能用就清掉令牌、抛 [ModelError.SignInExpired];网络或服务端一时出错照常抛,令牌留着。
 */
class AccountTokens(
    private val refresh: suspend (AccountSession) -> OauthTokenResult,
    private val load: () -> AccountSession?,
    private val save: (AccountSession) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val refreshAheadMs: Long = 5 * 60_000L,
) : BearerToken {
    private val lock = Mutex()
    override val refreshable: Boolean get() = true

    override suspend fun get(fresh: Boolean): String = lock.withLock {
        val s = load()?.takeIf { it.signedIn } ?: throw ModelError.SignInExpired()
        if (!fresh && s.accessToken.isNotEmpty() && now() < s.expiresAtMs - refreshAheadMs) return@withLock s.accessToken
        when (val r = refresh(s)) {
            is OauthTokenResult.Ok -> {
                val t = r.tokens
                val next = s.copy(
                    accessToken = t.accessToken,
                    refreshToken = t.refreshToken ?: s.refreshToken,
                    expiresAtMs = now() + t.expiresInSec * 1000,
                    scopes = t.scopes.ifEmpty { s.scopes },
                    apiBase = t.apiBase ?: s.apiBase,
                )
                save(next)
                next.accessToken
            }
            is OauthTokenResult.Rejected -> {
                if (r.terminal) {
                    save(s.signedOut())
                    throw ModelError.SignInExpired()
                }
                throw ModelError.Auth()
            }
            is OauthTokenResult.Failed -> throw r.error
        }
    }
}
