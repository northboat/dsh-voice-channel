package com.dsh.channel.whale.net

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 服务端 hello 声明的下行音频参数。 */
data class AudioSpec(val format: String = "pcm16", val sampleRate: Int = 24000, val frameMs: Int = 60)

/**
 * 小智（xiaozhi）WS 协议客户端：一条长连接承担 ASR / 对话 / TTS 三段。
 *
 * 语音上行用 `listen start|stop` 括起来（manual 模式），断句仍由手机本地 VAD 做——
 * 这台机器没有平台 AEC，服务端断句会把外放回声也切进去。下行音频是服务端逐句合成、
 * 按 60 ms 节奏推过来的 PCM，边收边播（见 [com.dsh.channel.whale.voice.StreamingPlayer]）。
 *
 * 所有回调都切到主线程；重连在内部按 0.5→1→2→4→8→10 s 退避。
 */
class WsSession(private val store: SettingsStore) {

    interface Listener {
        fun onOpen(sessionId: String, audio: AudioSpec)
        fun onStt(text: String)
        fun onEmotion(emotion: String)
        fun onSpeechStart()
        fun onSentence(text: String)
        fun onAudio(pcm: ByteArray)
        fun onSpeechStop()
        fun onAlert(message: String)
        fun onClosed()
    }

    var listener: Listener? = null

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var open = false
    @Volatile private var wanted = false
    private var attempt = 0
    private var retryTask: Runnable? = null

    /** 服务端 hello 覆盖；默认值只在 hello 之前用得上。 */
    @Volatile var audio = AudioSpec()
        private set

    val isReady: Boolean get() = open

    fun connect() {
        wanted = true
        attempt = 0
        openSocket()
    }

    /** 挂断 / 退出：不再重连。 */
    fun close() {
        wanted = false
        retryTask?.let { main.removeCallbacks(it) }
        retryTask = null
        open = false
        socket?.close(1000, "bye")
        socket = null
    }

    private fun wsUrl(): String {
        val base = store.endpoint.trim().trimEnd('/')
        if (base.isEmpty()) return ""
        val withScheme = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            base.startsWith("ws://") || base.startsWith("wss://") -> base
            else -> "ws://$base"
        }
        return "$withScheme/xiaozhi/v1/"
    }

    private fun openSocket() {
        val url = wsUrl()
        if (url.isEmpty()) {
            post { listener?.onAlert("还没连接桌面端") }
            return
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${store.pin}")
            .header("X-PIN", store.pin)
            .header("Protocol-Version", "1")
            .header("Device-Id", store.deviceId)
            .header("Client-Id", store.deviceId)
            .build()
        Log.d("dsh-dbg", "ws connect $url")
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("dsh-dbg", "ws open, send hello")
                webSocket.send(hello())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                open = true
                attempt = 0
                onJson(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                val pcm = bytes.toByteArray()
                post { listener?.onAudio(pcm) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.d("dsh-dbg", "ws failure ${t.message} code=${response?.code}")
                open = false
                socket = null
                post { listener?.onClosed() }
                scheduleRetry()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("dsh-dbg", "ws closed code=$code reason=$reason")
                open = false
                socket = null
                post { listener?.onClosed() }
                scheduleRetry()
            }
        })
    }

    private fun scheduleRetry() {
        if (!wanted) return
        retryTask?.let { main.removeCallbacks(it) }
        val delay = when {
            attempt <= 0 -> 500L
            attempt == 1 -> 1_000L
            attempt == 2 -> 2_000L
            attempt == 3 -> 4_000L
            attempt == 4 -> 8_000L
            else -> 10_000L
        }
        attempt += 1
        val task = Runnable { if (wanted) openSocket() }
        retryTask = task
        main.postDelayed(task, delay)
    }

    private fun hello(): String = JSONObject()
        .put("type", "hello")
        .put("version", 1)
        .put("transport", "websocket")
        .put("features", JSONObject())
        .put(
            "audio_params",
            JSONObject()
                .put("format", "pcm16")
                .put("sample_rate", 16000)
                .put("channels", 1)
                .put("frame_duration", 60),
        )
        .toString()

    private fun onJson(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (json.optString("type")) {
            "hello" -> {
                val params = json.optJSONObject("audio_params")
                if (params != null) {
                    audio = AudioSpec(
                        format = params.optString("format", "pcm16"),
                        sampleRate = params.optInt("sample_rate", 24000),
                        frameMs = params.optInt("frame_duration", 60),
                    )
                }
                val session = json.optString("session_id", "-")
                val spec = audio
                Log.d("dsh-dbg", "ws hello session=$session audio=$spec")
                post { listener?.onOpen(session, spec) }
            }
            "stt" -> {
                val value = json.optString("text")
                post { listener?.onStt(value) }
            }
            "llm" -> {
                val emotion = json.optString("emotion")
                if (emotion.isNotEmpty()) post { listener?.onEmotion(emotion) }
            }
            "tts" -> when (json.optString("state")) {
                "start" -> post { listener?.onSpeechStart() }
                "sentence_start" -> {
                    val sentence = json.optString("text")
                    post { listener?.onSentence(sentence) }
                }
                "stop" -> post { listener?.onSpeechStop() }
            }
            "alert" -> {
                val message = json.optString("message").ifBlank { "服务端出错，请稍后重试" }
                post { listener?.onAlert(message) }
            }
        }
    }

    private fun post(block: () -> Unit) {
        main.post { runCatching(block) }
    }

    // ---- 上行 ----

    /** 一句话开始：清空服务端缓冲，之后写 PCM。 */
    fun beginUtterance() {
        sendJson(JSONObject().put("type", "listen").put("state", "start").put("mode", "manual"))
    }

    fun sendAudio(pcm: ByteArray) {
        socket?.send(pcm.toByteString())
    }

    /** 一句话结束：服务端立刻识别。 */
    fun endUtterance() {
        sendJson(JSONObject().put("type", "listen").put("state", "stop"))
    }

    /** 打断：只用于挂断或服务端异常，正常对话不打断朗读（回复排队播）。 */
    fun abort() {
        sendJson(JSONObject().put("type", "abort").put("reason", "wake_word_detected"))
    }

    private fun sendJson(json: JSONObject) {
        socket?.send(json.toString())
    }
}
