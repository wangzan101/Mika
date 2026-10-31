package com.wangzan101.mika.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.about.ApkDownloadState
import com.wangzan101.mika.about.ApkUpdater
import com.wangzan101.mika.about.DownloadPhase
import com.wangzan101.mika.about.ReleaseInfo
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun UpdateDownloadControls(release: ReleaseInfo) {
    val context = LocalContext.current
    val updater = remember { ApkUpdater(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var state by remember(release.apkUrl) { mutableStateOf(ApkDownloadState()) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
                refresh++
            }
        }
    }

    fun install() = run {
        val intent = withContext(Dispatchers.IO) { updater.installIntent(release) }
        context.startActivity(intent)
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install()
    }

    LaunchedEffect(release.apkUrl, refresh) {
        try {
            do {
                state = withContext(Dispatchers.IO) { updater.state(release) }
                if (state.phase !in listOf(DownloadPhase.Downloading, DownloadPhase.Paused)) break
                delay(1000)
            } while (true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }

    UpdateDownloadActions(state, busy, failed,
        onDownload = { run { withContext(Dispatchers.IO) { updater.download(release) } } },
        onCancel = { run { withContext(Dispatchers.IO) { updater.cancel() } } },
        onInstall = {
            if (context.packageManager.canRequestPackageInstalls()) install()
            else run {
                permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            }
        },
    )
}

@Composable
internal fun UpdateDownloadActions(
    state: ApkDownloadState,
    busy: Boolean,
    failed: Boolean,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    val active = state.phase == DownloadPhase.Downloading || state.phase == DownloadPhase.Paused
    val text = when {
        busy -> stringResource(R.string.about_update_working)
        state.phase == DownloadPhase.Paused -> stringResource(R.string.about_update_paused)
        active -> state.percent?.let { stringResource(R.string.about_update_progress, it) }
            ?: stringResource(R.string.about_update_downloading)
        state.phase == DownloadPhase.Ready -> stringResource(R.string.about_update_install)
        state.phase == DownloadPhase.Failed -> stringResource(R.string.about_update_retry)
        else -> stringResource(R.string.about_update_download_in_app)
    }
    MikaButton(text, if (state.phase == DownloadPhase.Ready) onInstall else onDownload,
        Modifier.fillMaxWidth().padding(top = 14.dp), enabled = !busy && !active)
    if (active || state.phase == DownloadPhase.Ready) {
        MikaButton(stringResource(R.string.about_update_cancel), onCancel,
            Modifier.fillMaxWidth().padding(top = 10.dp), kind = ButtonKind.Tonal, enabled = !busy)
    }
    Text(stringResource(if (failed || state.phase == DownloadPhase.Failed) R.string.about_update_error else R.string.about_update_install_hint),
        Modifier.padding(top = 8.dp), style = MikaType.Caption, color = MikaTheme.colors.ink3)
}
