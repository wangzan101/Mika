package com.wangzan101.mika.core.agent

/**
 * 回复正文里漏出来的工具调用:模型想调工具,服务端却没把它解析成结构化的调用,调用原样当文字回来了
 * (例如推理服务的工具解析偶尔没认出 Qwen 的 `<tool_call><function=…>`)。这样的回复不是回答,不能交给用户。
 *
 * 只认常见开源模型的工具调用标记,而且正文里还要出现这一步给模型的某个工具名(整词):
 * 用户问的就是这些格式时,回答里提到标记本身不算。按标记和工具名判断,不解析也不执行文字里的调用。
 */
internal object LeakedToolCall {
    /** 各家模型写工具调用时用的标记:Qwen / Hermes、Llama、Mistral、DeepSeek、Kimi。 */
    private val MARKERS = listOf(
        "<tool_call>", "<function=",
        "<|python_tag|>",
        "[TOOL_CALLS]",
        "<｜tool▁calls▁begin｜>", "<｜tool▁call▁begin｜>",
        "<|tool_calls_section_begin|>", "<|tool_call_begin|>",
    )

    /**
     * [text] 里漏出了对 [toolNames] 中某个工具的调用时返回那个工具名;没有为 null。
     * 只看第一个调用标记之后出现的工具名,取最靠前的那个:标记前面的话里出现 wait、find 这类和工具同名的词不算。
     */
    fun find(text: String, toolNames: Collection<String>): String? {
        val start = MARKERS.mapNotNull { m -> text.indexOf(m).takeIf { it >= 0 } }.minOrNull() ?: return null
        return toolNames.filter { it.isNotEmpty() }
            .mapNotNull { name -> wholeWord(name).find(text, start)?.let { name to it.range.first } }
            .minByOrNull { it.second }?.first
    }

    private fun wholeWord(name: String) = Regex("(?<![A-Za-z0-9_])" + Regex.escape(name) + "(?![A-Za-z0-9_])")
}
