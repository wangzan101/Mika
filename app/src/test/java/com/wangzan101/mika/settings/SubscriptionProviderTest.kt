package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.oauth.AccountSession
import com.wangzan101.mika.core.oauth.CopilotAuth
import com.wangzan101.mika.core.oauth.XaiAuth
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

/** 订阅登录存进和 API Key 一样的加密文件,列模型时用访问令牌,不把令牌写进配置。 */
class SubscriptionProviderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val caps = Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = true)

    private fun store(lister: suspend (ProviderConfig.Kind, String, String?, Map<String, String>) -> ModelListResult = { _, _, _, _ ->
        ModelListResult.Ok(emptyList())
    }) = ProviderStore(tmp.root, AesGcmKeyCipher { key }, modelLister = lister)

    @Test fun xaiSessionIsEncryptedAndListingUsesTheAccessToken() = runTest {
        val calls = mutableListOf<Triple<ProviderConfig.Kind, String, String?>>()
        val s = store { kind, url, token, _ ->
            calls += Triple(kind, url, token)
            ModelListResult.Ok(listOf(ListedModel("grok-4")))
        }
        val session = AccountSession(
            provider = XaiAuth.PROVIDER, accountId = "u", label = "a@b.test",
            accessToken = "xai-access-SECRET", refreshToken = "xai-refresh-SECRET",
            expiresAtMs = System.currentTimeMillis() + 3_600_000, apiBase = XaiAuth.API_BASE, clientId = XaiAuth.CLIENT_ID,
            issuer = XaiAuth.ISSUER,
        )
        val p = s.connectAccount(session, listOf(ListedModel("grok-4")))
        assertEquals(ProviderConfig.Kind.Xai, p.kind)
        assertEquals(ProviderStore.XAI_NAME, p.name)
        assertEquals(XaiAuth.API_BASE, p.baseUrl)
        assertEquals(session, s.accountSession(p.id))
        tmp.root.walk().filter(File::isFile).forEach { f ->
            assertFalse(f.name, f.readText().contains("SECRET"))
        }
        s.listModels(p)
        assertEquals(Triple(ProviderConfig.Kind.Xai, XaiAuth.API_BASE, "xai-access-SECRET"), calls.single())
        assertTrue(s.use(ModelRole.Main, p.id, "grok-4", caps))
        // 再登录更新同一家
        s.connectAccount(session.copy(accessToken = "xai-access-2"), listOf(ListedModel("grok-4")))
        assertEquals(1, s.providers.value.count { it.kind == ProviderConfig.Kind.Xai })
        s.signOutAccount(p.id)
        assertFalse(s.accountSession(p.id)!!.signedIn)
        assertEquals("a@b.test", s.accountSession(p.id)!!.label)
    }

    @Test fun copilotListSendsTheIntegrationHeadersAndNotTheSessionJson() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":[{"id":"gpt-4o","model_picker_enabled":true},{"id":"hidden","model_picker_enabled":false}]}"""))
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val result = ProviderStore.listAvailable(ProviderConfig.Kind.Copilot, base, "copilot-access", mapOf("User-Agent" to "Mika/1.2.3"))
            val models = (result as ModelListResult.Ok).models.map { it.id }
            assertEquals(listOf("gpt-4o"), models)
            val req = server.takeRequest()
            assertEquals("Bearer copilot-access", req.getHeader("Authorization"))
            assertEquals("vscode-chat", req.getHeader("Copilot-Integration-Id"))
            assertEquals("GitHubCopilotChat/0.38.2", req.getHeader("User-Agent"))
            val s = store()
            val session = AccountSession(
                provider = CopilotAuth.PROVIDER, accountId = "7", label = "octocat",
                accessToken = "copilot-SECRET", grantToken = "github-SECRET",
                expiresAtMs = System.currentTimeMillis() + 3_600_000, apiBase = base, clientId = CopilotAuth.CLIENT_ID,
            )
            val p = s.connectAccount(session, emptyList())
            assertEquals(ProviderConfig.Kind.Copilot, p.kind)
            assertEquals(base, p.baseUrl)
            tmp.root.walk().filter(File::isFile).forEach { f ->
                val text = f.readText()
                assertFalse(text, text.contains("copilot-SECRET"))
                assertFalse(text, text.contains("github-SECRET"))
            }
        } finally {
            server.shutdown()
        }
    }
}
