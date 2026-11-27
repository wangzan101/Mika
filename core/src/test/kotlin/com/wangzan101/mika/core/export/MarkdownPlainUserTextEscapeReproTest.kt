package com.wangzan101.mika.core.export

import com.wangzan101.mika.core.export.markdown.MarkdownExportBackend
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * 用户正文的字面文字整段经 [MarkdownExportBackend] 写进导出的 .md 时,
 * 行首的 -、+、N.、N) 与成对 ~~、整行 === 曾被原样放行,
 * 被渲染器解释成无序/有序列表、删除线和 setext 标题,导出文件与对话原文不符。
 */
class MarkdownPlainUserTextEscapeReproTest {
    @Test fun userLiteralListMarkersStrikethroughAndSetextSurviveEscaped() = kotlinx.coroutines.runBlocking {
        val userText = "- 上午 9 点开会\n+ 备选时间\n1. 第一条\n2) 第二条\n~~划线文字~~\n标题行\n===\n  - 缩进列表\n  1. 缩进有序\n   ===\n  \n- 空行后列表"
        val document = ExportDocument(
            meta = ExportMeta("c1", "标题", 1_000L, "test", "en"),
            blocks = listOf(
                ExportBlock.UserMessage(ExportIds.user(0), userText, null, null, emptyList(), emptyList(), null),
            ),
            assets = emptyList(),
        )
        val root = createTempDir()
        try {
            MarkdownExportBackend().render(document, ExportPresentation(), Reader, DirectoryExportSink(root)) {}
            val text = File(root, "document.md").readText()
            assertTrue("用户正文应出现在导出文件里", text.contains("上午 9 点开会"))
            val lines = text.lines()
            assertTrue("行首 - 应转义为 \\-,实际:$lines", lines.any { it == "\\- 上午 9 点开会" })
            assertTrue("行首 + 应转义为 \\+", lines.any { it == "\\+ 备选时间" })
            assertTrue("行首 1. 应转义句点", lines.any { it == "1\\. 第一条" })
            assertTrue("行首 2) 应转义右括号", lines.any { it == "2\\) 第二条" })
            assertTrue("成对 ~~ 应转义为 \\~\\~", lines.any { it == "\\~\\~划线文字\\~\\~" })
            assertTrue("整行 ===(setext 下划线)应转义", lines.any { it == "\\===" })
            assertFalse("行首 - 不应原样写成无序列表", lines.any { it.startsWith("- 上午") })
            assertFalse("行首 + 不应原样写成无序列表", lines.any { it.startsWith("+ 备选") })
            assertFalse("N. 行不应原样写成有序列表", lines.any { it == "1. 第一条" })
            assertFalse("N) 行不应原样写成有序列表", lines.any { it == "2) 第二条" })
            assertFalse("~~ 不应原样保留", lines.any { it == "~~划线文字~~" })
            assertFalse("=== 不应原样保留", lines.any { it == "===" })
            assertTrue("1–3 空格缩进的 - 也应转义", lines.any { it == "  \\- 缩进列表" })
            assertTrue("1–3 空格缩进的 N. 也应转义", lines.any { it == "  1\\. 缩进有序" })
            assertTrue("1–3 空格缩进的 === 也应转义", lines.any { it == "   \\===" })
            assertTrue("缩进空行后仍应识别新行首", lines.any { it == "\\- 空行后列表" })
        } finally {
            root.deleteRecursively()
        }
    }

    private object Reader : ExportAssetReader {
        override suspend fun open(asset: ExportAsset): InputStream = ByteArrayInputStream(ByteArray(0))
    }
}
