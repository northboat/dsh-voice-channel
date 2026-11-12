package com.dsh.channel.whale.ui

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import com.dsh.channel.whale.Emotion
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 形象包：`pack.json` + 同目录动画文件（gif / webp）。
 *
 * 接入标准：
 * ```json
 * {
 *   "schema": 1,
 *   "id": "deepy",            // 缺省用目录名
 *   "name": "Deepy",          // 设置页显示名
 *   "states": {               // 动画名 → 文件名，缺省 <动画名>.gif
 *     "idle": "notification.gif",
 *     "sleeping": "sleeping.gif"
 *   },
 *   "emotions": {             // 情绪 key → 动画名
 *     "neutral": "idle",
 *     "sleepy": "sleeping"
 *   },
 *   "fallback": "idle"        // 该情绪没有素材时退到哪个动画
 * }
 * ```
 *
 * 内置形象是不依赖素材的 Eva；除此之外所有素材都在 `filesDir/packs/<id>/`，
 * 只能通过导入 zip 添加，之后可重命名、覆盖导入、删除。APK 里不带任何素材文件。
 */
data class AvatarPack(
    val id: String,
    val name: String,
    val emotions: Map<String, String>,
    val states: Map<String, String>,
    val fallback: String,
    val files: Set<String>,
    val dir: File,
) {
    /** 情绪 → 素材引用；null 表示这个包没有可用文件，调用方回退内置 Eva。 */
    fun asset(emotion: Emotion): AvatarRef? {
        val anim = emotions[emotion.key] ?: fallback
        return resolve(anim) ?: resolve(fallback)
    }

    private fun resolve(anim: String): AvatarRef? {
        val file = states[anim] ?: "$anim.gif"
        if (!files.contains(file)) return null
        return AvatarRef.Local(File(dir, file))
    }
}

/** 素材位置：只可能是用户目录，assets 里的原始包在首次启动时已经复制过去。 */
sealed interface AvatarRef {
    data class Local(val file: File) : AvatarRef
}

object AvatarPacks {
    const val ROOT = "packs"

    /** 空字符串代表内置 Eva（不依赖任何素材文件）。 */
    const val BUILT_IN = ""

    private const val MAX_ENTRIES = 500

    /**
     * 素材第一帧的边缘平均色：页面背景用它，素材底色就和背景一致，看起来是融进去的。
     * 透明底素材（大部分像素 alpha=0）没有底色可言，返回 null 让页面用主题色。
     */
    fun tintOf(pack: AvatarPack): Int? = runCatching {
        val file = (pack.asset(Emotion.NEUTRAL) as? AvatarRef.Local)?.file ?: return@runCatching null
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
            decoder.setTargetSize(12, 12)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val w = bitmap.width
        val h = bitmap.height
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        var opaque = 0
        fun take(x: Int, y: Int) {
            val c = bitmap.getPixel(x, y)
            if (android.graphics.Color.alpha(c) < 200) return
            opaque += 1
            r += android.graphics.Color.red(c)
            g += android.graphics.Color.green(c)
            b += android.graphics.Color.blue(c)
            n += 1
        }
        for (x in 0 until w) { take(x, 0); take(x, h - 1) }
        for (y in 1 until h - 1) { take(0, y); take(w - 1, y) }
        bitmap.recycle()
        // 边缘几乎全透明 = 素材已经抠过底，别拿角色颜色去染整页背景。
        if (opaque * 3 < (w + h) * 2 || n == 0) null
        else android.graphics.Color.rgb(r / n, g / n, b / n)
    }.getOrNull()

    fun load(context: Context): List<AvatarPack> = runCatching {
        packsDir(context).listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { readDir(it) }
            .sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())

    fun find(packs: List<AvatarPack>, id: String): AvatarPack? =
        if (id.isEmpty()) null else packs.firstOrNull { it.id == id }

    /**
     * 导入 zip（压缩包根目录或唯一子目录里要有 pack.json）。
     * 同 id 视为覆盖更新。
     */
    fun importZip(context: Context, uri: Uri): AvatarPack {
        val staging = File(context.cacheDir, "pack-import-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                unzip(input, staging)
            } ?: throw IllegalStateException("读不到文件")

            val manifest = findManifest(staging) ?: throw IllegalStateException("包里没有 pack.json")
            val json = JSONObject(manifest.readText())
            val rawId = json.optString("id").ifBlank { manifest.parentFile?.name.orEmpty() }
            val id = rawId.replace(Regex("[^A-Za-z0-9_-]"), "")
            if (id.isBlank()) throw IllegalStateException("形象包 id 非法")

            val target = File(packsDir(context), id)
            if (target.exists()) target.deleteRecursively()
            manifest.parentFile?.copyRecursively(target, overwrite = true)
                ?: throw IllegalStateException("包结构异常")
            return readDir(target) ?: throw IllegalStateException("pack.json 解析失败")
        } finally {
            staging.deleteRecursively()
        }
    }

    /** 改名只动 `pack.json` 的 `name`，目录 id 保持不变（id 是引用键）。 */
    fun rename(pack: AvatarPack, name: String): AvatarPack? = runCatching {
        val clean = name.trim().take(24)
        if (clean.isBlank()) return null
        val manifest = File(pack.dir, "pack.json")
        val json = JSONObject(manifest.readText())
        json.put("name", clean)
        manifest.writeText(json.toString(2))
        readDir(pack.dir)
    }.getOrNull()

    fun delete(pack: AvatarPack): Boolean = pack.dir.deleteRecursively()

    private fun packsDir(context: Context): File =
        File(context.filesDir, ROOT).apply { mkdirs() }

    private fun unzip(input: java.io.InputStream, target: File) {
        val root = target.canonicalPath
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            var count = 0
            while (entry != null) {
                if (++count > MAX_ENTRIES) throw IllegalStateException("包内文件过多")
                val name = entry.name.replace('\\', '/')
                val out = File(target, name)
                // 防 zip slip：解出来的路径必须还在暂存目录里。
                if (!out.canonicalPath.startsWith(root)) throw IllegalStateException("包路径非法")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    /** zip 可能是 `pack.json + 资源`，也可能是 `目录/pack.json + 资源`。 */
    private fun findManifest(root: File): File? {
        File(root, "pack.json").takeIf { it.isFile }?.let { return it }
        val subDirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        if (subDirs.size != 1) return null
        return File(subDirs.first(), "pack.json").takeIf { it.isFile }
    }

    private fun readDir(dir: File): AvatarPack? = runCatching {
        val manifest = File(dir, "pack.json")
        if (!manifest.isFile) return null
        parse(manifest.readText(), dir.name, dir.list()?.toSet().orEmpty(), dir)
    }.getOrNull()

    private fun parse(text: String, dirName: String, files: Set<String>, dir: File): AvatarPack {
        val obj = JSONObject(text)
        return AvatarPack(
            id = obj.optString("id", dirName).ifBlank { dirName },
            name = obj.optString("name", dirName).ifBlank { dirName },
            emotions = obj.optJSONObject("emotions").toStringMap(),
            states = obj.optJSONObject("states").toStringMap(),
            fallback = obj.optString("fallback", "idle"),
            files = files,
            dir = dir,
        )
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return keys().asSequence().associateWith { getString(it) }
    }
}
