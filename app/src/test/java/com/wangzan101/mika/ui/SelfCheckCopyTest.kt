package com.wangzan101.mika.ui

import com.wangzan101.mika.about.Diagnostics
import com.wangzan101.mika.core.selfcheck.Advice
import com.wangzan101.mika.core.selfcheck.CheckResult
import com.wangzan101.mika.core.selfcheck.CheckStatus
import com.wangzan101.mika.core.selfcheck.DeviceFacts
import com.wangzan101.mika.core.selfcheck.SelfCheck
import com.wangzan101.mika.core.selfcheck.SelfCheckReport
import com.wangzan101.mika.core.selfcheck.SelfCheckState
import com.wangzan101.mika.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SelfCheckCopyTest {
    @get:Rule val texts = ChineseTexts()

    private val jargon = listOf("shell", "Binder", "uid", "adb", "SDK", "ABI")

    @Test
    fun everyCheckStatusAndAdviceHasPlainText() {
        val all = SelfCheck.entries.map(SelfCheckCopy::name) + CheckStatus.entries.map(SelfCheckCopy::status) + Advice.entries.map(SelfCheckCopy::advice)
        all.forEach { t ->
            assertTrue("empty copy", t.isNotBlank())
            jargon.forEach { j -> assertFalse("「$j」 in: $t", t.contains(j)) }
        }
        assertEquals(SelfCheck.entries.size, SelfCheck.entries.map(SelfCheckCopy::name).toSet().size)
    }

    @Test
    fun lineNamesWhatIsMissingOrGivesTheAdvice() {
        assertEquals("跳过:要先通过「内置助手」", SelfCheckCopy.line(CheckResult(SelfCheck.VIRTUAL_DISPLAY, CheckStatus.Skipped, "needs helper (fail)", blockedBy = SelfCheck.HELPER)))
        assertEquals(SelfCheckCopy.advice(Advice.FramesStopped), SelfCheckCopy.line(CheckResult(SelfCheck.STAYS_ALIVE, CheckStatus.Fail, "frames stopped after 6 s", Advice.FramesStopped)))
        assertEquals("正常", SelfCheckCopy.line(CheckResult(SelfCheck.DEVICE, CheckStatus.Pass, "Android 14")))
        assertEquals("正在检查…", SelfCheckCopy.line(CheckResult(SelfCheck.DEVICE, CheckStatus.Running)))
    }

    /** 报告走「复制诊断信息」同一套脱敏:IP、端口、邮箱、像凭据的值都不出现。 */
    @Test
    fun reportIsSanitizedLikeDiagnostics() {
        val facts = DeviceFacts("Acme", "Phone 7", "14", 34, listOf("arm64-v8a"), 8L shl 30, 1080, 2400, true, "0.1.21", "abc1234")
        val state = SelfCheckState(
            SelfCheckState.Phase.Done,
            SelfCheck.entries.map { CheckResult(it, CheckStatus.Pass, "ok") }.toMutableList().also {
                it[SelfCheck.HELPER.ordinal] = CheckResult(SelfCheck.HELPER, CheckStatus.Fail, "connect 192.168.1.20:37011 failed, token=abcdef123456 me@example.com", Advice.Error)
            },
            facts, 30_000,
        )
        val text = SelfCheckReport.render(state, Diagnostics::sanitize)
        assertTrue(text.startsWith("Mika self-check 0.1.21 (build abc1234)\n"))
        assertFalse(text.contains("192.168"))
        assertFalse(text.contains("me@example.com"))
        assertFalse(text.contains("abcdef123456"))
        assertTrue(text.contains("3 helper: FAIL — connect <ip> failed"))
    }
}
