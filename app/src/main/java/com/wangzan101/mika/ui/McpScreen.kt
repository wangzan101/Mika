package com.wangzan101.mika.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.mcp.McpHttpServer
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.theme.MikaIcons

@Composable
fun McpScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val connection = remember { (context.applicationContext as MikaApp).mcp }
    val state by connection.state.collectAsState()
    var copied by remember { mutableStateOf(false) }
    val port = McpHttpServer.DEFAULT_PORT
    val command = "adb forward tcp:$port tcp:$port"
    val url = "http://127.0.0.1:$port/mcp"

    PageScaffold(stringResource(R.string.mcp_title), onBack) {
        GroupCard {
            ListRow(MikaIcons.Code, stringResource(R.string.mcp_enable), stringResource(R.string.mcp_subtitle), first = true) {
                MikaSwitch(state.enabled, onCheckedChange = { if (it) connection.enable() else connection.disable() })
            }
        }
        FooterNote(stringResource(R.string.mcp_explain))
        state.error?.let { FooterNote(stringResource(R.string.mcp_error, it)) }
        state.token?.let { token ->
            GroupLabel(stringResource(R.string.mcp_connect))
            FooterNote(stringResource(R.string.mcp_connect_steps, command, url))
            GroupCard {
                ListRow(MikaIcons.Copy, stringResource(R.string.mcp_copy), first = true, onClick = {
                    val config = "[mcp_servers.mika]\nurl = \"$url\"\nbearer_token_env_var = \"MIKA_MCP_TOKEN\"\n"
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Mika MCP", config))
                    copied = true
                }) { }
                ListRow(MikaIcons.Copy, stringResource(R.string.mcp_copy_token), onClick = {
                    val clip = ClipData.newPlainText("MIKA_MCP_TOKEN", token).apply {
                        description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                    }
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                    copied = true
                }) { }
            }
            if (copied) FooterNote(stringResource(R.string.mcp_copied))
        }
    }
}
