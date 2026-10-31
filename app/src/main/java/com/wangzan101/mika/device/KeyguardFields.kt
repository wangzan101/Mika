package com.wangzan101.mika.device

import com.wangzan101.mika.core.device.CredentialKind

/** Android/Redmi 的自绘 PIN 框不声明 editable;仅对已观察到的 SystemUI ID 和类名放行。 */
internal object KeyguardFields {
    fun matches(kind: CredentialKind, id: String, className: String, password: Boolean, editable: Boolean, numeric: Boolean): Boolean {
        if (!password || !id.startsWith("com.android.systemui:id/") || "sim" in id.lowercase()) return false
        val pinView = id == "com.android.systemui:id/pinEntry" && className == "com.android.keyguard.PasswordTextView"
        return when (kind) {
            CredentialKind.Pin -> pinView || editable && numeric
            CredentialKind.Password -> editable && !numeric
        }
    }
}
