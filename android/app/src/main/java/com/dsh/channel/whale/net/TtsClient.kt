package com.dsh.channel.whale.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 插件端 TTS：只发文本，POST /api/tts（PC 侧 edge-tts 在线神经语音），返回 mp3。
 * 音色 / 语速由服务端决定（`DSH_TTS_VOICE` 或插件默认值），client 不参与选型——
 * 与 ASR 对称：手机既不知道用的哪个识别模型，也不该知道用的哪个音色。
 */
class TtsClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 阻塞调用，需在工作线程执行：把 mp3 写进 target，供 MediaPlayer 播放。 */
    fun synthesize(baseUrl: String, pin: String, deviceId: String, text: String, target: File): Result<File> {
        val payload = JSONObject().put("text", text).toString()
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/tts")
            .header("Authorization", "Bearer $pin")
            .header("X-PIN", pin)
            .header("device-id", deviceId)
            .post(payload.toRequestBody(JSON))
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error(
                        when (response.code) {
                            401 -> "配对码不正确"
                            413 -> "这段话太长，读不完"
                            503 -> "电脑端语音服务不可用"
                            else -> "朗读失败，请稍后重试"
                        }
                    )
                }
                val bytes = response.body?.bytes() ?: error("没有收到声音")
                if (bytes.isEmpty()) error("没有收到声音")
                target.outputStream().use { it.write(bytes) }
                target
            }
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
