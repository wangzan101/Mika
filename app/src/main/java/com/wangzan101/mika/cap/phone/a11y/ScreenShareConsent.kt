package com.wangzan101.mika.cap.phone.a11y

import android.view.Gravity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.settings.AppAccessStore
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 用户主动分享的授权浮层;不会先读取应用内容。 */
internal suspend fun requestScreenShare(service: ScreenAccessibility, store: AppAccessStore, pkg: String, label: String): Boolean =
    suspendCancellableCoroutine { continuation ->
        val protection = store.protectUi()
        var window: OverlayWindow? = null
        fun finish(choice: Int) {
            if (!continuation.isActive) return
            val allowed = runCatching {
                when (choice) {
                    1 -> store.setShareAllowed(pkg, true)
                    2 -> store.setBlocked(pkg, false)
                }
                choice != 0
            }.getOrDefault(false)
            window?.close()
            protection.close()
            continuation.resume(allowed)
        }
        window = service.overlays().open(OverlayPlacement(Gravity.CENTER), onOutside = { finish(0) }) {
            MikaTheme {
                Surface { Column(Modifier.widthIn(max = 340.dp).padding(20.dp)) {
                    Text("分享 $label 的屏幕")
                    Text("此应用已在黑名单。屏幕分享许可仅用于你主动分享，自动读取和操作仍被禁止。")
                    TextButton({ finish(1) }) { Text("允许仅分享屏幕") }
                    TextButton({ finish(2) }) { Text("移出黑名单") }
                    TextButton({ finish(0) }) { Text("取消分享") }
                } }
            }
        }
        if (window == null) finish(0)
        continuation.invokeOnCancellation { window?.close(); protection.close() }
    }
