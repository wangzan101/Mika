package com.wangzan101.mika.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import com.wangzan101.mika.core.model.StreamingTtsModel
import com.wangzan101.mika.core.model.TtsAudioPart
import com.wangzan101.mika.core.model.TtsModel
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File

/** Mika 此刻的朗读状态。 */
sealed interface VoicePlayback {
    data object Idle : VoicePlayback
    data object Loading : VoicePlayback
    data object Playing : VoicePlayback
    data class Error(val message: String) : VoicePlayback
}

/**
 * 本机朗读。流式 PCM 数据到达后送入 AudioTrack，已合成的音频交给 MediaPlayer。
 * 同一时间只播一条。播放结束、出错或停止时释放音频焦点。
 */
class VoicePlayer(private val context: Context) {
    private val _state = MutableStateFlow<VoicePlayback>(VoicePlayback.Idle)
    val state: StateFlow<VoicePlayback> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var pcmTrack: PcmAudioTrack? = null
    private var pendingPlayback: Job? = null
    private var file: File? = null
    private var focus: AudioFocusRequest? = null
    private val audioManager by lazy { context.getSystemService(AudioManager::class.java) }
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** 播一段合成好的音频;格式从 [mime] 猜扩展名,MediaPlayer 按文件内容解码。 */
    suspend fun play(bytes: ByteArray, mime: String) = loadAndPlay {
        prepareFile(bytes, mime)
    }

    /** 在服务支持时边合成边播，其他服务或语速继续使用完整音频响应。 */
    suspend fun play(
        model: TtsModel,
        request: TtsRequest,
        streaming: Boolean,
        onStarted: () -> Unit = {},
    ) = playRequests(model, listOf(request), streaming, onStarted)

    /** 按顺序朗读同一条回答的各段。 */
    internal suspend fun playRequests(
        model: TtsModel,
        requests: List<TtsRequest>,
        streaming: Boolean,
        onStarted: () -> Unit = {},
    ) = loadAndPlay {
        var streamModel = if (streaming) model as? StreamingTtsModel else null
        for ((index, request) in requests.withIndex()) {
            val stream = streamModel
            val started = { if (index == 0) onStarted() }
            val complete = if (stream != null) collectStream(stream.streamAudio(request), started) else model.synthesize(request)
            if (complete != null) {
                // 后续段沿用已经确认可播放的完整音频路径。
                streamModel = null
                drainPcm(VoicePlayback.Loading)
                _state.value = VoicePlayback.Loading
                val completion = if (index < requests.lastIndex) CompletableDeferred<VoicePlayback>() else null
                prepareFile(complete.bytes, complete.mime, started, completion)
                val result = completion?.await()
                if (result is VoicePlayback.Error) error(result.message)
            }
        }
        drainPcm()
    }

    private suspend fun loadAndPlay(block: suspend () -> Unit) = withContext(Dispatchers.Main.immediate) {
        stop()
        val job = currentCoroutineContext().job
        pendingPlayback = job
        _state.value = VoicePlayback.Loading
        try {
            block()
        } catch (e: CancellationException) {
            if (pendingPlayback === job) release(VoicePlayback.Idle)
            throw e
        } catch (e: Exception) {
            if (pendingPlayback === job) release(VoicePlayback.Error(e.message ?: "cannot play audio"))
            throw e
        } finally {
            if (pendingPlayback === job) pendingPlayback = null
        }
    }

    private suspend fun collectStream(parts: Flow<TtsAudioPart>, onStarted: () -> Unit): TtsResponse? {
        var complete: TtsResponse? = null
        var receivedPcm = false
        parts.collect { part ->
            check(complete == null) { "unexpected audio after a complete response" }
            when (part) {
                is TtsAudioPart.Pcm -> {
                    val current = pcmTrack
                    if (!receivedPcm && current != null &&
                        (current.sampleRate != part.sampleRate || current.channels != part.channels)
                    ) {
                        drainPcm(VoicePlayback.Loading)
                    }
                    playPcmChunk(part, onStarted)
                    receivedPcm = true
                }
                is TtsAudioPart.Complete -> {
                    check(!receivedPcm) { "unexpected complete audio after PCM" }
                    complete = part.response
                }
            }
        }
        check(receivedPcm || complete != null) { "empty audio stream" }
        return complete
    }

    private suspend fun drainPcm(nextState: VoicePlayback = VoicePlayback.Idle) {
        val audio = pcmTrack ?: return
        audio.drain()
        release(nextState)
    }

    private suspend fun playPcmChunk(chunk: TtsAudioPart.Pcm, onStarted: () -> Unit) {
        val first = pcmTrack == null
        val audio = pcmTrack ?: PcmAudioTrack(attributes, chunk.sampleRate, chunk.channels).also { track ->
            pcmTrack = track
            check(requestFocus { change ->
                if (pcmTrack === track) {
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS -> stop()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> track.pause()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> track.setVolume(0.2f)
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            track.setVolume(1f)
                            track.resume()
                        }
                    }
                }
            }) { "audio focus is not available" }
            track.resume()
        }
        check(audio.sampleRate == chunk.sampleRate && audio.channels == chunk.channels) { "PCM format changed during playback" }
        audio.write(chunk.bytes)
        if (first) {
            _state.value = VoicePlayback.Playing
            onStarted()
        }
    }

    private suspend fun prepareFile(
        bytes: ByteArray,
        mime: String,
        onStarted: () -> Unit = {},
        completion: CompletableDeferred<VoicePlayback>? = null,
    ) {
        fun finishSegment(result: VoicePlayback) {
            release(if (completion != null && result == VoicePlayback.Idle) VoicePlayback.Loading else result)
            completion?.complete(result)
        }

        val dir = File(context.cacheDir, "voice")
        val f = File(dir, "tts-${System.nanoTime()}.${extensionOf(mime)}")
        try {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                f.writeBytes(bytes)
            }
        } catch (e: Exception) {
            f.delete()
            throw e
        }
        file = f
        try {
            val media = MediaPlayer()
            player = media
            media.apply {
                setAudioAttributes(attributes)
                setDataSource(f.absolutePath)
                setOnPreparedListener { prepared ->
                    if (player !== prepared) return@setOnPreparedListener
                    if (requestFocus { change -> onMediaFocusChange(prepared, change) }) {
                        prepared.start()
                        _state.value = VoicePlayback.Playing
                        onStarted()
                    } else {
                        finishSegment(VoicePlayback.Error("audio focus is not available"))
                    }
                }
                setOnCompletionListener { if (player === it) finishSegment(VoicePlayback.Idle) }
                setOnErrorListener { failed, what, extra ->
                    if (player === failed) finishSegment(VoicePlayback.Error("player error $what/$extra"))
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            finishSegment(VoicePlayback.Error(e.message ?: "cannot play audio"))
        }
    }

    /** 停下来并释放。 */
    fun stop() = finish(VoicePlayback.Idle)

    fun fail(message: String) = finish(VoicePlayback.Error(message))

    private fun onMediaFocusChange(media: MediaPlayer, change: Int) {
        if (player !== media) return
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> stop()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (media.isPlaying) media.pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> media.setVolume(0.2f, 0.2f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                media.setVolume(1f, 1f)
                if (!media.isPlaying) runCatching { media.start() }
            }
        }
    }

    private fun requestFocus(onFocusChange: (Int) -> Unit): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { onFocusChange(it) }
            .build()
        focus = req
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun finish(state: VoicePlayback) {
        val job = pendingPlayback
        pendingPlayback = null
        job?.cancel()
        release(state)
    }

    private fun release(state: VoicePlayback) {
        runCatching { player?.release() }
        player = null
        runCatching { pcmTrack?.close() }
        pcmTrack = null
        focus?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focus = null
        file?.delete()
        file = null
        _state.value = state
    }

    private fun extensionOf(mime: String): String = when (mime.substringAfter('/', "").lowercase()) {
        "mpeg", "mp3" -> "mp3"
        "ogg", "opus" -> "ogg"
        "aac" -> "aac"
        "flac" -> "flac"
        "wav", "x-wav" -> "wav"
        else -> "wav"
    }
}
