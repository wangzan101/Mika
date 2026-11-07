package com.wangzan101.mika.ui

import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.balance.BalanceCatalog
import com.wangzan101.mika.settings.balance.BalanceFailure
import com.wangzan101.mika.settings.balance.BalanceLine
import com.wangzan101.mika.settings.balance.BalanceLineKind
import com.wangzan101.mika.settings.balance.BalanceLookup
import com.wangzan101.mika.settings.balance.BalanceOutcome
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
import javax.crypto.KeyGenerator

/** 添加页和详情页怎么触发查询:不联网,余额由测试里的 [BalanceLookup] 给出。 */
@OptIn(ExperimentalCoroutinesApi::class)
class BalanceViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val calls = mutableListOf<Pair<String, String>>()

    private val lookup = BalanceLookup { method, apiKey ->
        calls += method.id to apiKey
        if (apiKey == "bad") BalanceOutcome.Failed(BalanceFailure.Auth, method.experimental)
        else BalanceOutcome.Ready(listOf(BalanceLine(BalanceLineKind.Total, 3.5, "CNY")), method.experimental)
    }

    private fun store() = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mika/test")

    private fun TestScope.vm(store: ProviderStore = store()) =
        ModelSetupViewModel(store, backgroundScope, io = StandardTestDispatcher(testScheduler), balanceLookup = lookup)

    @Test fun draftQueryWaitsUntilTypingPausesAndKeepsTheExperimentalFlag() = runTest {
        val vm = vm()
        vm.setApiKey("sk-live")
        vm.watchDraftBalance()
        assertTrue(vm.state.value.draftBalance?.view is BalanceView.Loading)
        advanceTimeBy(399)
        runCurrent()
        assertTrue("还在输入,不该已经请求", calls.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("deepseek" to "sk-live"), calls)
        val ready = vm.state.value.draftBalance?.view as BalanceView.Ready
        assertEquals(3.5, ready.lines.single().amount, 0.0)
        assertEquals(false, ready.experimental)

        vm.selectPreset("智谱")
        vm.watchDraftBalance()
        advanceTimeBy(ModelSetupViewModel.DRAFT_BALANCE_DELAY_MS)
        runCurrent()
        val zhipu = vm.state.value.draftBalance?.view as BalanceView.Ready
        assertTrue(zhipu.experimental)
        assertEquals("zhipu", calls.last().first)
    }

    @Test fun hiddenAndUnsupportedPresetsDoNotQuery() = runTest {
        val vm = vm()
        vm.selectPreset("Ollama")
        vm.setApiKey("ignored")
        vm.watchDraftBalance()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(BalanceCatalog.shownDraft(vm.state.value.draftBalance, vm.state.value.form.preset, vm.state.value.form.baseUrl, vm.state.value.form.apiKey) is BalanceView.Hidden)

        vm.selectPreset("硅基流动")
        vm.watchDraftBalance()
        advanceTimeBy(1_000)
        runCurrent()
        val shown = BalanceCatalog.shownDraft(vm.state.value.draftBalance, "硅基流动", ProviderPresets.all.single { it.label == "硅基流动" }.baseUrl, "ignored")
        assertTrue(shown is BalanceView.Unsupported)
        assertTrue(calls.isEmpty())
    }

    @Test fun changingTheKeyDropsThePreviousResultUntilTheNewQueryReturns() = runTest {
        val vm = vm()
        vm.setApiKey("sk-1")
        vm.refreshDraftBalance()
        runCurrent()
        assertTrue(vm.state.value.draftBalance?.view is BalanceView.Ready)
        vm.setApiKey("sk-2")
        val shown = BalanceCatalog.shownDraft(vm.state.value.draftBalance, "DeepSeek", "https://api.deepseek.com", "sk-2")
        assertTrue(shown is BalanceView.Loading)
    }

    @Test fun providerDetailUsesTheSavedKeyAndReportsAuthFailure() = runTest {
        val store = store()
        val provider = store.connect("DeepSeek", com.wangzan101.mika.settings.ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", "bad", emptyList())
        val vm = vm(store)
        runCurrent()
        vm.loadBalance(provider.id)
        runCurrent()
        val failed = vm.state.value.providerBalances.getValue(provider.id) as BalanceView.Failed
        assertEquals(BalanceFailure.Auth, failed.failure)
        assertEquals(listOf("deepseek" to "bad"), calls)

        val ollama = store.connect("Ollama", com.wangzan101.mika.settings.ProviderConfig.Kind.OpenAiCompat, "http://127.0.0.1:11434/v1", null, emptyList())
        vm.loadBalance(ollama.id)
        runCurrent()
        assertTrue(vm.state.value.providerBalances.getValue(ollama.id) is BalanceView.Hidden)
        assertEquals(1, calls.size)
    }
}
