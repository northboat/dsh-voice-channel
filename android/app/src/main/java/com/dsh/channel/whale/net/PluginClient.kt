package com.dsh.channel.whale.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 连接设置里的连通性检查：`GET /api/health`（不需要配对码）。
 *
 * 对话与语音已全部搬到 WS 长连接（[WsSession]），这里只保留「地址填对没有」这一件事——
 * 它能在握手前就区分「地址不可达」和「配对码不对」，设置页的提示更准。
 */
class PluginClient(private val store: SettingsStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun check(): Result<String> = withContext(Dispatchers.IO) {
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
