package com.wangzan101.mika.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.mcp.McpActivity
import com.wangzan101.mika.mcp.McpRuntime
import com.wangzan101.mika.mcp.McpServerState
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowActions
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 设置 › 电脑连接(MCP):先是经 USB 的那种(电脑用 adb 打开,这里显示开着没有、能关掉,并写出电脑上要跑的命令),
 * 再是局域网那种的开关;局域网那种打开时列出连接地址、访问令牌(复制、换一个)、客户端配置片段。
 * 有一种开着就显示一点点动静(最近连过几个客户端、最后一次请求的时间)。地址和动静每两秒重读一次。
 */
@Composable
fun McpScreen(runtime: McpRuntime, onBack: () -> Unit) {
    val context = LocalContext.current
    val enabled by runtime.settings.enabled.collectAsState()
    val usbOn by runtime.usb.enabled.collectAsState()
    val usbCommand = remember { runtime.usbCommand() }
    val state by runtime.state.collectAsState()
    var addresses by remember { mutableStateOf<List<String>>(emptyList()) }
    var activity by remember { mutableStateOf(McpActivity()) }
    var token by remember { mutableStateOf<String?>(null) }
    var confirmRegenerate by remember { mutableStateOf(false) }
    LaunchedEffect(enabled, usbOn) {
        if (!enabled && !usbOn) return@LaunchedEffect
        if (enabled) token = withContext(Dispatchers.IO) { runCatching { runtime.settings.token() }.getOrNull() }
        while (true) {
            if (enabled) addresses = withContext(Dispatchers.IO) { McpRuntime.addresses() }
            activity = runtime.activity
            delay(2_000)
        }
    }
    val port = (state as? McpServerState.Listening)?.port ?: runtime.settings.port

    PageScaffold(stringResource(R.string.mcp_title), onBack) {
        GroupLabel(stringResource(R.string.mcp_usb_group))
        GroupCard {
            ListRow(MikaIcons.Link, stringResource(if (usbOn) R.string.mcp_usb_on else R.string.mcp_usb_title),
                stringResource(if (usbOn) R.string.mcp_usb_on_sub else R.string.mcp_usb_off_sub), first = true,
                tone = if (usbOn) TileTone.Ok else TileTone.Neutral, onClick = { runtime.setUsbEnabled(!usbOn) }) {
                MikaSwitch(usbOn, { runtime.setUsbEnabled(it) }, Modifier.testTag("mcp-usb"))
            }
        }
        FooterNote(stringResource(R.string.mcp_usb_foot, usbCommand), Modifier.clickable { copy(context, usbCommand) })

        GroupLabel(stringResource(R.string.mcp_network_group))
        GroupCard {
            ListRow(MikaIcons.Desktop, stringResource(R.string.mcp_network_title), stringResource(R.string.mcp_sub), first = true,
                onClick = { runtime.setEnabled(!enabled) }) {
                MikaSwitch(enabled, { runtime.setEnabled(it) }, Modifier.testTag("mcp-enabled"))
            }
        }
        FooterNote(stringResource(R.string.mcp_foot))
        if (enabled || usbOn) {
            GroupLabel(stringResource(R.string.mcp_status))
            GroupCard {
                val (title, tone) = when (val s = state) {
                    is McpServerState.Listening -> stringResource(R.string.mcp_listening, s.port) to TileTone.Ok
                    is McpServerState.Failed -> stringResource(R.string.mcp_failed, s.message) to TileTone.Bad
                    McpServerState.Off -> stringResource(R.string.mcp_starting) to TileTone.Neutral
                }
                val detail = activity.lastRequestAtMs?.let { stringResource(R.string.mcp_activity, activity.recentClients, clock(it)) }
                    ?: stringResource(R.string.mcp_no_requests)
                ListRow(MikaIcons.Check.takeIf { tone == TileTone.Ok } ?: MikaIcons.Exclaim, title, detail, first = true, tone = tone)
            }
        }
        if (enabled) {

            GroupLabel(stringResource(R.string.mcp_address))
            GroupCard {
                if (addresses.isEmpty()) ListRow(MikaIcons.Link, stringResource(R.string.mcp_no_address), first = true)
                addresses.forEachIndexed { i, a ->
                    val url = McpRuntime.url(a, port)
                    ListRow(MikaIcons.Link, url, first = i == 0, onClick = { copy(context, url) })
                }
            }
            FooterNote(stringResource(R.string.mcp_address_foot))

            GroupLabel(stringResource(R.string.mcp_token))
            GroupCard {
                ListRow(MikaIcons.Key, token?.let { it.take(6) + "…" + it.takeLast(4) } ?: "…", first = true)
                RowActions {
                    MikaButton(stringResource(R.string.mcp_copy), { token?.let { copy(context, it) } }, size = ButtonSize.Small, enabled = token != null)
                    MikaButton(stringResource(R.string.mcp_regenerate), { confirmRegenerate = true }, kind = ButtonKind.Tonal, size = ButtonSize.Small)
                }
            }

            val config = McpRuntime.clientConfig(McpRuntime.url(addresses.firstOrNull() ?: "<phone-address>", port), token ?: "<token>")
            GroupLabel(stringResource(R.string.mcp_config))
            GroupCard {
                Text(config, Modifier.fillMaxWidth().padding(16.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp,
                    color = MikaTheme.colors.ink2)
                RowActions {
                    MikaButton(stringResource(R.string.mcp_copy_config), { copy(context, config) }, size = ButtonSize.Small, enabled = token != null)
                }
            }
            FooterNote(stringResource(R.string.mcp_config_foot))
        }
    }

    if (confirmRegenerate) AlertDialog(
        onDismissRequest = { confirmRegenerate = false },
        title = { Text(stringResource(R.string.mcp_regenerate_title)) },
        text = { Text(stringResource(R.string.mcp_regenerate_body)) },
        confirmButton = { TextButton({ confirmRegenerate = false; token = runtime.regenerateToken() }) { Text(stringResource(R.string.mcp_regenerate_confirm)) } },
        dismissButton = { TextButton({ confirmRegenerate = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun clock(ms: Long): String =
    DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Mika", text))
    Toast.makeText(context, context.getString(R.string.mcp_copied), Toast.LENGTH_SHORT).show()
}
