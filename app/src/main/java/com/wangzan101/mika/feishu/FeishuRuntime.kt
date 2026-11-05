package com.wangzan101.mika.feishu

import android.content.Context
import android.util.Log
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.feishu.FeishuApi
import com.wangzan101.mika.core.feishu.FeishuBot
import com.wangzan101.mika.core.feishu.FeishuJournal
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.KeystoreKeyCipher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable data class FeishuConfig(
    val enabled: Boolean = false, val appId: String = "", val appSecret: String = "", val mirrorLocal: Boolean = false,
) {
    override fun toString() = "FeishuConfig(enabled=$enabled, credentials=[redacted])"
}

/** Used for configuration, owner binding, inbound receipts and delivery attempts; never backed up. */
class FeishuEncryptedFile(file: File, private val cipher: KeyCipher) {
    private val file = file
    @Synchronized fun read(): String? {
        if (!file.exists()) return null
        val plain=cipher.decrypt(file.readBytes())
        return try { plain.toString(Charsets.UTF_8) } finally { plain.fill(0) }
    }
    @Synchronized fun write(text: String) {
        file.parentFile?.mkdirs()
        val plain=text.toByteArray(Charsets.UTF_8)
        val temp=File(file.parentFile,file.name+".tmp")
        try {
            val encrypted=cipher.encrypt(plain)
            temp.outputStream().use { stream -> stream.write(encrypted); stream.fd.sync() }
            Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { plain.fill(0); temp.delete() }
    }
    @Synchronized fun clear() { check(!file.exists() || file.delete()) { "Cannot clear Feishu state" } }
}

class FeishuRuntime(private val app: MikaApp) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val cipher=KeystoreKeyCipher("mika-feishu")
    private val configFile=FeishuEncryptedFile(File(app.noBackupFilesDir,"feishu-config.bin"),cipher)
    private val stateFile=FeishuEncryptedFile(File(app.noBackupFilesDir,"feishu-inbox.bin"),cipher)
    val channel = FeishuChannel(this)
    private val windowHost by lazy { AndroidFeishuWindows(app) { pairedOwner() } }
    fun channelFor(id: String) = channel.takeIf { id == FeishuChannel.ID }
    @Synchronized fun pairedOwner(): String? = journal().state.owner
    @Synchronized fun pairedChat(): String? = journal().state.chat
    fun windowTitle(id: String): String = windowHost.title(id)
    @Synchronized fun acknowledge(messageId: String) = journal().acknowledge(messageId)

    private var journal: FeishuJournal? = null
    private var bot: FeishuBot? = null
    private var job: Job? = null
    private val mutableStatus=MutableStateFlow(false)
    val connected=mutableStatus.asStateFlow()

    @Synchronized fun config(): FeishuConfig = runCatching {
        configFile.read()?.let { Json.decodeFromString<FeishuConfig>(it) }
    }.getOrNull() ?: FeishuConfig()

    @Synchronized fun binding(): Pair<String?,String> {
        val state=journal().state
        return state.owner to state.pairingCode
    }

    @Synchronized private fun journal(): FeishuJournal = journal ?: FeishuJournal(stateFile::read,stateFile::write).also { journal=it }

    suspend fun configure(next: FeishuConfig) {
        require(!next.enabled || (Regex("cli_[a-zA-Z0-9]+").matches(next.appId) && next.appSecret.isNotBlank()))
        val old=config()
        stop()
        synchronized(this) {
            if (old.appId != next.appId || old.appSecret != next.appSecret) {
                stateFile.clear(); journal=null; bot=null
            }
            configFile.write(Json.encodeToString(next))
        }
    }

    suspend fun resetBinding() {
        stop()
        synchronized(this) { stateFile.clear(); journal=null; bot=null }
    }

    @Synchronized private fun bot(config: FeishuConfig): FeishuBot = bot ?: run {
        val api=FeishuApi(config.appId,config.appSecret)
        FeishuBot(config.appId, journal(), windowHost,
            send = { chat, text, id -> api.send(chat, text, id) },
            sendCard = { chat, card, id -> api.sendCard(chat, card, id) })
            .also { bot=it }
    }

    @Synchronized fun start(): Boolean {
        if (job?.isActive == true) return true
        val config=config()
        if (!config.enabled) return false
        val current=try { bot(config) } catch (_: Exception) { return false }
        job=scope.launch {
            supervisorScope {
                val socket=launch {
                    FeishuSocketRunner(config,current) { mutableStatus.value=it }.run()
                }
                try {
                    while (isActive) {
                        try { current.tick() }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { Log.w("MikaFeishu","Could not process inbox; no task automatically resubmitted") }
                        delay(1000)
                    }
                } finally { socket.cancel(); mutableStatus.value=false }
            }
        }
        return true
    }

    @Synchronized fun cancel() { job?.cancel(); job=null; mutableStatus.value=false }

    suspend fun stop() { val previous=synchronized(this) { job.also { job=null } }; previous?.cancelAndJoin(); mutableStatus.value=false }

    /** Called from existing result paths; local notification delivery never depends on Feishu availability. */
    fun mirror(id: String, text: String) {
        val config=config()
        if (!config.enabled || !config.mirrorLocal) return
        scope.launch {
            runCatching { synchronized(this@FeishuRuntime) { if (config() == config) bot(config).mirrorResult(id,text) } }
                .onFailure { Log.w("MikaFeishu","Could not queue result notification") }
        }
    }

    private class FeishuSocketRunner(val config: FeishuConfig,val bot: FeishuBot,val status:(Boolean)->Unit) {
        suspend fun run() = com.wangzan101.mika.core.feishu.FeishuSocket(FeishuApi(config.appId,config.appSecret),bot::enqueue,status).run()
    }
}
