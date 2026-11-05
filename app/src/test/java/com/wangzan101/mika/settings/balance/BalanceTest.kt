package com.wangzan101.mika.settings.balance

import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.usage.AllowanceMoney
import com.wangzan101.mika.core.usage.AllowanceSource
import com.wangzan101.mika.core.usage.ProviderAllowance
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** 添加页和详情页怎么选查询：跟用量页同一条路由，不再各写一套地址。 */
class BalanceTest {
    @Test fun liveQueriesAreTheUsagePageSources() {
        assertEquals(AllowanceSource.DeepSeek, source("DeepSeek"))
        assertEquals(AllowanceSource.Kimi, source("Kimi"))
        assertEquals(AllowanceSource.SiliconFlow, source("硅基流动"))
        assertEquals(AllowanceSource.OpenRouter, source("OpenRouter"))
    }

    @Test fun providersWithoutAUsagePageQueryOnlyOfferTheDashboard() {
        val step = BalanceCatalog.targetFor(preset("阶跃星辰"))
        assertNull(step.source)
        assertEquals("https://platform.stepfun.com/", (step.idle(hasKey = true) as BalanceView.Unsupported).dashboard)

        val zhipu = BalanceCatalog.targetFor(preset("智谱"))
        assertNull(zhipu.source)
        assertEquals("https://bigmodel.cn/finance/overview", (zhipu.idle(hasKey = true) as BalanceView.Unsupported).dashboard)

        val mini = BalanceCatalog.targetFor(preset("MiniMax"))
        assertNull(mini.source)
        assertTrue(mini.idle(hasKey = true) is BalanceView.Unsupported)
    }

    @Test fun selfHostedAndAccountLoginsStayHidden() {
        assertTrue(BalanceCatalog.targetFor(preset("Ollama")).idle(hasKey = true) is BalanceView.Hidden)
        assertTrue(BalanceCatalog.targetFor(preset("LM Studio")).idle(hasKey = true) is BalanceView.Hidden)
        val chatGpt = Provider("p", "ChatGPT", ProviderConfig.Kind.ChatGpt, "https://api.openai.com/v1")
        assertTrue(BalanceCatalog.targetFor(chatGpt).idle(hasKey = true) is BalanceView.Hidden)
        val xai = Provider("p", "Grok", ProviderConfig.Kind.Xai, "https://api.x.ai/v1")
        assertTrue(BalanceCatalog.targetFor(xai).idle(hasKey = true) is BalanceView.Hidden)
        assertTrue(
            BalanceCatalog.targetForDraft("Ollama", ProviderConfig.Kind.OpenAiCompat, "http://127.0.0.1:11434/v1", "Ollama", selfHostedPreset = true)
                .idle(hasKey = true) is BalanceView.Hidden,
        )
    }

    @Test fun aResultForAnotherKeyIsNotShown() {
        val ready = BalanceView.Ready(
            ProviderAllowance.Balance(listOf(AllowanceMoney(BigDecimal("3.5"), "CNY"))),
            AllowanceSource.DeepSeek,
            "https://platform.deepseek.com/usage",
        )
        val stale = BalanceSnapshot(token("DeepSeek", "sk-1"), ready)
        val shown = BalanceCatalog.shownDraft(
            stale, "DeepSeek", ProviderConfig.Kind.OpenAiCompat, deepSeekUrl(), "sk-2", "DeepSeek", selfHostedPreset = false,
        )
        assertTrue(shown is BalanceView.Loading)
        assertTrue(BalanceCatalog.shownDraft(stale, "DeepSeek", ProviderConfig.Kind.OpenAiCompat, deepSeekUrl(), "sk-1", "DeepSeek", false) is BalanceView.Ready)
    }

    @Test fun anEmptyKeyAsksForOneInsteadOfQuerying() {
        val shown = BalanceCatalog.shownDraft(
            null, "DeepSeek", ProviderConfig.Kind.OpenAiCompat, deepSeekUrl(), "  ", "DeepSeek", false,
        )
        assertTrue(shown is BalanceView.NeedKey)
    }

    @Test fun sharedClientErrorsKeepTheirMeaning() {
        assertEquals(BalanceFailure.Auth, ModelError.Auth().toBalanceFailure())
        assertEquals(BalanceFailure.Network, ModelError.Network().toBalanceFailure())
        assertEquals(BalanceFailure.Unreadable, ModelError.BadResponse().toBalanceFailure())
        assertEquals(BalanceFailure.Http(503), ModelError.Server(503).toBalanceFailure())
        assertEquals(BalanceFailure.Unavailable, ModelError.RateLimited(1_000).toBalanceFailure())
        assertEquals(BalanceFailure.Unavailable, IllegalArgumentException("bad header").toBalanceFailure())
    }

    @Test fun aZeroWalletBesideAPositiveBalanceIsNotDepleted() {
        val mixed = ProviderAllowance.Balance(listOf(money("0.00", "CNY"), money("1.23", "USD")))
        val empty = ProviderAllowance.Balance(listOf(money("0", "CNY")))
        val limit = ProviderAllowance.KeyLimit(money("0", "USD"))
        assertFalse(mixed.depleted())
        assertTrue(empty.depleted())
        assertTrue(limit.depleted())
        assertFalse(ProviderAllowance.KeyLimit(null).depleted())
    }

    private fun source(label: String) = BalanceCatalog.targetFor(preset(label)).source

    private fun preset(label: String): Provider {
        val item = ProviderPresets.all.single { it.label == label }
        return Provider(label, label, item.kind, item.baseUrl)
    }

    private fun deepSeekUrl() = ProviderPresets.all.single { it.label == "DeepSeek" }.baseUrl

    private fun token(preset: String, apiKey: String) =
        BalanceCatalog.token(preset, ProviderConfig.Kind.OpenAiCompat, deepSeekUrl(), apiKey, preset)

    private fun money(amount: String, currency: String) = AllowanceMoney(BigDecimal(amount), currency)
}
