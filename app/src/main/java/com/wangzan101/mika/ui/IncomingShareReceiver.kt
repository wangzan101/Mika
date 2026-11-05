package com.wangzan101.mika.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.sandbox.Attachments
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.chat.AttachFilesProvider
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.record.Redactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** 从系统 Intent 读取外部分享;只接受约定的数据,不信任调用方夹带的内部状态或私有文件地址。 */
internal fun readIncomingShare(context: Context, intent: Intent): IncomingShare? {
    return try {
        val image = if (intent.action == Intent.ACTION_VIEW) intent.data else {
            val stream = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            stream ?: intent.clipData?.getItemAt(0)?.uri
        }
        if (image != null && context.packageManager.resolveContentProvider(image.authority.orEmpty().substringAfter('@'), 0)
                ?.applicationInfo?.uid == android.os.Process.myUid()) return null
        // 图片查看器可能只传内容地址,没有 MIME 时向来源 Provider 查询。
        val mime = intent.type ?: image?.let { context.contentResolver.getType(it) }
        if (intent.action == Intent.ACTION_SEND && mime?.startsWith("image/") == true &&
            (intent.clipData?.itemCount ?: 0) > 1) return null
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT) ?: intent.clipData?.getItemAt(0)?.text
        IncomingShare.parse(intent.action, mime, text?.toString(), image?.toString())
    } catch (_: RuntimeException) {
        // 来源 App 传入的字段类型或 Parcelable 不合法时只提示,不让主界面崩溃。
        null
    }
}

/** 主界面接收分享:后台暂存图片,然后把内容交给新对话的原输入区;全程不调用模型。 */
class IncomingShareReceiver(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application as MikaApp
    private var receiving: Job? = null
    internal val opened = Channel<String>(Channel.BUFFERED)
    internal val notices = Channel<Int>(Channel.BUFFERED)

    init {
        // 旋转复用本实例;进程重建时只恢复尚未交付的分享,不覆盖已交付草稿里的用户编辑。
        val id = saved.get<String>("pending")
        if (id != null) prepare(id, IncomingShare(saved.get<String>("text").orEmpty(), saved["source"], saved["mime"]))
    }

    /** 每次新的系统分享各分配新编号;新的请求会取消尚未交付的旧分享,不覆盖已打开的草稿。 */
    internal fun receive(content: IncomingShare?) {
        if (content == null) {
            notices.trySend(R.string.share_invalid)
            return
        }
        val id = UUID.randomUUID().toString()
        saved["pending"] = id
        saved["text"] = content.text
        saved["source"] = content.image
        saved["mime"] = content.mime
        prepare(id, content)
    }

    /** 1. 在读取授权有效时暂存图片 2. 等待允许切换会话 3. 填入新草稿并通知主界面回到聊天页。 */
    private fun prepare(id: String, content: IncomingShare) {
        val previous = receiving
        previous?.cancel()
        receiving = viewModelScope.launch {
            previous?.join()
            var delivered = false
            try {
                // 1. 图片复制在后台执行;未完整读到时不把附言当成一次成功的图片分享。
                if (content.image != null && SandboxState.get(app).status.value is SandboxStatus.Unsupported) {
                    notices.send(R.string.share_image_unsupported)
                    return@launch
                }
                val image = content.image?.let { IncomingShareImages.prepare(app, id, it, content.mime!!) }
                // 2. 串行任务仍在执行时只等待,不取消、插话或启动另一个任务。
                if (!app.chat.canSwitch() || app.chat.ui.value.importing) notices.send(R.string.share_busy)
                app.chat.ui.first { app.chat.canSwitch() && !it.importing }
                currentCoroutineContext().ensureActive()
                // 3. 这里只创建待发草稿,真正执行沿用聊天页原有的发送流程。
                delivered = app.chat.openSharedDraft(id, content.text, image)
                if (delivered) opened.send(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MikaShare", Redactor.text("没能读到分享的图片：${e::class.simpleName}: ${e.message}"))
                notices.send(R.string.share_read_failed)
            } finally {
                if (saved.get<String>("pending") == id) saved["pending"] = null
                // 交付后的缓存归聊天草稿管理;取消或读取失败的暂存立即在后台清理。
                if (!delivered) withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { File(app.cacheDir, "attach/incoming/$id").deleteRecursively() }
                        .onFailure { Log.w("MikaShare", "分享临时图片没能清理", it) }
                }
            }
        }
    }
}

/** 外部图片只暂存到自己的缓存;用户发送时再走原附件导入,移除时不触碰来源 App。 */
internal object IncomingShareImages {
    /** 有界复制并核实图片,只把完整文件交给聊天页,原始 URI 不交给后台任务。 */
    suspend fun prepare(context: Context, id: String, source: String, mime: String): PendingAttachment = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "attach/incoming/$id")
        dir.mkdirs()
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
        val image = File(dir, "image.$extension")
        if (!image.isFile) {
            val partial = File(dir, "image.partial")
            try {
                context.contentResolver.openInputStream(Uri.parse(source)).use { input ->
                    requireNotNull(input)
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var size = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            size += read
                            check(size <= Attachments.MAX_BYTES && dir.usableSpace >= Attachments.MIN_FREE)
                            output.write(buffer, 0, read)
                        }
                    }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(partial.path, bounds)
                check(bounds.outWidth > 0 && bounds.outHeight > 0)
                check(partial.renameTo(image))
            } finally {
                partial.delete()
            }
        }
        val uri = FileProvider.getUriForFile(context, AttachFilesProvider.authority(context.packageName), image)
        PendingAttachment(uri.toString(), image.name, image.length(), photo = true)
    }

    /** 只清理本入口生成的私有缓存,外部相册或文件选择器的地址不处理;由调用方放到后台线程。 */
    fun release(context: Context, items: List<PendingAttachment>) {
        val root = File(context.cacheDir, "attach/incoming").canonicalFile
        for (item in items) {
            val uri = Uri.parse(item.uri)
            if (uri.authority != AttachFilesProvider.authority(context.packageName)) continue
            val parts = uri.pathSegments
            if (parts.size != 3 || parts[0] != "incoming") continue
            val dir = File(root, parts[1]).canonicalFile
            if (dir.parentFile == root) dir.deleteRecursively()
        }
    }
}
