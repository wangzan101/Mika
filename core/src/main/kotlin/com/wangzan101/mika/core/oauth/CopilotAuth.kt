package com.wangzan101.mika.core.oauth

import com.wangzan101.mika.core.model.HttpSupport
import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 用 GitHub Copilot 订阅登录,再直接调 Copilot 自己的 Chat Completions(`{api}/chat/completions`)。
 *
 * 设备码走 GitHub 公开的 OAuth 设备流程。client_id 复用官方 VS Code GitHub Copilot 扩展的公开客户端
 * (不是 Mika 注册的应用)。换 Copilot 接口令牌和之后的调用要带上该接口要求的编辑器集成标识
 * (见 [API_HEADERS]);GitHub 可能不支持第三方这样用,有账号被限制的风险,界面在登录前写明。
 * 不把 Copilot 的请求转成别的协议。GitHub Enterprise 不在这条路上。
 */
class CopilotAuth(
    private val http: OkHttpClient,
    private val deviceCodeUrl: String = DEVICE_CODE_URL,
    private val oauthTokenUrl: String = OAUTH_TOKEN_URL,
    private val userUrl: String = USER_URL,
    private val copilotTokenUrl: String = COPILOT_TOKEN_URL,
    private val clientId: String = CLIENT_ID,
    private val oauthHosts: Set<String> = setOf("github.com"),
    private val apiHosts: Set<String> = setOf("api.github.com"),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun signIn(
        openBrowser: (String) -> Unit,
        onChallenge: (DeviceChallenge) -> Unit,
        sleep: suspend (Long) -> Unit = ::oauthSleep,
        clock: () -> Long = now,
    ): AccountSession {
        val client = DeviceCodeClient(http, deviceCodeUrl, oauthTokenUrl, clientId, SCOPE, oauthHosts)
        val grant = client.start()
        val verify = if (OauthUrls.allowed(grant.challenge.verificationUri, oauthHosts)) grant.challenge.verificationUri else VERIFY_FALLBACK
        val complete = grant.challenge.verificationUriComplete?.takeIf { OauthUrls.allowed(it, oauthHosts) }
        val challenge = grant.challenge.copy(verificationUri = verify, verificationUriComplete = complete)
        onChallenge(challenge)
        openBrowser(complete ?: verify)
        val github = awaitDeviceGrant(grant, clock, sleep) { client.poll(it) }
        val login = githubUser(github.accessToken)
        val api = copilotToken(github.accessToken, clock)
        return AccountSession(
            provider = PROVIDER,
            accountId = login.first,
            label = login.second,
            accessToken = api.token,
            expiresAtMs = api.expiresAtMs,
            scopes = github.scopes,
            grantToken = github.accessToken,
            apiBase = api.apiBase,
            clientId = clientId,
            issuer = "https://github.com",
        )
    }

    /** 用保存的 GitHub 授权再换一张短期的 Copilot 接口令牌。GitHub 授权失效就得重新登录。 */
    suspend fun refresh(session: AccountSession): OauthTokenResult {
        if (session.grantToken.isEmpty()) return OauthTokenResult.Rejected("no_grant", terminal = true)
        return when (val r = fetchCopilot(session.grantToken, now)) {
            is CopilotMint.Ok -> OauthTokenResult.Ok(
                OauthTokens(accessToken = r.token, expiresInSec = ((r.expiresAtMs - now()) / 1000).coerceAtLeast(1), apiBase = r.apiBase),
            )
            is CopilotMint.NoSubscription -> OauthTokenResult.Rejected("no_subscription", terminal = true)
            is CopilotMint.Unauthorized -> OauthTokenResult.Rejected("unauthorized", terminal = true)
            is CopilotMint.BadHost -> OauthTokenResult.Rejected("host", terminal = true)
            is CopilotMint.Failed -> OauthTokenResult.Failed(r.error)
        }
    }

    private suspend fun githubUser(token: String): Pair<String, String> {
        val url = OauthUrls.check(userUrl, apiHosts)
        val raw = try {
            HttpSupport.execute(
                http,
                Request.Builder().url(url).header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer $token").header("User-Agent", "Mika").get().build(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError) {
            throw AccountSignInException(AccountSignInFailure.Network, "user", e)
        }
        if (raw.status == 401) throw AccountSignInException(AccountSignInFailure.Rejected, "unauthorized")
        val root = raw.takeIf { it.status == 200 }?.let { jsonObject(it.body) } ?: return "" to ""
        val id = (root["id"] as? JsonPrimitive)?.longOrNull?.toString().orEmpty()
        val login = root.str("login").orEmpty()
        return id to login
    }

    private suspend fun copilotToken(githubToken: String, clock: () -> Long): CopilotMint.Ok {
        return when (val r = fetchCopilot(githubToken, clock)) {
            is CopilotMint.Ok -> r
            is CopilotMint.NoSubscription -> throw AccountSignInException(AccountSignInFailure.NoSubscription, "no_subscription")
            is CopilotMint.Unauthorized -> throw AccountSignInException(AccountSignInFailure.Rejected, "unauthorized")
            is CopilotMint.BadHost -> throw AccountSignInException(AccountSignInFailure.BadIssuer, "api")
            is CopilotMint.Failed -> throw AccountSignInException(AccountSignInFailure.Network, cause = r.error)
        }
    }

    private suspend fun fetchCopilot(githubToken: String, clock: () -> Long): CopilotMint {
        val url = try {
            OauthUrls.check(copilotTokenUrl, apiHosts)
        } catch (e: AccountSignInException) {
            return CopilotMint.BadHost
        }
        val raw = try {
            val builder = Request.Builder().url(url).header("Accept", "application/json")
                .header("Authorization", "Bearer $githubToken").get()
            API_HEADERS.forEach { (k, v) -> builder.header(k, v) }
            HttpSupport.execute(http, builder.build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError) {
            return CopilotMint.Failed(e)
        }
        when (raw.status) {
            401 -> return CopilotMint.Unauthorized
            403 -> return CopilotMint.NoSubscription
            in 500..599 -> return CopilotMint.Failed(ModelError.Server(raw.status))
        }
        val root = jsonObject(raw.body) ?: return CopilotMint.Failed(ModelError.BadResponse("Copilot 令牌返回无法识别"))
        if (raw.status != 200) return CopilotMint.Failed(ModelError.BadResponse("HTTP ${raw.status}"))
        val token = root.str("token") ?: return CopilotMint.Failed(ModelError.BadResponse("Copilot 令牌返回缺少令牌"))
        val expiresAt = (root["expires_at"] as? JsonPrimitive)?.longOrNull
        val expiresMs = when {
            expiresAt == null -> clock() + 25 * 60_000
            expiresAt > 1_000_000_000_000L -> expiresAt
            else -> expiresAt * 1000
        }
        val api = apiBase(root["endpoints"] as? JsonObject) ?: return CopilotMint.BadHost
        return CopilotMint.Ok(token, expiresMs, api)
    }

    /** 只接受 https 且主机是 api.githubcopilot.com 或其子域。没给就用默认的。 */
    private fun apiBase(endpoints: JsonObject?): String? {
        val raw = endpoints?.str("api")?.takeIf { it.isNotBlank() } ?: API_BASE
        val u = raw.toHttpUrlOrNull() ?: return null
        if (u.scheme != "https" || u.username.isNotEmpty()) return null
        val host = u.host
        if (host != "api.githubcopilot.com" && !host.endsWith(".githubcopilot.com")) return null
        return "https://$host"
    }

    private fun jsonObject(body: String): JsonObject? = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private sealed interface CopilotMint {
        data class Ok(val token: String, val expiresAtMs: Long, val apiBase: String) : CopilotMint
        data object NoSubscription : CopilotMint
        data object Unauthorized : CopilotMint
        data object BadHost : CopilotMint
        data class Failed(val error: ModelError) : CopilotMint
    }

    companion object {
        const val PROVIDER = "copilot"
        /** 官方 VS Code GitHub Copilot 扩展的公开客户端。不是 Mika 注册的,复用有风险,见界面上的说明。 */
        const val CLIENT_ID = "Iv1.b507a08c87ecfe98"
        const val SCOPE = "read:user"
        const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        const val OAUTH_TOKEN_URL = "https://github.com/login/oauth/access_token"
        const val USER_URL = "https://api.github.com/user"
        const val COPILOT_TOKEN_URL = "https://api.github.com/copilot_internal/v2/token"
        const val VERIFY_FALLBACK = "https://github.com/login/device"
        /** 订阅登录调用的默认接口根。服务端若返回别的 githubcopilot.com 主机,用返回的那个。 */
        const val API_BASE = "https://api.githubcopilot.com"

        /**
         * Copilot 接口要求的公开集成标识(和官方编辑器扩展相同的那一组)。
         * 只加在 Copilot 的令牌和模型请求上,不拿去冒充完成别的协议转换。
         */
        val API_HEADERS: Map<String, String> = mapOf(
            "User-Agent" to "GitHubCopilotChat/0.38.2",
            "Editor-Version" to "vscode/1.110.1",
            "Editor-Plugin-Version" to "copilot-chat/0.38.2",
            "Copilot-Integration-Id" to "vscode-chat",
            "X-GitHub-Api-Version" to "2025-10-01",
        )
    }
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
