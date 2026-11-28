package com.wangzan101.mika.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 「图片模型」页(`role_image*`、`image_*`、`tool_image*`、`tool_share_image`)的文案,在每种非英语语言里都要有译文:
 * 条目数和占位符都对得上、内容却是英文原文的时候,[StringResourcesTest] 查不出来,9 个语言目录曾整页留着英文。
 * 各语言本来就拼写相同的条目列在 [sameSpelling] 里。
 */
class ImageModelPageTranslationTest {
    /** 图片模型页要翻的条目;通用缩写 `image_protocol`(API)和产品名 `image_protocol_openai` 不在其中。 */
    private val names = setOf(
        "role_image", "role_image_what", "image_hint", "image_enable", "image_enable_note", "image_provider",
        "image_no_providers", "image_model_name", "image_protocol_gemini", "image_supports_edit", "image_limits",
        "image_max_per_task", "image_save_enabled", "image_save_enabled_note", "image_ask_before_save",
        "image_ask_before_generate", "image_test", "image_testing", "image_test_ok", "image_test_failed", "image_footer",
        "tool_image_generate", "tool_image_edit", "tool_image_save", "tool_share_image",
    )

    private val others = listOf(
        "values-zh" to Locale.SIMPLIFIED_CHINESE,
        "values-b+zh+Hant" to Locale.TRADITIONAL_CHINESE,
        "values-ja" to Locale.JAPANESE,
        "values-ko" to Locale.KOREAN,
        "values-es" to Locale.forLanguageTag("es"),
        "values-pt" to Locale.forLanguageTag("pt"),
        "values-ru" to Locale.forLanguageTag("ru"),
        "values-tr" to Locale.forLanguageTag("tr"),
        "values-ar" to Locale.forLanguageTag("ar"),
        "values-fa" to Locale.forLanguageTag("fa"),
        // 印尼语的资源文件夹沿用安卓的旧语言代码 in
        "values-in" to Locale.forLanguageTag("id"),
        "values-vi" to Locale.forLanguageTag("vi"),
    )

    /** 条目名 to 可以与英文拼写相同的语言文件夹。 */
    private val sameSpelling = mapOf(
        "image_model_name" to setOf("values-tr", "values-in"),
    )

    private val en = ResFileTexts.english

    @Test
    fun imageModelPageTextsAreTranslatedEverywhere() {
        assertTrue(names.all { it in en.strings })
        val leftInEnglish = others.flatMap { (folder, locale) ->
            val texts = ResFileTexts(folder, locale)
            names.filter { name -> folder !in sameSpelling.getOrElse(name) { emptySet() } && texts.strings[name] == en.strings[name] }
                .map { name -> "$folder/$name" }
        }
        assertEquals(emptyList<String>(), leftInEnglish)
    }
}
