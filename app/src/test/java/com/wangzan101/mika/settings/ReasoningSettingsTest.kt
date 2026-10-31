package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class ReasoningSettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val caps = Capabilities(toolCalls = true, vision = true, contextTokens = 100_000, reasoning = true)
    private val setups = mutableListOf<ClientSetup>()
    private fun store() = ProviderStore(tmp.root, AesGcmKeyCipher { key }, modelFactory = { cfg, _, _, setup ->
        setups += setup
        object : ChatModel {
            override val name = cfg.model
            override val capabilities = cfg.capabilities
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply = error("not called")
        }
    })
    private fun connected(s: ProviderStore) = s.connect("Responses", ProviderConfig.Kind.OpenAiResponses, "https://example.test/v1", "k",
        listOf(ListedModel("m", reasoningEfforts = listOf("low", "medium", "high")), ListedModel("other", reasoningEfforts = emptyList())))

    @Test fun sameModelCanHaveDifferentEffortPerRoleAndSurvivesReload() {
        val s = store()
        val p = connected(s)
        ModelRole.entries.forEach { assertTrue(s.use(it, p.id, "m", caps)) }
        assertEquals(1, s.list().size)
        assertTrue(s.setReasoningEffort(ModelRole.Main, "high"))
        assertTrue(s.setReasoningEffort(ModelRole.Vision, "medium"))
        assertTrue(s.setReasoningEffort(ModelRole.Light, "low"))
        val reloaded = store()
        ModelRole.entries.forEach { reloaded.modelFor(it) }
        assertEquals(listOf("high", "medium", "low"), setups.map { it.reasoningEffort })
        assertEquals(listOf("low", "medium", "high"), reloaded.provider(p.id)!!.models!!.first().reasoningEfforts)
        assertEquals(emptyList<String>(), reloaded.provider(p.id)!!.models!![1].reasoningEfforts)
        assertTrue(reloaded.setReasoningEffort(ModelRole.Light, null))
        assertNull(store().roles.value.reasoningEffort(ModelRole.Light))
        assertEquals("high", store().roles.value.reasoningEffort(ModelRole.Main))
    }

    @Test fun followingMainInheritsEffortAndChangingModelClearsOnlyItsOwnChoice() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m", caps)
        s.setReasoningEffort(ModelRole.Main, "high")
        assertFalse(s.setReasoningEffort(ModelRole.Light, "low"))
        s.modelFor(ModelRole.Light)
        assertEquals("high", setups.last().reasoningEffort)
        s.use(ModelRole.Light, p.id, "m", caps)
        s.setReasoningEffort(ModelRole.Light, "low")
        s.use(ModelRole.Main, p.id, "other", caps)
        assertNull(s.roles.value.reasoningEffort(ModelRole.Main))
        assertEquals("low", s.roles.value.reasoningEffort(ModelRole.Light))
        s.choose(ModelRole.Light, RoleChoice.SameAsMain)
        assertNull(s.roles.value.reasoningEffort(ModelRole.Light))
        assertFalse(s.roles.value.reasoningEfforts.containsKey(ModelRole.Light))
    }

    @Test fun unsupportedLevelsAreRejectedAndOldSettingsKeepServerDefault() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m", caps)
        assertFalse(s.setReasoningEffort(ModelRole.Main, "max"))
        assertNull(store().roles.value.reasoningEffort(ModelRole.Main))
        val saved = Json.parseToJsonElement(tmp.root.resolve("providers.json").readText()).jsonObject
        assertFalse(saved["uses"]!!.jsonObject["Main"]!!.jsonObject.containsKey("reasoningEffort"))
        store().modelFor(ModelRole.Main)
        assertNull(setups.last().reasoningEffort)
        s.use(ModelRole.Main, p.id, "other", caps)
        assertFalse(s.setReasoningEffort(ModelRole.Main, "low"))
        assertTrue(s.setReasoningEffort(ModelRole.Main, null))
    }

    @Test fun actualProtocolDeterminesWhetherEffortIsOffered() {
        val cfg = ProviderConfig("c", ProviderConfig.Kind.OpenAiCompat, "https://example.test", "m", caps)
        assertNull(ReasoningOptions.of(cfg, null))
        val responses = ListedModel("m", endpoint = ModelEndpoint(ModelProtocol.Responses, "https://example.test"), reasoningEfforts = listOf("low"))
        assertEquals(listOf("low"), ReasoningOptions.of(cfg, responses)!!.levels)
        val direct = cfg.copy(kind = ProviderConfig.Kind.OpenAiResponses)
        assertFalse(ReasoningOptions.of(direct, null)!!.fromProvider)
        assertEquals(emptyList<String>(), ReasoningOptions.of(direct, ListedModel("m", reasoning = false))!!.levels)
        assertNull(ReasoningOptions.of(direct, responses.copy(endpoint = ModelEndpoint(ModelProtocol.Messages, "https://example.test"))))
    }

    @Test fun metadataRefreshKeepsTheSavedChoiceButAllowsResettingAnUnavailableLevel() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m", caps)
        s.setReasoningEffort(ModelRole.Main, "high")
        s.connect("Responses", ProviderConfig.Kind.OpenAiResponses, "https://example.test/v1", "k",
            listOf(ListedModel("m", reasoning = false)))
        val reloaded = store()
        assertEquals("high", reloaded.roles.value.reasoningEffort(ModelRole.Main))
        assertFalse(reloaded.setReasoningEffort(ModelRole.Main, "high"))
        assertTrue(reloaded.setReasoningEffort(ModelRole.Main, null))
        reloaded.modelFor(ModelRole.Main)
        assertNull(setups.last().reasoningEffort)
    }

    @Test fun savedRoleEffortReachesRealResponsesAdapter() = runTest {
        MockWebServer().use { server ->
            server.start()
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key })
            val p = s.connect("Responses", ProviderConfig.Kind.OpenAiResponses, server.url("/v1").toString(), "k",
                listOf(ListedModel("m", reasoningEfforts = listOf("high"))))
            s.use(ModelRole.Main, p.id, "m", caps)
            s.setReasoningEffort(ModelRole.Main, "high")
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"))
            s.modelFor(ModelRole.Main)!!.complete("", emptyList(), emptyList())
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("high", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        }
    }
}
