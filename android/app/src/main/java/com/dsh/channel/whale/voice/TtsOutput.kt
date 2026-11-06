package com.dsh.channel.whale.voice

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.dsh.channel.whale.net.TtsClient
import java.io.File

/**
 * 插件端 TTS：文本 → POST /api/tts → mp3 → MediaPlayer 播放。
 *
 * 与 ASR 对称（手机只采集/播放，识别与合成都在 PC 侧）：本机不初始化系统 TextToSpeech，
 * 也不携带任何音色 / 语速参数——一把音色由服务端定，手机与 ESP32 拿到的是同一段音频。
 * 代价是必须联网、首句要等 PC 合成（约 2 s）。
 */
class TtsOutput(
    context: Context,
    private val endpoint: () -> String,
    private val pin: () -> String,
    private val deviceId: () -> String,
    private val onDone: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val app = context.applicationContext
    private val client = TtsClient()
    private val main = Handler(Looper.getMainLooper())
    private val cacheDir = File(app.cacheDir, "tts").apply { mkdirs() }

    private var player: MediaPlayer? = null
    private var current: File? = null

    @Volatile private var generation = 0

    init {
        cacheDir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    /** 合成并播放；再次调用会打断上一段。 */
    fun speak(text: String) {
        val value = text.trim()
        if (value.isEmpty()) {
            onDone()
            return
        }
        val base = endpoint().trim()
        if (base.isEmpty()) {
            onError("还没连接桌面端，无法朗读")
            return
        }
        val gen = stopCurrent()
        val target = File(cacheDir, "speech-$gen.mp3")
        val pinValue = pin()
        val device = deviceId()

        Thread({
            val result = client.synthesize(base, pinValue, device, value, target)
            main.post {
                if (gen != generation) {
                    runCatching { target.delete() }
                    return@post
                }
                result.fold(
                    onSuccess = { file -> play(gen, file) },
                    onFailure = { error ->
                        runCatching { target.delete() }
                        onError("朗读失败：${error.message}")
                    },
                )
            }
        }, "plugin-tts").apply { isDaemon = true }.start()
    }

    private fun play(gen: Int, file: File) {
        releasePlayer()
        current = file
        val media = MediaPlayer()
        player = media
        try {
            media.setDataSource(file.absolutePath)
            media.setOnPreparedListener {
                if (gen == generation) it.start() else releasePlayer()
            }
            media.setOnCompletionListener {
                if (gen == generation) onDone()
                releasePlayer()
            }
            media.setOnErrorListener { _, what, extra ->
                if (gen == generation) onError("播放失败（$what/$extra）")
                releasePlayer()
                true
            }
            media.prepareAsync()
        } catch (e: Exception) {
            onError("播放失败：${e.message}")
            releasePlayer()
        }
    }

    /**
     * 是否正在出声（含合成完等待开播的间隙）。
     * 语音输入用它抬高 VAD 门限，避免把自己的朗读当成用户插话。
     */
    fun isSpeaking(): Boolean = player != null

    fun stop() {
        stopCurrent()
    }

    fun shutdown() {
        stopCurrent()
    }

    /** 打断上一段并返回新的 generation：晚到的合成结果按 generation 丢弃。 */
    private fun stopCurrent(): Int {
        generation++
        releasePlayer()
        return generation
    }

    private fun releasePlayer() {
        val media = player
        player = null
        if (media != null) {
            runCatching { if (media.isPlaying) media.stop() }
            runCatching { media.release() }
        }
        current?.let { runCatching { it.delete() } }
        current = null
    }
}
