package com.wangzan101.mika.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateMap

/** 按会话保留输入文字:外部分享打开新对话后,回到原对话仍能找到没发出的草稿。 */
@Composable
internal fun rememberConversationInput(conversationId: String?): MutableState<String> {
    val drafts = rememberSaveable(saver = mapSaver(
        save = { it.toMap() },
        restore = { it.mapValues { entry -> entry.value as String }.toList().toMutableStateMap() },
    )) { mutableStateMapOf<String, String>() }
    val key = conversationId.orEmpty()
    return remember(drafts, key) {
        object : MutableState<String> {
            override var value: String
                get() = drafts[key].orEmpty()
                set(value) {
                    if (value.isEmpty()) drafts.remove(key) else drafts[key] = value
                }
            override fun component1() = value
            override fun component2(): (String) -> Unit = { value = it }
        }
    }
}
