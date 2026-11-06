package com.dsh.channel.whale.ui

import android.content.Context
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
 * 两个来源：随包的 `assets/packs/<id>/`（只读）和用户导入的 `filesDir/packs/<id>/`。
 */
data class AvatarPack(
    val id: String,
    val name: String,
    val emotions: Map<String, String>,
    val states: Map<String, String>,
    val fallback: String,
    val files: Set<String>,
    /** 导入的包落在 filesDir；内置包为 null（读 assets）。 */
    val dir: File? = null,
) {
    val imported: Boolean get() = dir != null

    /** 情绪 → 素材引用；null 表示这个包没有可用文件，调用方回退内置动画。 */
    fun asset(emotion: Emotion): AvatarRef? {
        val anim = emotions[emotion.key] ?: fallback
        return resolve(anim) ?: resolve(fallback)
    }

    private fun resolve(anim: String): AvatarRef? {
        val file = states[anim] ?: "$anim.gif"
        if (!files.contains(file)) return null
        return dir?.let { AvatarRef.Local(File(it, file)) }
            ?: AvatarRef.Asset("${AvatarPacks.ROOT}/$id/$file")
    }
}

/** 素材位置：包内 assets 或用户导入的私有目录。 */
sealed interface AvatarRef {
    /** 相对 assets 的路径，如 `packs/deepy/idle.gif`。 */
    data class Asset(val path: String) : AvatarRef

    data class Local(val file: File) : AvatarRef
}

object AvatarPacks {
    const val ROOT = "packs"

    /** 空字符串代表内置程序化鲸鱼。 */
    const val BUILT_IN = ""

    private const val MAX_ENTRIES = 500

    fun load(context: Context): List<AvatarPack> {
        val builtIn = runCatching {
            context.assets.list(ROOT).orEmpty().mapNotNull { readAssets(context, it) }
        }.getOrDefault(emptyList())
        val imported = runCatching {
            packsDir(context).listFiles().orEmpty()
                .filter { it.isDirectory }
                .mapNotNull { readDir(it) }
        }.getOrDefault(emptyList())
        // 同 id 时导入的覆盖内置（否则设置页会出现两个同名项）。
        return (builtIn + imported).associateBy { it.id }.values.sortedBy { it.name.lowercase() }
    }

    fun find(packs: List<AvatarPack>, id: String): AvatarPack? =
        if (id.isEmpty()) null else packs.firstOrNull { it.id == id }

    /**
     * 导入 zip（压缩包根目录或唯一子目录里要有 pack.json）。
     * 返回新包；同 id 的旧包会被覆盖。
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

    /** 只允许删除导入的包；内置包在 assets 里，删不了也不需要删。 */
    fun delete(pack: AvatarPack): Boolean = pack.dir?.deleteRecursively() ?: false

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

    private fun readAssets(context: Context, dir: String): AvatarPack? = runCatching {
        val text = context.assets.open("$ROOT/$dir/pack.json").bufferedReader().use { it.readText() }
        parse(text, dir, context.assets.list("$ROOT/$dir")?.toSet().orEmpty(), null)
    }.getOrNull()

    private fun readDir(dir: File): AvatarPack? = runCatching {
        val manifest = File(dir, "pack.json")
        if (!manifest.isFile) return null
        parse(manifest.readText(), dir.name, dir.list()?.toSet().orEmpty(), dir)
    }.getOrNull()

    private fun parse(text: String, dirName: String, files: Set<String>, dir: File?): AvatarPack {
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
