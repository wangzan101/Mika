package com.wangzan101.mika.chat.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.wangzan101.mika.R

/**
 * 任务进行中、没开并行时侧栏里灰掉的入口(别的对话、新建对话、新建项目)点下去要有回应(#839):
 * 弹一句为什么现在不能切换。连点只留最新一条,不排一串。
 */
@Composable
internal fun rememberBusyHint(): () -> Unit {
    val context = LocalContext.current
    return remember(context) {
        var shown: Toast? = null
        {
            shown?.cancel()
            shown = Toast.makeText(context, context.getString(R.string.drawer_busy), Toast.LENGTH_SHORT).also { it.show() }
        }
    }
}
