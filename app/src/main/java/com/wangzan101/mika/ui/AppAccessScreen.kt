package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.core.access.*
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AppAccessScreen(onBack: () -> Unit, classifyApps: Boolean = true) {
    val app = LocalContext.current.applicationContext as MikaApp
    val store = app.appAccess
    val state by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmingClassify by remember { mutableStateOf(false) }
    var recommending by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    DisposableEffect(store) { val protection = store.protectUi(); onDispose { protection.close() } }

    // 推荐只含还在允许范围内的金融应用:已经禁用的不再重复出现。
    val recommended = apps.filter { (pkg, _) ->
        state.classifications[pkg]?.category == AppCategory.Finance && pkg !in state.blocked
    }

    fun save(action: () -> Unit) { runCatching(action).onFailure { error = it.message ?: "保存失败" } }
    suspend fun loadApps() {
        apps = withContext(Dispatchers.IO) { launchableApps(app.packageManager, app.packageName) }
    }
    suspend fun refresh(classifyNow: Boolean) {
        busy = true
        error = null
        try {
            loadApps()
            if (!classifyNow || !classifyApps) return
            val latest = store.current()
            if (!latest.available) return
            val pending = apps.filter { latest.classifications[it.first] == null }
            if (pending.isEmpty()) return
            val model = app.providers.modelFor(ModelRole.Light) ?: app.providers.modelFor(ModelRole.Main)
            if (model == null) {
                error = "没有可用的模型，应用列表已刷新，但无法分类。"
                return
            }
            val classifier = AppClassifier(model, app.providers.modelFor(ModelRole.Main))
            for (batch in pending.chunked(30)) {
                val classified = withContext(Dispatchers.IO) { classifier.classify(batch) }
                save {
                    store.classify(batch.associate { it.first to (classified[it.first] ?: AppClassification(AppCategory.Unknown)) })
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "刷新失败"
        } finally {
            busy = false
        }
    }
    // 打开页面只读取本机应用清单,不把任何内容发给模型。分类要用户点刷新并确认后才做。
    LaunchedEffect(Unit) {
        try {
            loadApps()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "读取应用列表失败"
        }
    }

    PageScaffold("应用访问", onBack) {
        Text("新应用默认允许。分类只用于整理，禁用状态按单个应用保存。任意 shell 仍可执行，本版限制标准手机工具。")
        Text("分类需要把应用名称和包名发送给 AI 模型；点「刷新应用列表」时会先征求你的同意。")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!state.available) {
            Text("权限文件无法读取，标准手机访问已暂停。重置将清除已有权限和分类。")
            TextButton({ save { store.reset() } }) { Text("重置应用访问设置") }
        } else {
            OutlinedTextField(search, { search = it }, label = { Text("搜索名称或包名") }, singleLine = true)
            TextButton({
                if (busy) return@TextButton
                val pending = apps.filter { state.classifications[it.first] == null }
                if (classifyApps && pending.isNotEmpty()) confirmingClassify = true else scope.launch { refresh(false) }
            }, enabled = !busy) { Text(if (busy) "正在分类…" else "刷新应用列表") }
            TextButton({ recommending = true }) { Text(if (recommended.isEmpty()) "推荐禁用" else "推荐禁用 · ${recommended.size}") }
            TextButton({ save { store.allowAll() } }) { Text("全部允许") }
            AppCategory.entries.forEach { category ->
                val rows = apps.filter { (pkg, label) ->
                    (state.classifications[pkg]?.category ?: AppCategory.Unknown) == category &&
                        (pkg.contains(search, true) || label.contains(search, true))
                }
                if (rows.isNotEmpty()) {
                    GroupLabel("${category.label} · ${rows.size}")
                    if (category == AppCategory.Game || category == AppCategory.Social) Text("自动操作可能带来账号限制风险，可按需禁用。")
                    GroupCard {
                        rows.forEachIndexed { index, (pkg, label) ->
                            ListRow(null, label, pkg, first = index == 0,
                                detail = state.classifications[pkg]?.reason, onClick = { editing = pkg }) {
                                Text(if (pkg in state.blocked) "已禁用" else "允许")
                                Switch(pkg !in state.blocked, { allowed -> save { store.setBlocked(pkg, !allowed) } })
                            }
                            if (pkg in state.shareAllowed) TextButton({ save { store.setShareAllowed(pkg, false) } }) { Text("撤销 $label 的屏幕分享许可") }
                        }
                    }
                }
            }
        }
    }
    if (confirmingClassify) {
        AlertDialog(
            onDismissRequest = { confirmingClassify = false },
            title = { Text("把应用列表发送给 AI？") },
            text = { Text("刷新会读取已安装的应用，并把应用名称和包名发送给你配置的 AI 模型，用于判断风险分类。应用里的内容不会被发送。") },
            confirmButton = { TextButton({
                confirmingClassify = false
                scope.launch { refresh(true) }
            }) { Text("继续并分类") } },
            dismissButton = { TextButton({ confirmingClassify = false }) { Text("取消") } },
        )
    }
    if (recommending) {
        AlertDialog(
            onDismissRequest = { recommending = false },
            title = { Text("推荐禁用金融交易应用") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text("以下应用被识别为金融交易，且当前仍允许 Mika 自动操作。可以逐个禁用，或一次全部禁用。")
                    if (recommended.isEmpty()) {
                        Text("没有需要禁用的应用。", Modifier.padding(top = 12.dp))
                    }
                    recommended.forEach { (pkg, label) ->
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) { Text(label) }
                            TextButton({ save { store.blockPackages(setOf(pkg)) } }) { Text("禁用") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    { save { store.blockPackages(recommended.map { it.first }.toSet()) }; recommending = false },
                    enabled = recommended.isNotEmpty(),
                ) { Text("全部禁用") }
            },
            dismissButton = { TextButton({ recommending = false }) { Text("取消") } },
        )
    }
    editing?.let { pkg ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("修改分类") }, text = {
            Column { AppCategory.entries.forEach { category ->
                TextButton({ save { store.classify(mapOf(pkg to AppClassification(category, "用户选择", manual = true))) }; editing = null }) { Text(category.label) }
            } }
        }, confirmButton = { TextButton({ editing = null }) { Text("取消") } })
    }
}
