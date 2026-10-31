package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class ModelContextSettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val caps = Capabilities(true, true, 400_000, true)
    private fun store() = ProviderStore(tmp.root, AesGcmKeyCipher { key }, modelFactory = { cfg, _, _, _ ->
        object : StreamingChatModel {
            override val name = cfg.model
            override val capabilities = cfg.capabilities
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
                ModelReply(listOf(Part.Text("ok")), StopReason.EndTurn, Usage(1, 1))
            override suspend fun completeStreaming(system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit): ModelReply {
                onText("ok")
                return complete(system, messages, tools)
            }
        }
    })
    private fun connected(s: ProviderStore, name: String = "ChatGPT") =
        s.connect(name, ProviderConfig.Kind.ChatGpt, "https://api.openai.com/v1", null,
            listOf(ListedModel("m", contextTokens = 500_000), ListedModel("other", contextTokens = 200_000)))

    @Test fun settingsSurviveReloadModelSwitchingAndUnusedConfigPruning() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m", caps)
        val custom = ModelContextSettings(1_000_000, 600_000, ThresholdMode.Tokens)
        assertTrue(s.setModelContextSettings(p.id, "m", custom))
        s.use(ModelRole.Main, p.id, "other", caps)
        assertTrue(s.list().none { it.model == "m" })
        val reloaded = store()
        assertEquals(custom, reloaded.provider(p.id)!!.contextSettings["m"])
        reloaded.use(ModelRole.Main, p.id, "m", caps)
        val main = reloaded.modelFor(ModelRole.Main)!!
        assertEquals(1_000_000, main.capabilities.contextTokens)
        assertEquals(0.6, main.capabilities.compactAt, 0.0)
        assertEquals(0.6, reloaded.modelFor(ModelRole.Light)!!.capabilities.compactAt, 0.0)
        assertTrue(reloaded.setModelContextSettings(p.id, "m", ModelContextSettings()))
        assertEquals(caps.contextTokens, store().modelFor(ModelRole.Main)!!.capabilities.contextTokens)
        assertFalse(store().provider(p.id)!!.contextSettings.containsKey("m"))
    }

    @Test fun providerAndModelSettingsStayIsolatedAndSharedRolesInherit() = runTest {
        val s = store()
        val p = connected(s)
        val second = connected(s, "Other account")
        s.use(ModelRole.Main, p.id, "m", caps)
        s.use(ModelRole.Vision, second.id, "m", caps)
        s.use(ModelRole.Light, p.id, "other", caps)
        val custom = ModelContextSettings(900_000, 75)
        assertTrue(s.setModelContextSettings(p.id, "m", custom))
        assertEquals(900_000, s.modelFor(ModelRole.Main)!!.capabilities.contextTokens)
        assertEquals(caps.contextTokens, s.modelFor(ModelRole.Vision)!!.capabilities.contextTokens)
        assertEquals(caps.contextTokens, s.modelFor(ModelRole.Light)!!.capabilities.contextTokens)
        s.choose(ModelRole.Light, RoleChoice.SameAsMain)
        assertEquals(0.75, s.modelFor(ModelRole.Light)!!.capabilities.compactAt, 0.0)
        val streaming = s.modelFor(ModelRole.Main) as StreamingChatModel
        val text = mutableListOf<String>()
        assertEquals("m", streaming.name)
        assertEquals(Usage(1, 1), streaming.completeStreaming("", emptyList(), emptyList(), text::add).usage)
        assertEquals(listOf("ok"), text)
    }

    @Test fun metadataRefreshDoesNotChangeSavedWindowOrRewriteSettings() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m", caps)
        val saved = ModelContextSettings(threshold = 300_000, mode = ThresholdMode.Tokens)
        assertTrue(s.setModelContextSettings(p.id, "m", saved))
        assertFalse(s.setModelContextSettings(p.id, "m", ModelContextSettings(threshold = 360_001, mode = ThresholdMode.Tokens)))
        assertFalse(s.setModelContextSettings("missing", "m", ModelContextSettings()))
        assertFalse(s.setModelContextSettings(p.id, "missing", ModelContextSettings()))
        s.rememberModels(p.id, listOf(ListedModel("m", contextTokens = 200_000)))
        assertEquals(caps.contextTokens, s.modelFor(ModelRole.Main)!!.capabilities.contextTokens)
        assertEquals(saved, store().provider(p.id)!!.contextSettings["m"])
        assertEquals(0.75, s.modelFor(ModelRole.Main)!!.capabilities.compactAt, 0.0)
        val explicit = ModelContextSettings(800_000, 600_000, ThresholdMode.Tokens)
        s.setModelContextSettings(p.id, "m", explicit)
        connected(s)
        assertEquals(explicit, store().provider(p.id)!!.contextSettings["m"])
        assertEquals(800_000, store().modelFor(ModelRole.Main)!!.capabilities.contextTokens)
    }

    @Test fun oldSettingsRetainDefaultsAndCorruptEntriesDoNotDiscardProviders() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m", caps)
        assertEquals(0.6, store().modelFor(ModelRole.Main)!!.capabilities.compactAt, 0.0)
        val file = tmp.root.resolve("providers.json")
        val original = file.readText()
        for (entry in listOf(
            """{"windowTokens":0}""", """{"windowTokens":2147483648}""",
            """{"threshold":{"mode":"percent","value":100}}""",
            """{"threshold":{"mode":"tokens","value":0}}""",
            """{"windowTokens":500,"threshold":{"mode":"tokens","value":500}}""",
            """{"threshold":{"mode":"unknown","value":60}}""", "null",
        )) {
            file.writeText(original.replace("\"services\":[{", "\"services\":[{\"contextSettings\":{\"m\":$entry},"))
            val reloaded = store()
            assertNotNull(entry, reloaded.provider(p.id))
            assertEquals(entry, caps.contextTokens, reloaded.modelFor(ModelRole.Main)!!.capabilities.contextTokens)
            assertEquals(entry, 0.6, reloaded.modelFor(ModelRole.Main)!!.capabilities.compactAt, 0.0)
        }
    }

    @Test fun reselectedSmallerModelCapsRuntimeThresholdWithoutErasingSavedValue() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m", caps)
        val saved = ModelContextSettings(threshold = 300_000, mode = ThresholdMode.Tokens)
        s.setModelContextSettings(p.id, "m", saved)
        s.use(ModelRole.Main, p.id, "other", caps)
        s.use(ModelRole.Main, p.id, "m", caps.copy(contextTokens = 200_000))
        assertEquals(0.9, s.modelFor(ModelRole.Main)!!.capabilities.compactAt, 0.0)
        assertEquals(saved, store().provider(p.id)!!.contextSettings["m"])
    }
}
