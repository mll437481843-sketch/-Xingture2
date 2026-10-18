package com.qqbq.export

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.os.Build
import java.nio.ByteBuffer

/** 动图解码：API 28+ 用 ImageDecoder（GIF/WebP 都能动），更低版本只能解首帧。 */
object ImageDecoderCompat {

    fun decode(context: Context, bytes: ByteArray): Drawable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            return ImageDecoder.decodeDrawable(source)
        }
        throw IllegalStateException("API < 28")
    }
}
