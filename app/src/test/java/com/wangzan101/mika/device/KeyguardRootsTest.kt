package com.wangzan101.mika.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyguardRootsTest {
    private val systemUi = { w: String -> w.startsWith("systemui") }

    @Test fun displayListWinsWhenItHasSystemUi() {
        var fellBack = false
        val pick = KeyguardRoots.pick(listOf("app", "systemui:bouncer"), { fellBack = true; listOf("systemui:legacy") },
            { fellBack = true; "systemui:active" }, systemUi)
        assertEquals(KeyguardRoots.Source.Display, pick.source)
        assertEquals(listOf("systemui:bouncer"), pick.roots)
        assertTrue(!fellBack)
    }

    @Test fun emptyDisplayListFallsBackToLegacyWindows() {
        val pick = KeyguardRoots.pick(emptyList(), { listOf("systemui:legacy") }, { "systemui:active" }, systemUi)
        assertEquals(KeyguardRoots.Source.Legacy, pick.source)
        assertEquals(listOf("systemui:legacy"), pick.roots)
    }

    @Test fun noWindowsAtAllFallsBackToActiveSystemUiRoot() {
        val pick = KeyguardRoots.pick(emptyList(), { emptyList() }, { "systemui:active" }, systemUi)
        assertEquals(KeyguardRoots.Source.ActiveRoot, pick.source)
        assertEquals(listOf("systemui:active"), pick.roots)
    }

    @Test fun activeRootOfAnotherAppIsNeverUsed() {
        val pick = KeyguardRoots.pick(listOf("app"), { listOf("app") }, { "app:active" }, systemUi)
        assertEquals(KeyguardRoots.Source.None, pick.source)
        assertTrue(pick.roots.isEmpty())
    }
}
