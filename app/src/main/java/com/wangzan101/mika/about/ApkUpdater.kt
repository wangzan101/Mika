package com.wangzan101.mika.about

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import java.io.File
import java.util.UUID

enum class DownloadPhase { Idle, Downloading, Paused, Ready, Failed }

data class ApkDownloadState(
    val phase: DownloadPhase = DownloadPhase.Idle,
    val bytes: Long = 0,
    val total: Long = -1,
) {
    val percent: Int? get() = if (total > 0) ((bytes.toDouble() / total) * 100).toInt().coerceIn(0, 100) else null
}

/** 系统负责后台下载；只保存任务编号，进程重启后仍能查询、取消或安装。方法在 IO 线程调用。 */
class ApkUpdater(private val context: Context) {
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("apk-update", Context.MODE_PRIVATE)
    private val directory: File get() = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: error("external files unavailable"), "updates")
    private val file: File? get() = prefs.getString("file", null)?.let { File(directory, it) }

    fun state(release: ReleaseInfo): ApkDownloadState {
        if (prefs.getString("url", null) != release.apkUrl) return ApkDownloadState()
        val id = prefs.getLong("id", -1)
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (cursor.moveToFirst()) {
                fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
                val phase = when (number(DownloadManager.COLUMN_STATUS).toInt()) {
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING -> DownloadPhase.Downloading
                    DownloadManager.STATUS_PAUSED -> DownloadPhase.Paused
                    DownloadManager.STATUS_SUCCESSFUL -> if (file?.isFile == true) DownloadPhase.Ready else DownloadPhase.Failed
                    else -> DownloadPhase.Failed
                }
                return ApkDownloadState(phase, number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR), number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            }
        }
        return ApkDownloadState(DownloadPhase.Failed)
    }

    fun download(release: ReleaseInfo) {
        val url = requireNotNull(release.apkUrl)
        ApkIntegrity.requireOfficialUrl(url)
        if (prefs.getString("url", null) == url && state(release).phase in
            listOf(DownloadPhase.Downloading, DownloadPhase.Paused, DownloadPhase.Ready)) return
        cancel()
        val folder = directory
        check(folder.mkdirs() || folder.isDirectory)
        // remove 的后台清理可能晚于下一次下载，不能复用旧任务的文件名。
        val target = File(folder, "update-${UUID.randomUUID()}.apk")
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Mika ${release.version}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(target))
        val id = manager.enqueue(request)
        if (!prefs.edit().putLong("id", id).putString("url", url).putString("file", target.name).commit()) {
            manager.remove(id)
            error("could not save download")
        }
    }

    fun cancel() {
        val id = prefs.getLong("id", -1)
        if (id != -1L) manager.remove(id)
        val target = file
        check(target == null || !target.exists() || target.delete())
        check(prefs.edit().clear().commit())
    }

    /** 系统安装器最终验证签名并要求用户确认；这里只接受本应用的更高版本和完整附件。 */
    @Suppress("DEPRECATION")
    fun installIntent(release: ReleaseInfo): Intent {
        check(state(release).phase == DownloadPhase.Ready)
        ApkIntegrity.requireOfficialUrl(requireNotNull(release.apkUrl))
        val target = requireNotNull(file)
        ApkIntegrity.verify(target, release.apkSize, release.apkDigest)
        val archive = context.packageManager.getPackageArchiveInfo(target.path, 0) ?: error("invalid APK")
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        check(archive.packageName == context.packageName && archive.longVersionCode > installed.longVersionCode)
        val uri = manager.getUriForDownloadedFile(prefs.getLong("id", -1)) ?: error("missing APK")
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
