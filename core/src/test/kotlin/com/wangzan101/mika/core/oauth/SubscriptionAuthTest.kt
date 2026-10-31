package com.wangzan101.mika.core.oauth

import com.wangzan101.mika.core.model.BearerToken
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.OpenAiCompatModel
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import com.wangzan101.mika.core.model.Author

class SubscriptionAuthTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private val origin get() = server.url("/").toString().trimEnd('/')
    private val host get() = server.hostName

    private fun json(code: Int = 200, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test fun devicePollWaitsThenReturnsTokensAndDropsErrorBodies() = runTest {
        val secret = "device-secret-SHOULD-NOT-LEAK"
        val token = "access-secret-SHOULD-NOT-LEAK"
        var now = 0L
        val client = DeviceCodeClient(http, "$origin/device", "$origin/token", "client", "openid", setOf(host))
        server.enqueue(json(body = """{"device_code":"$secret","user_code":"ABCD-1234","verification_uri":"$origin/verify","verification_uri_complete":"$origin/verify?user_code=ABCD-1234","expires_in":30,"interval":1}"""))
        val grant = client.start()
        assertEquals("ABCD-1234", grant.challenge.userCode)
        assertFalse(grant.challenge.toString(), grant.challenge.toString().contains(secret))
        server.enqueue(json(400, """{"error":"authorization_pending","error_description":"token=$token"}"""))
        server.enqueue(json(400, """{"error":"slow_down","error_description":"token=$token"}"""))
        server.enqueue(json(body = """{"access_token":"$token","refresh_token":"r","expires_in":10,"scope":"openid"}"""))
        val slept = mutableListOf<Long>()
        val got = awaitDeviceGrant(grant, now = { now }, sleep = { slept += it; now += it }) { client.poll(it) }
        assertEquals(token, got.accessToken)
        assertEquals(listOf(1_000L, 6_000L), slept)
        val denied = runCatching {
            server.enqueue(json(400, """{"error":"access_denied","error_description":"token=$token device=$secret"}"""))
            awaitDeviceGrant(grant, now = { 0 }, sleep = {}) { client.poll(it) }
        }.exceptionOrNull() as AccountSignInException
        assertEquals(AccountSignInFailure.Denied, denied.reason)
        assertFalse(denied.message.orEmpty(), denied.message.orEmpty().contains(token))
        assertFalse(denied.message.orEmpty(), denied.message.orEmpty().contains(secret))
    }

    @Test fun badIssuerIsRefusedBeforeAnyRequest() {
        assertFalse(OauthUrls.allowed("http://evil.example/token", setOf("evil.example")))
        assertFalse(OauthUrls.allowed("https://user:pw@auth.x.ai/token", setOf("auth.x.ai")))
        assertFalse(OauthUrls.allowed("https://evil.example/token", setOf("auth.x.ai")))
        assertTrue(OauthUrls.allowed("https://auth.x.ai/oauth2/token", setOf("auth.x.ai")))
        val e = runCatching { OauthUrls.check("https://evil.example/x", setOf("auth.x.ai")) }.exceptionOrNull()
        assertTrue(e is AccountSignInException && (e as AccountSignInException).reason == AccountSignInFailure.BadIssuer)
    }

    @Test fun xaiDiscoveryRejectsForeignEndpoints() = runTest {
        val auth = xai()
        server.enqueue(json(body = """{"issuer":"$origin","device_authorization_endpoint":"https://evil.example/device","token_endpoint":"$origin/oauth2/token"}"""))
        val e = runCatching { auth.discover() }.exceptionOrNull() as AccountSignInException
        assertEquals(AccountSignInFailure.BadIssuer, e.reason)
        assertEquals(1, server.requestCount)
        assertFalse(e.message.orEmpty(), e.message.orEmpty().contains("evil-token"))
    }

    @Test fun xaiSignInUsesDeviceCodeAndDoesNotLogTokens() = runTest {
        val access = "xai-access-SECRET"
        val refresh = "xai-refresh-SECRET"
        var now = 5_000L
        val auth = xai()
        val opened = mutableListOf<String>()
        server.enqueue(discovery())
        server.enqueue(json(body = """{"device_code":"dev-SECRET","user_code":"WXYZ-9999","verification_uri":"$origin/device","verification_uri_complete":"$origin/device?user_code=WXYZ-9999","expires_in":60,"interval":1}"""))
        server.enqueue(json(400, """{"error":"authorization_pending"}"""))
        server.enqueue(json(body = """{"access_token":"$access","refresh_token":"$refresh","expires_in":3600,"id_token":"${jwt()}","scope":"openid email offline_access grok-cli:access api:access"}"""))
        server.enqueue(json(body = """{"sub":"user-9","email":"a@b.test"}"""))
        val session = auth.signIn(
            openBrowser = { opened += it },
            onChallenge = { assertEquals("WXYZ-9999", it.userCode) },
            sleep = { now += it },
            clock = { now },
        )
        assertEquals(XaiAuth.PROVIDER, session.provider)
        assertEquals("user-9", session.accountId)
        assertEquals("a@b.test", session.label)
        assertEquals(access, session.accessToken)
        assertEquals(XaiAuth.API_BASE, session.apiBase)
        assertTrue(opened.single().contains("user_code=WXYZ-9999"))
        assertFalse(opened.single(), opened.single().contains("dev-SECRET"))
        assertFalse(opened.single(), opened.single().contains(access))
        assertEquals(5, server.requestCount)
    }

    @Test fun xaiRefreshKeepsTheNewRefreshToken() = runTest {
        val auth = xai()
        val session = AccountSession(
            provider = XaiAuth.PROVIDER, accountId = "u", accessToken = "old", refreshToken = "refresh-SECRET",
            expiresAtMs = 0, clientId = XaiAuth.CLIENT_ID, issuer = origin,
        )
        server.enqueue(json(body = """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":10}"""))
        val ok = auth.refresh(session) as OauthTokenResult.Ok
        assertEquals("new-access", ok.tokens.accessToken)
        assertEquals("new-refresh", ok.tokens.refreshToken)
        val req = server.takeRequest()
        assertEquals("/oauth2/token", req.path)
        assertTrue(req.body.readUtf8().contains("refresh-SECRET"))
    }

    @Test fun copilotSignInMintsApiTokenAndRejectsForeignApiHost() = runTest {
        val github = "gh-token-SECRET"
        val api = "copilot-token-SECRET"
        val auth = copilot()
        server.enqueue(json(body = """{"device_code":"dev-SECRET","user_code":"ABCD-1234","verification_uri":"https://github.com/login/device","verification_uri_complete":"https://github.com/login/device?user_code=ABCD-1234","expires_in":60,"interval":5}"""))
        server.enqueue(json(body = """{"error":"authorization_pending"}"""))
        server.enqueue(json(body = """{"access_token":"$github","scope":"read:user"}"""))
        server.enqueue(json(body = """{"login":"octocat","id":7}"""))
        server.enqueue(json(body = """{"token":"$api","expires_at":2000000000,"endpoints":{"api":"https://api.individual.githubcopilot.com"}}"""))
        val opened = mutableListOf<String>()
        val session = auth.signIn(openBrowser = { opened += it }, onChallenge = {}, sleep = {}, clock = { 0 })
        assertEquals("7", session.accountId)
        assertEquals("octocat", session.label)
        assertEquals(github, session.grantToken)
        assertEquals(api, session.accessToken)
        assertEquals("https://api.individual.githubcopilot.com", session.apiBase)
        assertEquals(2_000_000_000_000L, session.expiresAtMs)
        assertTrue(opened.single().startsWith("https://github.com/login/device"))
        assertTrue(opened.single(), opened.single().contains("user_code=ABCD-1234"))
        assertFalse(opened.single(), opened.single().contains(github))
        assertFalse(opened.single(), opened.single().contains(api))
        val mint = List(server.requestCount) { server.takeRequest() }.last()
        assertTrue(mint.path.orEmpty(), mint.path.orEmpty().contains("copilot_internal"))
        assertEquals("Bearer $github", mint.getHeader("Authorization"))
        assertEquals("vscode-chat", mint.getHeader("Copilot-Integration-Id"))
    }

    @Test fun copilotRejectsForeignApiHostWithoutEchoingTokens() = runTest {
        val github = "gh-token-SECRET"
        val api = "copilot-token-SECRET"
        val auth = copilot()
        server.enqueue(json(body = """{"device_code":"d","user_code":"AAAA-1111","verification_uri":"https://github.com/login/device","expires_in":60,"interval":5}"""))
        server.enqueue(json(body = """{"access_token":"$github"}"""))
        server.enqueue(json(body = """{"login":"octocat","id":7}"""))
        server.enqueue(json(body = """{"token":"$api","expires_at":2000000000,"endpoints":{"api":"https://evil.example"}}"""))
        val bad = runCatching {
            auth.signIn(openBrowser = {}, onChallenge = {}, sleep = {}, clock = { 0 })
        }.exceptionOrNull() as AccountSignInException
        assertEquals(AccountSignInFailure.BadIssuer, bad.reason)
        assertFalse(bad.message.orEmpty(), bad.message.orEmpty().contains(api))
        assertFalse(bad.message.orEmpty(), bad.message.orEmpty().contains(github))
    }

    @Test fun copilotWithoutSubscriptionFailsCleanly() = runTest {
        val auth = copilot()
        server.enqueue(json(body = """{"device_code":"d","user_code":"AAAA-1111","verification_uri":"https://github.com/login/device","expires_in":60,"interval":5}"""))
        server.enqueue(json(body = """{"access_token":"gh"}"""))
        server.enqueue(json(body = """{"login":"octocat","id":1}"""))
        server.enqueue(json(403, """{"message":"no copilot token=SHOULD-NOT-LEAK"}"""))
        val e = runCatching { auth.signIn({}, {}, sleep = {}, clock = { 0 }) }.exceptionOrNull() as AccountSignInException
        assertEquals(AccountSignInFailure.NoSubscription, e.reason)
        assertFalse(e.message.orEmpty(), e.message.orEmpty().contains("SHOULD-NOT-LEAK"))
    }

    @Test fun accountTokensRefreshOnceAndClearOnTerminalRejection() = runTest {
        var now = 10_000L
        var stored = AccountSession(XaiAuth.PROVIDER, accessToken = "old", refreshToken = "r", expiresAtMs = 0)
        val tokens = AccountTokens(
            refresh = {
                OauthTokenResult.Ok(OauthTokens("new", "r2", expiresInSec = 100))
            },
            load = { stored },
            save = { stored = it },
            now = { now },
        )
        assertEquals("new", tokens.get(fresh = false))
        assertEquals("new", stored.accessToken)
        assertEquals("r2", stored.refreshToken)
        now = stored.expiresAtMs
        stored = stored.copy(accessToken = "keep")
        var calls = 0
        val again = AccountTokens(
            refresh = { calls++; OauthTokenResult.Rejected("invalid_grant", terminal = true) },
            load = { stored },
            save = { stored = it },
            now = { now },
        )
        val e = runCatching { again.get(fresh = false) }.exceptionOrNull()
        assertTrue(e is ModelError.SignInExpired)
        assertEquals(1, calls)
        assertFalse(stored.signedIn)
    }

    @Test fun compatModelRetries401WithAFreshBearerAndNotWithAnApiKey() = runTest {
        server.enqueue(json(401, """{"error":{"message":"expired"}}"""))
        server.enqueue(json(body = """{"choices":[{"message":{"content":"pong"}}]}"""))
        val bearer = object : BearerToken {
            override val refreshable = true
            override suspend fun get(fresh: Boolean) = if (fresh) "fresh-token" else "stale-token"
        }
        val caps = Capabilities(toolCalls = true, vision = false, contextTokens = 8000, reasoning = false)
        val model = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), null, "gpt-4o", caps, http, token = bearer)
        val reply = model.complete("s", listOf(Message(Role.User, listOf(Part.Text("hi")), Author.Mika)), emptyList())
        assertTrue(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.contains("pong"))
        assertEquals("Bearer stale-token", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer fresh-token", server.takeRequest().getHeader("Authorization"))
        server.enqueue(json(401, """{"error":{"message":"no"}}"""))
        val keyed = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "sk", "m", caps, http)
        val denied = runCatching {
            keyed.complete("s", listOf(Message(Role.User, listOf(Part.Text("hi")), Author.Mika)), emptyList())
        }.exceptionOrNull()
        assertTrue(denied is ModelError.Auth)
        assertEquals(1, server.requestCount - 2)
    }

    @Test fun idClaimsAreDisplayOnlyAndIgnoreGarbage() {
        assertEquals("user-1" to "a@b.test", XaiAuth.idClaims(jwt()))
        assertEquals("" to "", XaiAuth.idClaims("not-a-jwt"))
        assertNull(AccountSession.fromJson("not json"))
    }

    private fun xai() = XaiAuth(http, issuer = origin, issuerHosts = setOf(host), verifyHosts = setOf(host))

    private fun copilot() = CopilotAuth(
        http,
        deviceCodeUrl = "$origin/login/device/code",
        oauthTokenUrl = "$origin/login/oauth/access_token",
        userUrl = "$origin/user",
        copilotTokenUrl = "$origin/copilot_internal/v2/token",
        oauthHosts = setOf(host, "github.com"),
        apiHosts = setOf(host),
    )

    private fun discovery() = json(
        body = """{"issuer":"$origin","device_authorization_endpoint":"$origin/oauth2/device/code","token_endpoint":"$origin/oauth2/token","userinfo_endpoint":"$origin/userinfo"}""",
    )

    private fun jwt(): String {
        fun part(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        return part("""{"alg":"none"}""") + "." + part("""{"sub":"user-1","email":"a@b.test"}""") + ".sig"
    }
}
