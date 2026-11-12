package com.dsh.channel.whale.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.max

/**
 * 流式播放：服务端逐句合成、按 60 ms 一帧推 PCM，这里边收边塞进 [AudioTrack]（MODE_STREAM），
 * 不等整段音频到齐，所以第一句话几乎是「说完就到」。
 *
 * 字幕跟着播放头走，而不是跟着下载进度：
 * `sentence_start` 在音频之前到达，每次标记记下当前已写入的采样位置；播放时用
 * `playbackHeadPosition` 反查落在哪一句、句内比例多少，折算成字数回给上层。
 * 这样打字幕的节奏天然等于朗读节奏（音画同步）。
 *
 * 写入与「标记/停止」都在同一条工作线程上按 FIFO 处理，标记的采样位置不会和写入抢跑。
 */
class StreamingPlayer(
    private val onCaption: (text: String, chars: Int) -> Unit,
    /** 队列播完（所有已写入的音频都出声完毕）。 */
    private val onDrained: () -> Unit,
) {

    private sealed interface Job {
        data class Mark(val text: String) : Job
        data class Data(val pcm: ByteArray) : Job
        data object SpeechDone : Job
        data object Stop : Job
        data object Release : Job
    }

    private class Segment(val text: String, val start: Long) {
        var samples: Long = 0
    }

    private val main = Handler(Looper.getMainLooper())
    private val queue = LinkedBlockingQueue<Job>()
    private val segments = ArrayList<Segment>()
    private val lock = Any()

    private var track: AudioTrack? = null
    private var sampleRate = 24000

    @Volatile private var written = 0L
    @Volatile private var discarding = false
    @Volatile private var finishing = false
    @Volatile private var running = true

    private var reported: Segment? = null
    private var reportedChars = -1
    private var ticking = false

    private val writer = Thread({ writerLoop() }, "dsh-audio-out").apply {
        isDaemon = true
        start()
    }

    /** 服务端 hello 的下行采样率；变了就重建 track。 */
    fun configure(rate: Int) {
        val value = if (rate > 0) rate else 24000
        if (value == sampleRate) return
        sampleRate = value
        queue.offer(Job.Stop)
    }

    fun mark(text: String) {
        if (text.isBlank()) return
        queue.offer(Job.Mark(text))
    }

    fun write(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        queue.offer(Job.Data(pcm))
        startTicker()
    }

    /** 服务端 tts stop：音频发完了，等播完再回聆听。 */
    fun finishSpeech() {
        queue.offer(Job.SpeechDone)
        startTicker()
    }

    /** 打断 / 挂断：立刻静音并清空。 */
    fun stop() {
        discarding = true
        finishing = false
        queue.removeIf { it is Job.Data || it is Job.Mark || it is Job.SpeechDone }
        queue.offer(Job.Stop)
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        reported = null
        reportedChars = -1
    }

    fun release() {
        stop()
        running = false
        queue.offer(Job.Release)
        ticking = false
        main.removeCallbacksAndMessages(null)
        writer.interrupt()
        runCatching { track?.release() }
        track = null
    }

    /** 还有声音没播完（含队列里没写进去的）：语音输入用它抬高 VAD 门限。 */
    fun isActive(): Boolean {
        if (queue.isNotEmpty() || finishing) return true
        val t = track ?: return false
        val head = runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
        return head < written
    }

    // ---- 工作线程 ----

    private fun writerLoop() {
        while (running) {
            val job = try { queue.take() } catch (_: InterruptedException) { break }
            try {
                when (job) {
                    is Job.Mark -> synchronized(lock) {
                        if (!discarding) segments.add(Segment(job.text, written))
                    }
                    is Job.Data -> writeFrames(job.pcm)
                    Job.SpeechDone -> finishing = true
                    Job.Stop -> resetAudio()
                    Job.Release -> break
                }
            } catch (error: Exception) {
                Log.d("dsh-dbg", "player job failed: ${error.message}")
            }
        }
    }

    private fun writeFrames(pcm: ByteArray) {
        if (discarding) return
        val track = ensureTrack() ?: return
        var offset = 0
        while (offset < pcm.size) {
            if (discarding || !running) return
            val count = track.write(pcm, offset, pcm.size - offset)
            if (count <= 0) return
            offset += count
            val frames = count / 2
            written += frames
            synchronized(lock) {
                val last = segments.lastOrNull()
                if (last != null) last.samples += frames
            }
        }
    }

    private fun ensureTrack(): AudioTrack? {
        track?.let { return it }
        val min = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // 2~3 个最小缓冲：太小会断音，太大插话延迟高（这里没有 AEC，宁可留点余量）。
        val size = max(min * 3, (sampleRate * 2 * 300) / 1000)
        val created = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // 明确按「媒体 / 语音」播：通话模式下默认属性会被挂到通话音轨，又闷又碎。
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()
        if (created == null) {
            Log.d("dsh-dbg", "AudioTrack 创建失败 rate=$sampleRate")
            return null
        }
        runCatching { created.play() }
        Log.d("dsh-dbg", "AudioTrack start rate=$sampleRate buffer=$size state=${created.state}")
        track = created
        return created
    }

    private fun resetAudio() {
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        synchronized(lock) { segments.clear() }
        written = 0
        finishing = false
        discarding = false
        reported = null
        reportedChars = -1
    }

    // ---- 字幕 ----

    private fun startTicker() {
        if (ticking) return
        ticking = true
        main.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            try { tickOnce() } catch (_: Exception) { /* 字幕不该影响播放 */ }
            if (ticking) main.postDelayed(this, 60)
        }
    }

    private fun tickOnce() {
        val track = track ?: return
        val head = runCatching { track.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
        val segment = synchronized(lock) {
            segments.firstOrNull { head >= it.start && head < it.start + it.samples }
                ?: segments.lastOrNull { it.samples > 0 && head >= it.start }
        }
        if (segment != null) {
            val length = segment.text.length
            val chars = if (segment.samples <= 0) length else {
                (((head - segment.start).coerceAtLeast(0) * length) / segment.samples).toInt()
            }.coerceIn(0, length)
            // 切换句子要等声音真的播到它（chars > 0），否则上一句还在响、字幕已先跳到下一句的空白。
            if (segment !== reported) {
                if (chars > 0) {
                    reported = segment
                    reportedChars = chars
                    onCaption(segment.text, chars)
                }
            } else if (chars > reportedChars) {
                reportedChars = chars
                onCaption(segment.text, chars)
            }
        }
        if (finishing && head >= written && queue.none { it is Job.Data }) {
            finishing = false
            onDrained()
        }
    }
}
