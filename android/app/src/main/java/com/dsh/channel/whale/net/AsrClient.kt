package com.dsh.channel.whale.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * PC 侧离线 ASR：把整段 PCM（s16le / 16 kHz / mono）POST 到插件端的 /api/asr。
 * 手机上不加载任何语音模型，APK 不需要 Vosk / JNA / 42 MB 模型。
 */
class AsrClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 阻塞调用，需在工作线程执行。返回识别出的文本（可能为空串）。 */
    fun transcribe(url: String, pcm: ByteArray, pin: String, deviceId: String): Result<String> {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $pin")
            .header("X-PIN", pin)
            .header("device-id", deviceId)
            .post(pcm.toRequestBody(PCM))
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(body) }.getOrNull()
                if (!response.isSuccessful) {
                    error(
                        when (response.code) {
                            401 -> "配对码不正确"
                            503 -> "电脑端识别服务不可用"
                            else -> "识别失败，再试一次"
                        }
                    )
                }
                json?.optString("text").orEmpty()
            }
        }
    }

    private companion object {
        val PCM = "audio/pcm; rate=16000; channels=1; encoding=s16le".toMediaType()
    }
}
