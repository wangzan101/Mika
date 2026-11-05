package com.wangzan101.mika.ui

import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.usage.AllowanceMoney
import com.wangzan101.mika.core.usage.AllowanceSource
import com.wangzan101.mika.core.usage.ProviderAllowance
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.balance.BalanceCatalog
import com.wangzan101.mika.settings.balance.BalanceFailure
import com.wangzan101.mika.settings.balance.BalanceView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.math.BigDecimal
import javax.crypto.KeyGenerator

/** 添加页和详情页怎么触发查询：不联网，余额由测试里的查询函数给出。 */
@OptIn(ExperimentalCoroutinesApi::class)
class BalanceViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val calls = mutableListOf<Pair<AllowanceSource, String>>()

    private val query: suspend (AllowanceSource, String) -> ProviderAllowance = { source, apiKey ->
        calls += source to apiKey
        if (apiKey == "bad") throw ModelError.Auth()
        ProviderAllowance.Balance(listOf(AllowanceMoney(BigDecimal("3.5"), "CNY")))
    }

    private fun store() = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mika/test")

    private fun TestScope.vm(store: ProviderStore = store()) =
        ModelSetupViewModel(store, backgroundScope, io = StandardTestDispatcher(testScheduler), queryAllowance = query)

    private fun ModelSetupUi.shownDraft(): BalanceView {
        val form = form
        return BalanceCatalog.shownDraft(
            draftBalance, form.preset, form.kind, form.baseUrl, form.apiKey, form.providerName, form.isSelfHosted,
        )
    }

    @Test fun draftQueryWaitsUntilTypingPausesAndUsesTheUsagePageSource() = runTest {
        val vm = vm()
        vm.setApiKey("sk-live")
        vm.watchDraftBalance()
        assertTrue(vm.state.value.draftBalance?.view is BalanceView.Loading)
        advanceTimeBy(399)
        runCurrent()
        assertTrue("还在输入，不该已经请求", calls.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(AllowanceSource.DeepSeek to "sk-live"), calls)
        val ready = vm.state.value.draftBalance?.view as BalanceView.Ready
        assertEquals(BigDecimal("3.5"), (ready.allowance as ProviderAllowance.Balance).amounts.single().amount)
        assertEquals(AllowanceSource.DeepSeek, ready.source)
    }

    @Test fun hiddenAndDashboardOnlyPresetsDoNotQuery() = runTest {
        val vm = vm()
        vm.selectPreset("Ollama")
        vm.setApiKey("ignored")
        vm.watchDraftBalance()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(vm.state.value.shownDraft() is BalanceView.Hidden)

        vm.selectPreset("阶跃星辰")
        vm.watchDraftBalance()
        advanceTimeBy(1_000)
        runCurrent()
        val shown = vm.state.value.shownDraft()
        assertTrue(shown is BalanceView.Unsupported)
        assertEquals("https://platform.stepfun.com/", (shown as BalanceView.Unsupported).dashboard)
        assertTrue(calls.isEmpty())
    }

    @Test fun siliconFlowUsesTheSharedQuery() = runTest {
        val vm = vm()
        vm.selectPreset("硅基流动")
        vm.setApiKey("sf-key")
        vm.refreshDraftBalance()
        runCurrent()
        assertEquals(listOf(AllowanceSource.SiliconFlow to "sf-key"), calls)
        assertTrue(vm.state.value.draftBalance?.view is BalanceView.Ready)
    }

    @Test fun changingTheKeyDropsThePreviousResultUntilTheNewQueryReturns() = runTest {
        val vm = vm()
        vm.setApiKey("sk-1")
        vm.refreshDraftBalance()
        runCurrent()
        assertTrue(vm.state.value.draftBalance?.view is BalanceView.Ready)
        vm.setApiKey("sk-2")
        val shown = vm.state.value.shownDraft()
        assertTrue(shown is BalanceView.Loading)
    }

    @Test fun providerDetailUsesTheSavedKeyAndReportsAuthFailure() = runTest {
        val store = store()
        val provider = store.connect("DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", "bad", emptyList())
        val vm = vm(store)
        runCurrent()
        vm.loadBalance(provider.id)
        runCurrent()
        val failed = vm.state.value.providerBalances.getValue(provider.id) as BalanceView.Failed
        assertEquals(BalanceFailure.Auth, failed.failure)
        assertEquals(AllowanceSource.DeepSeek, failed.source)
        assertEquals(listOf(AllowanceSource.DeepSeek to "bad"), calls)

        val ollama = ProviderPresets.all.single { it.label == "Ollama" }
        val local = store.connect(ollama.label, ollama.kind, ollama.baseUrl, null, emptyList())
        vm.loadBalance(local.id)
        runCurrent()
        assertTrue(vm.state.value.providerBalances.getValue(local.id) is BalanceView.Hidden)
        assertEquals(1, calls.size)
    }
}
