package com.dsh.channel.whale.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/** 局域网发现：扫描本机 /24 的 8787 端口，用 /api/health 识别插件，无需改插件。 */
class LanDiscovery {
    data class Found(val baseUrl: String, val host: String, val latencyMs: Long, val backend: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(400, TimeUnit.MILLISECONDS)
        .readTimeout(900, TimeUnit.MILLISECONDS)
        .build()

    suspend fun scan(onFound: (Found) -> Unit): List<Found> = withContext(Dispatchers.IO) {
        val local = localIpv4() ?: throw IllegalStateException("未找到局域网 IPv4 地址（请连接 WiFi）")
        val prefix = local.substringBeforeLast('.')
        val all = mutableListOf<Found>()
        for (batch in (1..254).chunked(32)) {
            val found = coroutineScope {
                batch.map { n -> async { probe("$prefix.$n") } }.awaitAll()
            }.filterNotNull()
            found.forEach(onFound)
            all.addAll(found)
        }
        all
    }

    private fun probe(host: String): Found? {
        val start = System.currentTimeMillis()
        val request = Request.Builder().url("http://$host:8787/api/health").get().build()
        return try {
            http.newCall(request).execute().use { res ->
                val text = if (res.isSuccessful) res.body?.string() else null
                val obj = text?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (obj != null && obj.optBoolean("ok") && obj.optBoolean("pluginLoaded")) {
                    Found("http://$host:8787", host, System.currentTimeMillis() - start, obj.optString("backend", "?"))
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun localIpv4(): String? =
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress && !it.isLoopbackAddress }
            ?.hostAddress
}
