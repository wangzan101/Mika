package com.wangzan101.mika.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.feishu.FeishuConfig
import com.wangzan101.mika.feishu.FeishuService
import com.wangzan101.mika.ui.components.PageScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun FeishuScreen(onBack: () -> Unit) {
    val context=LocalContext.current
    val runtime=remember { (context.applicationContext as MikaApp).feishu }
    val scope=rememberCoroutineScope()
    var saved by remember { mutableStateOf(FeishuConfig()) }
    var appId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(false) }
    var mirror by remember { mutableStateOf(false) }
    var binding by remember { mutableStateOf<Pair<String?,String>?>(null) }
    var busy by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    val connected by runtime.connected.collectAsState()
    LaunchedEffect(Unit) {
        saved=withContext(Dispatchers.IO) { runtime.config() }
        appId=saved.appId; enabled=saved.enabled; mirror=saved.mirrorLocal; busy=false
        while (true) {
            binding=withContext(Dispatchers.IO) { runCatching { runtime.binding() }.getOrNull() }
            delay(2000)
        }
    }
    PageScaffold(stringResource(R.string.feishu_title),onBack) {
        Text(stringResource(R.string.feishu_intro))
        OutlinedTextField(appId,{ appId=it },label={ Text(stringResource(R.string.feishu_app_id)) },singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(secret,{ secret=it },label={ Text(stringResource(R.string.feishu_secret)) },
            placeholder={ Text(stringResource(R.string.feishu_secret_keep)) },visualTransformation=PasswordVisualTransformation(),singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(stringResource(R.string.feishu_enable),Modifier.weight(1f))
            Switch(enabled,{ enabled=it },enabled=!busy)
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(stringResource(R.string.feishu_mirror),Modifier.weight(1f))
            Switch(mirror,{ mirror=it },enabled=!busy)
        }
        Text(stringResource(if(connected) R.string.feishu_connected else R.string.feishu_disconnected))
        if (saved.enabled) {
            Text(if(binding?.first != null) stringResource(R.string.feishu_paired)
                 else stringResource(R.string.feishu_pair,binding?.second.orEmpty()))
        }
        Text(stringResource(R.string.feishu_boundary))
        if (failed) Text(stringResource(R.string.feishu_save_error),color=MaterialTheme.colorScheme.error)
        Button(onClick={
            busy=true; failed=false
            scope.launch {
                try {
                    val next=FeishuConfig(enabled,appId.trim(),secret.trim().ifBlank { if(appId.trim()==saved.appId) saved.appSecret else "" },mirror)
                    withContext(Dispatchers.IO) { runtime.configure(next) }
                    saved=next; secret=""
                    if(next.enabled) FeishuService.startIfEnabled(context)
                    else context.stopService(Intent(context,FeishuService::class.java))
                    binding=withContext(Dispatchers.IO) { runtime.binding() }
                } catch (_: Exception) { failed=true }
                finally { busy=false }
            }
        },enabled=!busy) { Text(stringResource(R.string.feishu_save)) }
        if (saved.enabled) TextButton(onClick={
            busy=true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { runtime.resetBinding() }
                    FeishuService.startIfEnabled(context)
                    binding=withContext(Dispatchers.IO) { runtime.binding() }
                } catch (_: Exception) { failed=true }
                finally { busy=false }
            }
        },enabled=!busy) { Text(stringResource(R.string.feishu_reset_binding)) }
        Spacer(Modifier.height(8.dp))
    }
}
