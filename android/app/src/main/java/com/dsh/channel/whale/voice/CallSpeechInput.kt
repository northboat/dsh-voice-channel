package com.dsh.channel.whale.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 通话模式语音输入：麦克风常驻打开，边听边说，不需要按住或点按。
 *
 * 线程模型：
 * - capture 线程：`AudioRecord` 逐帧读 100 ms，能量 VAD 切句；切出的整段 PCM 交给 [Sink]；
 * - [Sink] 背后是 WS 长连接（`listen start` + PCM + `listen stop`），识别在插件端，
 *   所以这里不再占用第二个线程等 HTTP 返回，采集完全不阻塞；
 * - 事件统一 post 回主线程，`stop()` 递增 generation 丢弃过期事件。
 *
 * 断句为什么留在手机侧：这台机器拿不到平台 AEC（`VOICE_COMMUNICATION` 返回全 0），
 * 服务端断句会把外放回声也切进去。本地 VAD 能靠「朗读期抬阈值 + 起步片段丢弃」把回声压掉。
 *
 * 回声处理：
 * 1. 采集源优先 `VOICE_COMMUNICATION` 并启用 `AcousticEchoCanceler` / `NoiseSuppressor`；
 * 2. 朗读期间把 VAD 阈值抬到 `max(噪声底 × 3.5, 回声峰值 × 2.5, 700)`，并要求连续发声才认定；
 * 3. 朗读期间起步、又始终没被认定为人声的片段直接丢弃，不发出去（否则会把自己的回声
 *    当成用户的话再问一遍模型）；
 * 4. 判定为人声抛 [SpeechInput.Event.SpeechStarted]；本段缓冲从开口前
 *    [PRE_ROLL_FRAMES] 帧开始保留，头几个字不丢。
 *
 * 朗读不被打断：识别结果照常上行，服务端排队，当前回复播完再播下一段。
 */
class CallSpeechInput(
    context: Context,
    private val sink: Sink,
    /** 是否正在播放朗读（决定 VAD 阈值与回声判定）。 */
    private val playing: () -> Boolean,
    private val onEvent: (SpeechInput.Event) -> Unit,
) : SpeechInput {

    /** 一句话的出口：由上层接到 WS 的 listen start/stop 上。 */
    interface Sink {
        fun ready(): Boolean
        fun begin()
        fun write(pcm: ByteArray)
        fun end()
    }

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
        const val ONSET_PLAY_MS = 320f           // 朗读时确认打断所需时长（有 AEC）
        const val ONSET_PLAY_NO_AEC_MS = 600f    // 无 AEC 时更保守，压掉回声误触发
        const val ECHO_LEARN_MS = 300f           // 朗读开始后用来学回声峰值的窗口
        const val ECHO_GAIN = 2.5f               // 回声峰值 → 打断门限的倍数
        const val MIN_SPEECH_MS = 300f           // 短于此的片段视为噪声
        const val SILENCE_MS = 700f              // 句尾静音判据
        const val MAX_MS = 15_000f               // 单句上限
        const val PRE_ROLL_FRAMES = 3            // 开口前保留 300 ms
    }

    private val main = Handler(Looper.getMainLooper())
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 通话期间切到通信模式（平台 AEC 的前提），退出时恢复。 */
    private var callAudioMode = false
    private var prevAudioMode = AudioManager.MODE_NORMAL
    private var prevSpeakerOn = false
    private var deviceCallback: AudioDeviceCallback? = null

    @Volatile private var running = false
    @Volatile private var generation = 0
    private var worker: Thread? = null
    private var activeRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var suppressor: NoiseSuppressor? = null

    @SuppressLint("MissingPermission")
    override fun start() {
        stop()
        val gen = generation
        if (!sink.ready()) {
            failStart(gen, "还没连接桌面端，请先在设置里填写地址")
            return
        }

        // 优先走通信模式 + VOICE_COMMUNICATION：平台 AEC 只在这条链路上生效，
        // 否则外放时自己的朗读会被当成用户插话，来回自打断。
        // 不看 isAvailable()：这台 ROM 直接返回 false，但通信模式下该采集源仍可能可用。
        var fullDuplex = false
        var record: AudioRecord? = null
        enterCallAudioMode()
        record = openRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        if (record != null && !probeHasAudio(record)) {
            // 有的 ROM（实测 OPPO PLE110，MODE_NORMAL 下）对普通 App 关掉这个源，返回全 0。
            record.release()
            record = null
            restoreAudioMode()
        } else if (record != null) {
            fullDuplex = true
        }
        if (record == null) {
            record = openRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        }
        if (record == null) {
            restoreAudioMode()
            failStart(gen, "麦克风不可用，请检查权限")
            return
        }

        activeRecord = record
        if (fullDuplex) {
            aec = createEffect(record.audioSessionId) { AcousticEchoCanceler.create(it)?.apply { enabled = true } }
            suppressor = createEffect(record.audioSessionId) { NoiseSuppressor.create(it)?.apply { enabled = true } }
        }

        val comm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.communicationDevice?.type else -1
        Log.d(
            "dsh-dbg",
            "start fullDuplex=$fullDuplex mode=${audioManager.mode} comm=$comm speaker=${audioManager.isSpeakerphoneOn}",
        )

        running = true
        val thread = Thread({ captureLoop(gen, record, fullDuplex) }, "call-capture")
            .apply { isDaemon = true }
        worker = thread
        thread.start()
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(source: Int): AudioRecord? {
        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val record = runCatching {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBytes, FRAME_SAMPLES * 2 * 4))
                .build()
        }.getOrNull() ?: return null
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        return record
    }

    /** 采 5 帧看看有没有真实采样：全 0 说明这个采集源在本机不可用。 */
    @SuppressLint("MissingPermission")
    private fun probeHasAudio(record: AudioRecord): Boolean {
        val buf = ShortArray(FRAME_SAMPLES)
        return try {
            record.startRecording()
            var heard = false
            var frames = 0
            while (frames < 5 && !heard) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) break
                for (i in 0 until n) {
                    if (buf[i].toInt() != 0) {
                        heard = true
                        break
                    }
                }
                frames++
            }
            heard
        } catch (e: Exception) {
            false
        } finally {
            runCatching { record.stop() }
        }
    }

    private fun enterCallAudioMode() {
        if (callAudioMode) return
        prevAudioMode = audioManager.mode
        prevSpeakerOn = audioManager.isSpeakerphoneOn
        runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
        callAudioMode = true
        routeAudio()
        registerDeviceCallback()
    }

    /**
     * 选通话通道：有线 / USB / 蓝牙耳机优先，没有耳机才用外放。
     *
     * 以前这里无条件 `isSpeakerphoneOn = true`，插着耳机也照样从外放出声、用机身麦克风，
     * 所以通话时耳机完全没用。API 31+ 用 `setCommunicationDevice()` 显式指定；
     * 更老的系统只能靠 `isSpeakerphoneOn` + `startBluetoothSco()`。
     */
    private fun routeAudio() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val available = runCatching { audioManager.availableCommunicationDevices }.getOrDefault(emptyList())
            val target = available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                ?: available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                ?: available.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
                ?: available.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                ?: available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (target != null) runCatching { audioManager.setCommunicationDevice(target) }
            return
        }
        @Suppress("DEPRECATION")
        val wired = audioManager.isWiredHeadsetOn
        @Suppress("DEPRECATION")
        val bluetooth = audioManager.isBluetoothScoOn || audioManager.isBluetoothA2dpOn
        runCatching {
            if (bluetooth && !audioManager.isBluetoothScoOn) audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = !wired && !bluetooth
        }
    }

    /** 通话中插拔耳机会触发；重算一次通道即可，采集线程会跟到新的默认设备。 */
    private fun registerDeviceCallback() {
        if (deviceCallback != null) return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = routeAudio()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = routeAudio()
        }
        deviceCallback = callback
        runCatching { audioManager.registerAudioDeviceCallback(callback, null) }
    }

    private fun restoreAudioMode() {
        deviceCallback?.let { runCatching { audioManager.unregisterAudioDeviceCallback(it) } }
        deviceCallback = null
        if (!callAudioMode) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = prevSpeakerOn
            audioManager.mode = prevAudioMode
        }
        callAudioMode = false
    }

    private fun <T : AudioEffect> createEffect(sessionId: Int, factory: (Int) -> T?): T? =
        runCatching { factory(sessionId) }.getOrNull()

    private fun captureLoop(gen: Int, record: AudioRecord, fullDuplex: Boolean) {
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
        var wasPlaying = false
        var playStartMs = 0f
        var echoPeak = 0f
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

                // 朗读开始的头 300 ms 先把外放回声的峰值学下来，之后门限比它高一截；
                // 有 AEC 时回声本来就被压掉了，这条只是兜底。
                if (isPlaying && !wasPlaying) {
                    playStartMs = elapsedMs
                    echoPeak = 0f
                } else if (!isPlaying && wasPlaying) {
                    echoPeak = 0f
                }
                wasPlaying = isPlaying
                val learningEcho = isPlaying && elapsedMs - playStartMs < ECHO_LEARN_MS
                if (learningEcho) echoPeak = max(echoPeak, peak)

                val threshold = when {
                    learningEcho -> Float.MAX_VALUE
                    isPlaying -> maxOf(noise * NOISE_GAIN, echoPeak * ECHO_GAIN, PLAY_MIN_THRESHOLD)
                    else -> (noise * NOISE_GAIN).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
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
                            val need = when {
                                !isPlaying -> ONSET_MS
                                fullDuplex -> ONSET_PLAY_MS
                                else -> ONSET_PLAY_NO_AEC_MS
                            }
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
                            if (!echo && length >= MIN_SPEECH_MS) sendUtterance(gen, pcm)
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

    /**
     * 一句 PCM → WS（listen start + 帧 + listen stop）。
     * WS 的 send 只是入队，不会阻塞采集；识别结果从连接上以 `stt` 回来。
     */
    private fun sendUtterance(gen: Int, pcm: ByteArray) {
        emit(gen, SpeechInput.Event.Recognizing)
        runCatching {
            sink.begin()
            sink.write(pcm)
            sink.end()
        }.onFailure { emit(gen, SpeechInput.Event.Error("发送失败，请确认和电脑在同一网络")) }
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
        releaseEffects()
        restoreAudioMode()
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
