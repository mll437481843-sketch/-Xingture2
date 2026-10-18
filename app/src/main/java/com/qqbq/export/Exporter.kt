package com.qqbq.export

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 把识别出来的表情写到相册目录 Pictures/QQ表情/。
 * 同时做两件事：还原被 QQ 处理过的文件头、按内容去重（同一张表情常被缓存多份）。
 */
object Exporter {

    const val ALBUM = "QQ表情"

    data class Result(val ok: Int, val duplicate: Int, val failed: Int)

    fun exportAll(
        context: Context,
        svc: IShell,
        items: List<Scanner.Found>,
        onEach: (Int, Scanner.Found, Boolean) -> Unit
    ): Result {
        var ok = 0
        var duplicate = 0
        var failed = 0
        val seen = HashSet<String>()
        val resolver = context.contentResolver

        for (index in items.indices) {
            val item = items[index]
            var success = false
            try {
                val bytes = Scanner.readAll(svc, item.path)
                if (bytes == null || bytes.size < 32) {
                    failed++
                } else {
                    if (item.obfuscated) Scanner.decodeHeader(bytes)
                    val digest = sha256(bytes)
                    if (!seen.add(digest)) {
                        duplicate++
                    } else if (save(context, resolver, buildName(item, ok + 1), item.mime, bytes)) {
                        ok++
                        success = true
                    } else {
                        failed++
                    }
                }
            } catch (t: Throwable) {
                failed++
            }
            onEach(index + 1, item, success)
        }
        return Result(ok, duplicate, failed)
    }

    private fun save(
        context: Context,
        resolver: ContentResolver,
        name: String,
        mime: String,
        bytes: ByteArray
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues()
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
                values.put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/" + ALBUM
                )
                values.put(MediaStore.MediaColumns.IS_PENDING, 1)
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    false
                } else {
                    val output = resolver.openOutputStream(uri)
                    if (output == null) {
                        false
                    } else {
                        output.use { it.write(bytes) }
                        val done = ContentValues()
                        done.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, done, null, null)
                        true
                    }
                }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    ALBUM
                )
                if (!dir.exists() && !dir.mkdirs()) {
                    false
                } else {
                    val file = File(dir, name)
                    file.writeBytes(bytes)
                    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
                    true
                }
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun buildName(item: Scanner.Found, seq: Int): String {
        val fileName = if (item.fileName.isBlank()) "emotion" else item.fileName
        val raw = item.packName + "_" + fileName
        val builder = StringBuilder()
        for (c in raw) {
            val keep = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || (c.code in 0x4E00..0x9FFF)
            builder.append(if (keep) c else '_')
        }
        var base = builder.toString()
        while (base.contains("__")) {
            base = base.replace("__", "_")
        }
        if (base.length > 40) base = base.substring(0, 40)
        return "QQ" + base + "_" + seq + "." + item.ext
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val builder = StringBuilder(digest.size * 2)
        for (b in digest) {
            builder.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
        }
        return builder.toString()
    }
}
