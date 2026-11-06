package com.dsh.channel.whale.net

import android.content.Context
import java.util.UUID

/** 桌面端地址、配对码与稳定 device-id；SharedPreferences 避免额外依赖。 */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("dsh-whale", Context.MODE_PRIVATE)

    var endpoint: String
        get() = prefs.getString("endpoint", "") ?: ""
        set(value) = prefs.edit().putString("endpoint", normalize(value)).apply()

    var pin: String
        get() = prefs.getString("pin", "") ?: ""
        set(value) = prefs.edit().putString("pin", value.trim()).apply()

    val deviceId: String by lazy {
        prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }
    }

    companion object {
        /** 允许输入 "192.168.1.7" / "192.168.1.7:8787" / "http://192.168.1.7:8787"。 */
        fun normalize(value: String): String {
            var v = value.trim().trimEnd('/')
            if (v.isEmpty()) return ""
            if (!v.startsWith("http://") && !v.startsWith("https://")) v = "http://$v"
            val authority = v.substringAfter("://")
            return if (Regex(":\\d+$").containsMatchIn(authority)) v else "$v:8787"
        }
    }
}
