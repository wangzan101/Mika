package com.wangzan101.mika.ui

/** 外部分享的原始内容:第一版只接收文字、链接和单张 content:// 图片。 */
internal data class IncomingShare(val text: String, val image: String? = null, val mime: String? = null) {
    companion object {
        /** 在外部入口校验动作和数据类型;不接任意文件路径,也不把多图悄悄截成一张。 */
        fun parse(action: String?, mime: String?, text: String?, image: String?): IncomingShare? {
            return when {
                action == "android.intent.action.SEND" && mime == "text/plain" && !text.isNullOrBlank() ->
                    IncomingShare(text)
                action in listOf("android.intent.action.SEND", "android.intent.action.VIEW") &&
                    mime?.startsWith("image/") == true && image?.startsWith("content://") == true ->
                    IncomingShare(text.orEmpty(), image, mime)
                else -> null
            }
        }
    }
}
