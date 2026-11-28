package com.wangzan101.mika.selfcheck

import com.wangzan101.mika.core.selfcheck.Advice
import com.wangzan101.mika.core.selfcheck.CheckStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** 主屏不受打扰:虚拟屏没有自己的焦点时,主屏被动到是已知的系统限制,报部分支持而不是失败(#393、#80)。 */
class MainScreenOutcomeTest {
    @Test fun unchangedMainScreenPasses() {
        val outcome = MainScreenOutcome.of(emptyList(), focusShared = true, who = "Mika")
        assertEquals(CheckStatus.Pass, outcome.status)
    }

    @Test fun changeWithSharedFocusIsKnownLimitNotFailure() {
        val outcome = MainScreenOutcome.of(listOf("actions", "end"), focusShared = true, who = "Mika")
        assertEquals(CheckStatus.Partial, outcome.status)
        assertEquals(Advice.FocusShared, outcome.advice)
        assertEquals("top task changed during: actions, end (virtual display has no own focus)", outcome.detail)
    }

    @Test fun changeWithOwnFocusStillFails() {
        val outcome = MainScreenOutcome.of(listOf("end"), focusShared = false, who = "Mika")
        assertEquals(CheckStatus.Fail, outcome.status)
        assertEquals(Advice.MainScreenChanged, outcome.advice)
    }
}
