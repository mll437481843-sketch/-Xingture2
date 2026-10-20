package com.qqbq.export

import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 扫描 + 识别。
 *
 * QQ 的缓存结构（实测）：
 *   .emotionsm/<表情包id>/<md5>               ← 贴纸本体（无后缀、前 24 字节奇数下标 XOR 1）
 *   .emotionsm/<表情包id>/<md5>_thu.png       ← 缩略图（无价值）
 *   .emotionsm/<表情包id>/<md5>_aio.png       ← 合集/预览图（无价值）
 *   .emotionsm/<表情包id>/<id>.jtmp           ← 元数据 JSON（含包名/作者）
 * 一个 <id> 目录 = 一个表情包，里面有 8～72 张贴纸，所以**绝不能按目录去重**。
 */
object Scanner {

    private const val HEAD_BYTES = 4096
    private const val MAX_SIZE = 32L * 1024 * 1024

    /** QQ 自带的推荐表情图等，不是用户收藏，扫大目录时排除掉。 */
    private val EXCLUDE_PATH_PARTS = listOf(
        "/.sticker_recommended_pics/",
        "/.sticker_personalized/",
        "/head/",
        "/.card/",
        "/newCard/",
        "/hotpic/",
        "/diskcache/",
        "/.thumb/",
        "/thumbnails/",
        // 微信里跟表情无关的大块缓存
        "/cgi/",
        "/Download/",
        "/music/",
        "/video/",
        "/voice2/",
        "/avatar/",
        "/wxacache/",
        "/.tmp/"
    )

    data class Found(
        val path: String,
        val size: Long,
        val mtime: Long,
        val format: String,
        val obfuscated: Boolean,
        val packId: String,
        val packName: String,
        val source: String,
        val category: String
    ) {

        /** 本次扫描是否第一次出现（"新增" 标识） */
        var isNew: Boolean = false

        /** 稳定身份：路径 + 大小 */
        val key: String get() = path + "|" + size


        val ext: String
            get() = when (format) {
                "GIF" -> "gif"
                "APNG" -> "png"
                "PNG" -> "png"
                "动态WebP" -> "webp"
                else -> "webp"
            }

        val mime: String
            get() = when (ext) {
                "gif" -> "image/gif"
                "png" -> "image/png"
                else -> "image/webp"
            }

        val fileName: String
            get() = File(path).name

        val label: String
            get() = packName + " · " + fileName

        val sizeText: String
            get() = when {
                size >= 1048576L -> String.format(Locale.US, "%.1fMB", size / 1048576.0)
                size >= 1024L -> (size / 1024L).toString() + "KB"
                else -> size.toString() + "B"
            }

        val timeText: String
            get() = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(mtime))
    }

    private const val PAGE_SIZE = 2000
    private const val MAX_PAGES = 80

    /** 目录不存在返回 null；存在则返回其中所有文件的绝对路径（分页取，避开 binder 1MB 上限）。 */
    fun listFiles(svc: IShell, root: String): List<String>? {
        val safe = root.replace("'", "").trim()
        if (safe.isEmpty()) return null
        val marker = svc.exec("if [ -d '" + safe + "' ]; then echo __HAS_DIR__; else echo __NO_DIR__; fi")
        if (marker.contains("__NO_DIR__")) return null

        val result = ArrayList<String>()
        var page = 0
        while (page < MAX_PAGES) {
            val from = page * PAGE_SIZE + 1
            val to = (page + 1) * PAGE_SIZE
            val out = svc.exec("find '" + safe + "' -type f 2>/dev/null | sed -n '" + from + "," + to + "p'")
            var count = 0
            for (line in out.split("\n")) {
                val path = line.trim()
                if (path.startsWith("/")) {
                    result.add(path)
                    count++
                }
            }
            if (count < PAGE_SIZE) break
            page++
        }
        return result
    }

    fun isExcludedPath(path: String): Boolean {
        for (part in EXCLUDE_PATH_PARTS) {
            if (path.contains(part)) return true
        }
        return false
    }

    /** 缩略图 / 合集图 / 元数据：都不是贴纸本体。 */
    private fun isJunkName(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        if (lower.endsWith("_thu.png") || lower.endsWith("_aio.png")) return true
        val junkExt = listOf(".jtmp", ".json", ".ini", ".db", ".tmp", ".log", ".dat", ".cfg", ".bak", ".txt")
        for (ext in junkExt) {
            if (lower.endsWith(ext)) return true
        }
        return false
    }

    /** 读 <包目录>/<包id>.jtmp 里的包名（JSON），失败返回 null。 */
    fun readPackName(svc: IShell, packDir: String, packId: String): String? {
        val raw = readAll(svc, packDir + "/" + packId + ".jtmp", 32768) ?: return null
        val text = String(raw, Charsets.UTF_8)
        for (key in listOf("\"name\"", "\"packName\"", "\"title\"")) {
            val value = extractJsonString(text, key) ?: continue
            if (value.isNotBlank()) return value
        }
        return extractJsonString(text, "\"author\"")
    }

    private fun extractJsonString(text: String, quotedKey: String): String? {
        val at = text.indexOf(quotedKey)
        if (at < 0) return null
        var i = text.indexOf(':', at + quotedKey.length)
        if (i < 0) return null
        i++
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        if (i >= text.length || text[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                sb.append(text[i + 1])
                i += 2
                continue
            }
            if (c == '"') break
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** 读图片真实宽高（GIF/PNG/WebP/JPEG），用来识别系统小图标。 */
    private fun imageSize(b: ByteArray): Pair<Int, Int>? {
        if (b.size >= 10) {
            val header = String(b, 0, 6, Charsets.ISO_8859_1)
            if (header == "GIF87a" || header == "GIF89a") {
                val w = (b[6].toInt() and 0xFF) or ((b[7].toInt() and 0xFF) shl 8)
                val h = (b[8].toInt() and 0xFF) or ((b[9].toInt() and 0xFF) shl 8)
                return Pair(w, h)
            }
        }
        if (b.size >= 24 && (b[0].toInt() and 0xFF) == 0x89 && (b[1].toInt() and 0xFF) == 0x50) {
            val w = intOf(b, 16)
            val h = intOf(b, 20)
            return Pair(w, h)
        }
        if (b.size >= 30 && (b[0].toInt() and 0xFF) == 0x52 && (b[8].toInt() and 0xFF) == 0x57) {
            val tag = String(b, 12, 4, Charsets.ISO_8859_1)
            if (tag == "VP8X") {
                val w = 1 + ((b[24].toInt() and 0xFF) or ((b[25].toInt() and 0xFF) shl 8) or ((b[26].toInt() and 0xFF) shl 16))
                val h = 1 + ((b[27].toInt() and 0xFF) or ((b[28].toInt() and 0xFF) shl 8) or ((b[29].toInt() and 0xFF) shl 16))
                return Pair(w, h)
            }
        }
        if (b.size >= 4 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xD8) {
            var i = 2
            while (i + 9 < b.size) {
                if ((b[i].toInt() and 0xFF) != 0xFF) { i++; continue }
                val marker = b[i + 1].toInt() and 0xFF
                val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    val h = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                    val w = ((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)
                    return Pair(w, h)
                }
                i += 2 + len
            }
        }
        return null
    }

    private fun intOf(b: ByteArray, offset: Int): Int {
        return ((b[offset].toInt() and 0xFF) shl 24) or ((b[offset + 1].toInt() and 0xFF) shl 16) or
            ((b[offset + 2].toInt() and 0xFF) shl 8) or (b[offset + 3].toInt() and 0xFF)
    }

    /** 按路径判断这是"我保存的 / 表情包 / 聊天图 / 系统资源 / 其他"。 */
    fun categorize(path: String): String {
        if (path.contains("/Pictures/") || path.contains("/DCIM/")) return "我保存的"
        if (path.contains("/.emotionsm/")) return "表情包"
        val systemParts = listOf(
            "/wxanewfiles/", "/wxafiles/", "/appbrand/", "/wallet/", "/mapsdk/", "/xlog/",
            "/openapi", "/ThumbVideoCache/", "/chatroom_notice/", "/package/", "/recbiz/",
            "/selectrecord/", "/.sticker_recommended_pics/", "/assets/", "/bundled", "/head/"
        )
        for (part in systemParts) {
            if (path.contains(part)) return "系统资源"
        }
        if (path.contains("/chatpic/") || path.contains("/photo/") || path.contains("/thumb/") ||
            path.contains("/image/") || path.contains("/image2/") || path.contains("/cache/") ||
            path.contains("/sfs/")
        ) return "聊天图"
        return "其他"
    }

    fun classify(
        svc: IShell,
        path: String,
        wantStatic: Boolean,
        packId: String,
        packName: String,
        sourceName: String
    ): Found? {
        if (isExcludedPath(path)) return null
        val name = File(path).name
        if (name.isEmpty()) return null
        if (isJunkName(name)) return null

        val pfd = openQuietly(svc, path) ?: return null
        try {
            val stat = Os.fstat(pfd.fileDescriptor)
            if (stat.st_size < 120L || stat.st_size > MAX_SIZE) return null
            val head = readHead(pfd.fileDescriptor, HEAD_BYTES)
            if (head.size < 12) return null
            val detected = detect(head) ?: return null
            if (!wantStatic && (detected.first == "PNG" || detected.first == "WebP")) return null
            var category = categorize(path)
            val size = imageSize(head)
            val animated = detected.first == "GIF" || detected.first == "APNG" || detected.first == "动态WebP"
            if (size != null) {
                val maxSide = Math.max(size.first, size.second)
                // 很小的图基本是 UI 图标/角标，不是用户要的表情或照片
                if (maxSide in 1..64 || (stat.st_size < 2048L && !animated)) category = "系统资源"
                if (category == "聊天图" && maxSide in 1..96) category = "系统资源"
            }
            return Found(
                path, stat.st_size, stat.st_mtime * 1000L, detected.first, detected.second,
                packId, packName, sourceName, category
            )
        } catch (t: Throwable) {
            return null
        } finally {
            closeQuietly(pfd)
        }
    }

    fun readAll(svc: IShell, path: String, maxBytes: Int = 32 * 1024 * 1024): ByteArray? {
        val pfd = openQuietly(svc, path) ?: return null
        try {
            val input = FileInputStream(pfd.fileDescriptor)
            val out = ByteArrayOutputStream(65536)
            val buffer = ByteArray(65536)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                out.write(buffer, 0, n)
                total += n
                if (total > maxBytes) return null
            }
            return out.toByteArray()
        } catch (t: Throwable) {
            return null
        } finally {
            closeQuietly(pfd)
        }
    }

    data class Probe(
        val size: Long,
        val mtime: Long,
        val hex: String,
        val rawType: String,
        val decodedType: String
    )

    fun probe(svc: IShell, path: String): Probe? {
        val pfd = openQuietly(svc, path) ?: return null
        try {
            val stat = Os.fstat(pfd.fileDescriptor)
            val head = readHead(pfd.fileDescriptor, 64)
            if (head.isEmpty()) return null
            val raw = magicOf(head) ?: jpegOf(head)
            val copy = head.copyOf()
            decodeHeader(copy)
            val decoded = magicOf(copy) ?: jpegOf(copy)
            return Probe(stat.st_size, stat.st_mtime * 1000L, hexOf(head), raw ?: "-", decoded ?: "-")
        } catch (t: Throwable) {
            return null
        } finally {
            closeQuietly(pfd)
        }
    }

    private fun jpegOf(b: ByteArray): String? {
        if (b.size >= 3 && u(b[0]) == 0xFF && u(b[1]) == 0xD8 && u(b[2]) == 0xFF) return "JPEG"
        return null
    }

    private fun hexOf(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(String.format(Locale.US, "%02x", x.toInt() and 0xFF))
        return sb.toString()
    }

    private fun openQuietly(svc: IShell, path: String): ParcelFileDescriptor? {
        return try {
            svc.openRead(path)
        } catch (t: Throwable) {
            null
        }
    }

    private fun closeQuietly(pfd: ParcelFileDescriptor?) {
        try {
            pfd?.close()
        } catch (t: Throwable) {
        }
    }

    private fun readHead(fd: FileDescriptor, size: Int): ByteArray {
        val input = FileInputStream(fd)
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read <= 0) break
            offset += read
        }
        return if (offset == size) buffer else buffer.copyOf(offset)
    }

    private fun detect(head: ByteArray): Pair<String, Boolean>? {
        magicOf(head)?.let { return Pair(it, false) }
        val copy = head.copyOf()
        decodeHeader(copy)
        magicOf(copy)?.let { return Pair(it, true) }
        return null
    }

    /** 还原被 QQ 处理过的文件头：前 24 字节中下标 1、3、5… 的字节 XOR 1。 */
    fun decodeHeader(bytes: ByteArray) {
        var i = 1
        while (i < 24 && i < bytes.size) {
            bytes[i] = (bytes[i].toInt() xor 0x01).toByte()
            i += 2
        }
    }

    private fun magicOf(b: ByteArray): String? {
        if (b.size >= 6) {
            val header = String(b, 0, 6, Charsets.ISO_8859_1)
            if (header == "GIF87a" || header == "GIF89a") return "GIF"
        }
        if (b.size >= 8 && u(b[0]) == 0x89 && u(b[1]) == 0x50 && u(b[2]) == 0x4E && u(b[3]) == 0x47 &&
            u(b[4]) == 0x0D && u(b[5]) == 0x0A && u(b[6]) == 0x1A && u(b[7]) == 0x0A
        ) {
            return if (contains(b, "acTL")) "APNG" else "PNG"
        }
        if (b.size >= 12 && u(b[0]) == 0x52 && u(b[1]) == 0x49 && u(b[2]) == 0x46 && u(b[3]) == 0x46 &&
            u(b[8]) == 0x57 && u(b[9]) == 0x45 && u(b[10]) == 0x42 && u(b[11]) == 0x50
        ) {
            return if (contains(b, "ANIM")) "动态WebP" else "WebP"
        }
        return null
    }

    private fun u(b: Byte): Int = b.toInt() and 0xFF

    private fun contains(b: ByteArray, ascii: String): Boolean {
        val pattern = ascii.toByteArray(Charsets.ISO_8859_1)
        if (b.size < pattern.size) return false
        var i = 0
        while (i <= b.size - pattern.size) {
            var j = 0
            while (j < pattern.size && b[i + j] == pattern[j]) j++
            if (j == pattern.size) return true
            i++
        }
        return false
    }
}
