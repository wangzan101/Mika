package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingShareTest {
    /** 分享来的网址和附言原样保留,后续由用户编辑后发送。 */
    @Test fun textAndLinksAreDraftContent() {
        val text = "看看这篇文章\nhttps://example.com/a?q=1"
        assertEquals(IncomingShare(text), IncomingShare.parse(SEND, "text/plain", text, null))
    }

    /** 分享和“打开方式”两条入口都能带入同一张图片,并保留来源附言。 */
    @Test fun sharesAndOpensOneContentImage() {
        for (action in listOf(SEND, VIEW)) {
            assertEquals(IncomingShare("帮我看看", IMAGE, "image/png"),
                IncomingShare.parse(action, "image/png", "帮我看看", IMAGE))
        }
        assertEquals(IncomingShare("", IMAGE, "image/jpeg"), IncomingShare.parse(SEND, "image/jpeg", null, IMAGE))
    }

    /** 外部入口不接受私有文件路径、网页打开请求和多图,防止越权读取或悄悄丢掉图片。 */
    @Test fun unsupportedInputsAreRejected() {
        assertNull(IncomingShare.parse(SEND, "image/png", null, "file:///data/data/private/image.png"))
        assertNull(IncomingShare.parse(VIEW, "text/html", null, "https://example.com"))
        assertNull(IncomingShare.parse("android.intent.action.SEND_MULTIPLE", "image/png", null, IMAGE))
        assertNull(IncomingShare.parse(SEND, "application/pdf", "附件", IMAGE))
        assertNull(IncomingShare.parse(SEND, "text/plain", "  ", null))
        assertNull(IncomingShare.parse(SEND, "image/png", null, null))
    }

    private companion object {
        const val SEND = "android.intent.action.SEND"
        const val VIEW = "android.intent.action.VIEW"
        const val IMAGE = "content://photos/shared/123"
    }
}
