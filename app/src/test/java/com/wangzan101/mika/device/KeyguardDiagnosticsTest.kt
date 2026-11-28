package com.wangzan101.mika.device

import com.wangzan101.mika.core.device.CredentialKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyguardDiagnosticsTest {
    @Test fun fieldKeepsOnlyBoundedTechnicalProperties() {
        val diagnostic = KeyguardDiagnostics.field(
            id = "com.android.systemui:id/pinEntry secret text\nnext",
            className = "com.android.keyguard.PasswordTextView",
            editable = false,
            numeric = false,
            untyped = false,
            digitPad = true,
            focused = true,
            match = CredentialKind.Pin,
        )
        assertTrue("com.android.systemui:id/pinEntry" in diagnostic)
        assertTrue("class=com.android.keyguard.PasswordTextView" in diagnostic)
        assertTrue("match=pin" in diagnostic)
        assertFalse("secret text" in diagnostic)
        assertFalse('\n' in diagnostic)
    }

    @Test fun namesAreAsciiAndLengthLimited() {
        val name = KeyguardDiagnostics.name("x".repeat(200) + "秘密")
        assertEquals(160, name.length)
        assertTrue(name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "_.\$:/-" })
        assertEquals("unknown", KeyguardDiagnostics.name(null))
    }

    @Test fun windowExplainsWhyNoFieldOrPinPadMatched() {
        assertEquals(
            "window[index=2,focused=false,password_nodes=0,digits=10,pad_keys=9,pin_layout=false]",
            KeyguardDiagnostics.window(2, false, 0, 10, 9, false),
        )
    }

    @Test fun sourceSummaryTellsWhichWindowListWasEmpty() {
        assertEquals(
            "keyguard_source[source=active_root,display_ids=none,legacy_windows=0,active_root=com.android.systemui]",
            KeyguardDiagnostics.source("active_root", emptyList(), 0, "com.android.systemui"),
        )
        assertEquals(
            "keyguard_source[source=none,display_ids=0/2,legacy_windows=3,active_root=none]",
            KeyguardDiagnostics.source("none", listOf(0, 2), 3, null),
        )
        val redacted = KeyguardDiagnostics.source("none", emptyList(), 0, "密码 user\nnext")
        assertFalse('\n' in redacted)
        assertFalse("密码" in redacted)
    }

    @Test fun windowInventoryContainsOnlyTechnicalProperties() {
        assertEquals(
            "window_info[index=0,type=2032,package=com.android.systemui,focused=true,active=true]",
            KeyguardDiagnostics.windowInfo(0, 2032, "com.android.systemui", focused = true, active = true),
        )
        val redacted = KeyguardDiagnostics.windowInfo(1, 1, "user text\nnext", focused = false, active = false)
        assertFalse("user text" in redacted)
        assertFalse('\n' in redacted)
    }
}
