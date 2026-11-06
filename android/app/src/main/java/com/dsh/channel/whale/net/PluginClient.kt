package com.dsh.channel.whale.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 插件 /api/chat：NDJSON 逐行解析 state / delta / emotion / done / error。 */
class PluginClient(private val store: SettingsStore) {

    sealed interface Event {
        data class State(val name: String) : Event
        data class Delta(val text: String) : Event
        data class Mood(val emotion: String) : Event
        data class Done(val text: String) : Event
        data class Failure(val message: String) : Event
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    fun chat(text: String): Flow<Event> = callbackFlow {
        val base = store.endpoint
        if (base.isEmpty()) {
            trySend(Event.Failure("请先在设置里连接桌面端"))
            close()
            return@callbackFlow
        }
        val json = JSONObject()
            .put("text", text)
            .put("sessionId", store.deviceId)
            .toString()
        val request = Request.Builder()
            .url("$base/api/chat")
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Authorization", "Bearer ${store.pin}")
            .header("X-PIN", store.pin)
            .header("device-id", store.deviceId)
            .build()

        val call = http.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                trySend(Event.Failure("网络异常，请确认和电脑在同一网络"))
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    if (!res.isSuccessful) {
                        trySend(Event.Failure(if (res.code == 401) "配对码不正确" else "对话失败，请稍后重试"))
                    } else {
                        val body = res.body
                        if (body == null) {
                            trySend(Event.Failure("没有收到回复，请稍后重试"))
                        } else {
                            body.byteStream().bufferedReader(Charsets.UTF_8).useLines { lines ->
                                for (line in lines) {
                                    if (line.isBlank()) continue
                                    val obj = try { JSONObject(line) } catch (_: Exception) { continue }
                                    when (obj.optString("type")) {
                                        "state" -> trySend(Event.State(obj.optString("state")))
                                        "delta" -> trySend(Event.Delta(obj.optString("text")))
                                        "emotion" -> trySend(Event.Mood(obj.optString("emotion")))
                                        "done" -> trySend(Event.Done(obj.optString("text")))
                                        "error" -> trySend(Event.Failure(obj.optString("message")))
                                    }
                                }
                            }
                        }
                    }
                }
                close()
            }
        })
        awaitClose { call.cancel() }
    }

    /** 连接时的连通性检查：/api/health 不需要 PIN。 */
    suspend fun health(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val base = store.endpoint
            if (base.isEmpty()) error("未配置插件地址")
            val request = Request.Builder().url("$base/api/health").get().build()
            http.newCall(request).execute().use { res ->
                if (!res.isSuccessful) error("HTTP ${res.code}")
                val obj = JSONObject(res.body?.string().orEmpty())
                if (!obj.optBoolean("ok") || !obj.optBoolean("pluginLoaded")) error("插件未就绪")
                "backend=${obj.optString("backend")} · session=${obj.optString("sessionId", "-")}"
            }
        }
    }
}
