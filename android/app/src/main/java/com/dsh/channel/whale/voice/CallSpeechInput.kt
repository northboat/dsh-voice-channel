package com.dsh.channel.whale.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.dsh.channel.whale.net.AsrClient
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 通话模式语音输入：麦克风常驻打开，边听边说（barge-in），不需要按住或点按。
 *
 * 线程模型：
 * - capture 线程：`AudioRecord` 逐帧读 100 ms，能量 VAD 切句；切出的整段 PCM 投给识别线程；
 * - asr 线程（单线程池）：串行 `POST /api/asr`，因此识别耗时不会挡住继续听；
 * - 事件统一 post 回主线程，`stop()` 递增 generation 丢弃过期结果。
 *
 * 打断（回声是这里唯一的难点）：
 * 1. 采集源优先 `VOICE_COMMUNICATION` 并启用 `AcousticEchoCanceler` / `NoiseSuppressor`；
 * 2. 朗读期间把 VAD 阈值抬到 `max(噪声底 × 2.5, 1200)`，并要求连续发声 [ONSET_PLAY_MS] 才认定，
 *    单个噪声帧 / 扬声器残留不会误打断；
 * 3. 朗读期间起步、又始终没被认定为「人声」的片段直接丢弃，不送去识别（否则会把自己的回声
 *    当成用户的话再问一遍模型）；
 * 4. 判定为人声立刻上抛 [SpeechInput.Event.SpeechStarted]，上层停朗读；本段缓冲从开口前
 *    [PRE_ROLL_FRAMES] 帧开始保留，所以打断后的头几个字不会丢。
 *
 * 全双工依赖设备 AEC：机器没有 AEC 时仍可用，只是嘈杂环境可能误打断（见 docs）。
 */
class CallSpeechInput(
    context: Context,
    private val endpoint: () -> String,
    private val pin: () -> String,
    private val deviceId: () -> String,
    /** 是否正在播放朗读（决定 VAD 阈值与打断判定）。 */
    private val playing: () -> Boolean,
    private val onEvent: (SpeechInput.Event) -> Unit,
) : SpeechInput {

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 1600           // 100 ms / 帧
        const val CALIBRATION_MS = 300f          // 开头估噪声底
        const val NOISE_GAIN = 3.5f              // 噪声峰值到人声峰值的倍数
        const val NOISE_FLOOR = 20f              // 噪声底下限（实测静音峰值 ~40）
        const val MIN_THRESHOLD = 120f           // 人声门限下限（实测远场说话峰值 200~800）
        const val MAX_THRESHOLD = 2500f
        const val PLAY_MIN_THRESHOLD = 700f      // 朗读期间的打断门限下限
        const val ONSET_MS = 200f                // 静默时确认开口所需时长
        const val ONSET_PLAY_MS = 320f           // 朗读时确认打断所需时长
        const val MIN_SPEECH_MS = 300f           // 短于此的片段视为噪声
        const val SILENCE_MS = 700f              // 句尾静音判据
        const val MAX_MS = 15_000f               // 单句上限
        const val PRE_ROLL_FRAMES = 3            // 开口前保留 300 ms
    }

    private val asr = AsrClient()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var generation = 0
    private var worker: Thread? = null
    private var asrExecutor: ExecutorService? = null
    private var activeRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var suppressor: NoiseSuppressor? = null

    /** 插件地址 → ASR 地址：同主机同端口，防火墙只需放行一个进程。 */
    private fun asrUrl(): String {
        val raw = endpoint().trim()
        if (raw.isEmpty()) return ""
        val uri = runCatching { URI(if (raw.contains("://")) raw else "http://$raw") }.getOrNull()
            ?: return ""
        val host = uri.host ?: return ""
        val port = if (uri.port > 0) uri.port else 8787
        return "http://$host:$port/api/asr"
    }

    @SuppressLint("MissingPermission")
    override fun start() {
        stop()
        val gen = generation
        val url = asrUrl()
        if (url.isEmpty()) {
            failStart(gen, "还没连接桌面端，请先在设置里填写地址")
            return
        }

        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val source = MediaRecorder.AudioSource.VOICE_RECOGNITION
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBytes, FRAME_SAMPLES * 2 * 4))
                .build()
        } catch (e: Exception) {
            failStart(gen, "麦克风不可用，请检查权限")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            failStart(gen, "麦克风不可用（检查录音权限）")
            return
        }

        activeRecord = record
        aec = createEffect(record.audioSessionId) { AcousticEchoCanceler.create(it)?.apply { enabled = true } }
        suppressor = createEffect(record.audioSessionId) { NoiseSuppressor.create(it)?.apply { enabled = true } }

        running = true
        asrExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "call-asr").apply { isDaemon = true }
        }
        val thread = Thread({ captureLoop(gen, record, url) }, "call-capture").apply { isDaemon = true }
        worker = thread
        thread.start()
    }

    private fun <T : AudioEffect> createEffect(sessionId: Int, factory: (Int) -> T?): T? =
        runCatching { factory(sessionId) }.getOrNull()

    private fun captureLoop(gen: Int, record: AudioRecord, url: String) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val buf = ShortArray(FRAME_SAMPLES)
        val preRoll = ArrayDeque<ByteArray>()
        var out = ByteArrayOutputStream(FRAME_SAMPLES * 2 * 80)

        var state = Vad.SILENT
        var candMs = 0f
        var speechMs = 0f
        var silenceMs = 0f
        var startedDuringPlayback = false
        var bargeIn = false
        var elapsedMs = 0f
        var noiseSum = 0f
        var noiseFrames = 0
        var noise = NOISE_FLOOR
        var lastLevelMs = 0f

        try {
            record.startRecording()
            emit(gen, SpeechInput.Event.Ready)
            while (running && gen == generation) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_DEAD_OBJECT ||
                        n == AudioRecord.ERROR_INVALID_OPERATION ||
                        n == AudioRecord.ERROR_BAD_VALUE
                    ) {
                        emit(gen, SpeechInput.Event.Error("录音中断，请重试"))
                        break
                    }
                    continue
                }

                val frameMs = n * 1000f / SAMPLE_RATE
                elapsedMs += frameMs
                val rms = rms(buf, n)
                // 判据用帧内峰值：100 ms 帧的 RMS 会把音节平均掉，远场说话很容易漏。
                val peak = peak(buf, n)
                val bytes = pcmBytes(buf, n)
                val isPlaying = playing()
                val silent = state == Vad.SILENT

                if (elapsedMs <= CALIBRATION_MS) {
                    // 标定取最小值：麦克风刚启动那一两帧常有远高于环境的瞬态，平均值会被它带偏。
                    noiseSum = if (noiseFrames == 0) peak else min(noiseSum, peak)
                    noiseFrames++
                    noise = max(noiseSum, NOISE_FLOOR)
                } else if (silent) {
                    // 快降慢升：环境变安静立刻跟上，变吵只慢慢抬，避免被一声咳嗽顶死门限。
                    noise = if (peak < noise) {
                        (noise * 0.6f + peak * 0.4f).coerceAtLeast(NOISE_FLOOR)
                    } else {
                        (noise * 0.98f + peak * 0.02f).coerceAtLeast(NOISE_FLOOR)
                    }
                }

                val threshold = if (isPlaying) {
                    max(noise * NOISE_GAIN, PLAY_MIN_THRESHOLD)
                } else {
                    (noise * NOISE_GAIN).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
                }
                val voiced = peak > threshold

                if (elapsedMs - lastLevelMs >= 200f) {
                    lastLevelMs = elapsedMs
                    emit(gen, SpeechInput.Event.Level(20f * log10(rms.coerceAtLeast(1f))))
                }

                preRoll.addLast(bytes)
                while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()

                var appended = false
                if (silent) {
                    if (!voiced) continue
                    state = Vad.CANDIDATE
                    candMs = 0f
                    startedDuringPlayback = isPlaying
                    bargeIn = false
                    out = ByteArrayOutputStream(FRAME_SAMPLES * 2 * 80)
                    for (frame in preRoll) out.write(frame)
                    appended = true
                }
                if (!appended) out.write(bytes)

                when (state) {
                    Vad.CANDIDATE -> {
                        if (voiced) {
                            candMs += frameMs
                            val need = if (isPlaying) ONSET_PLAY_MS else ONSET_MS
                            if (candMs >= need) {
                                state = Vad.SPEECH
                                speechMs = candMs
                                silenceMs = 0f
                                bargeIn = isPlaying
                                emit(gen, SpeechInput.Event.SpeechStarted)
                            }
                        } else {
                            out.reset()
                            preRoll.clear()
                            state = Vad.SILENT
                        }
                    }

                    Vad.SPEECH -> {
                        speechMs += frameMs
                        if (voiced) silenceMs = 0f else silenceMs += frameMs
                        if (silenceMs >= SILENCE_MS || speechMs >= MAX_MS) {
                            val pcm = out.toByteArray()
                            val length = speechMs
                            // 朗读期间起步却没被判定为人声 → 是自己的回声，丢掉。
                            val echo = startedDuringPlayback && !bargeIn
                            out.reset()
                            preRoll.clear()
                            state = Vad.SILENT
                            silenceMs = 0f
                            if (!echo && length >= MIN_SPEECH_MS) transcribe(gen, url, pcm)
                        }
                    }

                    Vad.SILENT -> Unit
                }
            }
        } catch (e: Exception) {
            if (gen == generation) emit(gen, SpeechInput.Event.Error("录音中断，请重试"))
        } finally {
            runCatching { record.stop() }
            record.release()
            if (activeRecord === record) activeRecord = null
            releaseEffects()
            running = false
            emit(gen, SpeechInput.Event.Finished)
        }
    }

    /** 一句 PCM → /api/asr；识别在独立线程，采集不中断。 */
    private fun transcribe(gen: Int, url: String, pcm: ByteArray) {
        val executor = asrExecutor ?: return
        val pinValue = pin()
        val device = deviceId()
        emit(gen, SpeechInput.Event.Recognizing)
        runCatching {
            executor.execute {
                val result = asr.transcribe(url, pcm, pinValue, device)
                if (gen != generation) return@execute
                result.fold(
                    onSuccess = { text ->
                        // 通话模式不因「没听清」打断节奏：空文本静默丢弃。
                        if (text.isNotBlank()) emit(gen, SpeechInput.Event.Result(text))
                    },
                    onFailure = { emit(gen, SpeechInput.Event.Error(it.message ?: "识别失败，再试一次")) },
                )
            }
        }
    }

    override fun stop() {
        running = false
        generation++
        runCatching { activeRecord?.stop() }
        val thread = worker
        worker = null
        if (thread != null && thread !== Thread.currentThread() && thread.isAlive) {
            runCatching { thread.join(400) }
        }
        asrExecutor?.shutdownNow()
        asrExecutor = null
        releaseEffects()
    }

    override fun close() {
        stop()
    }

    /** 起不来（没地址 / 没麦克风）：报错并立刻收尾，上层据此退出通话。 */
    private fun failStart(gen: Int, message: String) {
        emit(gen, SpeechInput.Event.Error(message))
        emit(gen, SpeechInput.Event.Finished)
    }

    private fun releaseEffects() {
        runCatching { aec?.release() }
        runCatching { suppressor?.release() }
        aec = null
        suppressor = null
    }

    private fun emit(gen: Int, event: SpeechInput.Event) {
        main.post { if (gen == generation) onEvent(event) }
    }

    private fun pcmBytes(buf: ShortArray, n: Int): ByteArray {
        val out = ByteArray(n * 2)
        var i = 0
        for (k in 0 until n) {
            val v = buf[k].toInt()
            out[i++] = (v and 0xFF).toByte()
            out[i++] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun rms(buf: ShortArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) {
            val v = buf[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / n).toFloat()
    }

    /** 帧内最大绝对值：对短促音节比 RMS 灵敏得多。 */
    private fun peak(buf: ShortArray, n: Int): Float {
        var max = 0
        for (i in 0 until n) {
            val v = kotlin.math.abs(buf[i].toInt())
            if (v > max) max = v
        }
        return max.toFloat()
    }

    private enum class Vad { SILENT, CANDIDATE, SPEECH }
}
