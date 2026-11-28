package com.wangzan101.mika.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 「朗读」页(`role_voice_what`、`voice_*`)和说话、音频工具名(`tool_speak`、`tool_audio_*`、`tool_share_audio`)的文案,
 * 在每种非英语语言里都要有译文:条目数和占位符都对得上、内容却是英文原文的时候,[StringResourcesTest] 查不出来,
 * 9 个语言目录曾整页留着英文。工具名还会出现在模型的工具清单里,不只是在设置页。
 */
class ReadAloudPageTranslationTest {
    /** 朗读页要翻的条目;通用缩写 `voice_protocol`(API)和产品名 `voice_protocol_openai`、`voice_protocol_gemini` 不在其中。 */
    private val names = setOf(
        "role_voice_what", "voice_enable", "voice_enable_note", "voice_name", "voice_speed", "voice_auto_read",
        "voice_speak_tool", "voice_max_chars", "voice_test", "voice_testing", "voice_test_ok", "voice_test_failed",
        "voice_footer", "voice_playing", "voice_loading", "voice_play_failed", "voice_save_enabled", "voice_save_note",
        "voice_ask_before_save", "tool_speak", "tool_audio_generate", "tool_audio_save", "tool_share_audio",
        "tool_audio_transcribe",
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

    private val en = ResFileTexts.english

    @Test
    fun readAloudPageTextsAreTranslatedEverywhere() {
        assertTrue(names.all { it in en.strings })
        val leftInEnglish = others.flatMap { (folder, locale) ->
            val texts = ResFileTexts(folder, locale)
            names.filter { name -> texts.strings[name] == en.strings[name] }.map { name -> "$folder/$name" }
        }
        assertEquals(emptyList<String>(), leftInEnglish)
    }
}
