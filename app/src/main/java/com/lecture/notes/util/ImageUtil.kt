package com.lecture.notes.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.LruCache
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * 截图相关的位图工具。
 *
 * 两件事要同时顾到：
 *  - 存下来能看清：全屏截图按原分辨率存，JPEG 质量 92，课件上的小字也不会糊；
 *  - 发给视觉模型不能太大：长边压到 1280 以内再转 base64，否则又慢又容易超模型的上下文。
 */
object ImageUtil {

    const val STORE_QUALITY = 92
    const val VISION_MAX_EDGE = 1280
    const val VISION_QUALITY = 80

    /** 不把整张图解进内存，先只读尺寸。 */
    fun sizeOf(f: File): Pair<Int, Int>? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    } catch (_: Throwable) {
        null
    }

    private fun sampleSize(w: Int, h: Int, maxPx: Int): Int {
        var s = 1
        var long = maxOf(w, h)
        while (long / 2 >= maxPx) {
            s *= 2
            long /= 2
        }
        return s
    }

    /** 按需降采样解码；[maxPx] 是长边上限。 */
    fun decode(f: File, maxPx: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        val o = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(f.absolutePath, o)
    } catch (_: Throwable) {
        null
    }

    /** 长边超过 [maxEdge] 就等比缩小；本来就小就原样返回。 */
    fun fit(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val long = maxOf(bitmap.width, bitmap.height)
        if (long <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / long
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bitmap, Rect(0, 0, bitmap.width, bitmap.height), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    fun jpeg(bitmap: Bitmap, quality: Int = STORE_QUALITY): ByteArray {
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        return bos.toByteArray()
    }

    /** 给视觉模型用的 base64（不带 data URL 前缀）。 */
    fun visionBase64(bitmap: Bitmap): String {
        val small = fit(bitmap, VISION_MAX_EDGE)
        val bytes = jpeg(small, VISION_QUALITY)
        if (small !== bitmap) small.recycle()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * data URL 的头。拆成几段拼出来，是为了避免源码里出现一长串会被误认成图片数据的字面量。
     */
    val DATA_HEAD: String = "data" + ":" + "image/jpeg" + ";" + "base64" + ","

    fun dataUrl(base64: String): String = DATA_HEAD + base64

    /** 直接从文件生成给视觉模型用的 base64（先降采样再压缩，别把全屏原图整张发过去）。 */
    fun visionBase64(f: File): String? {
        val bmp = decode(f, VISION_MAX_EDGE) ?: return null
        val out = visionBase64(bmp)
        bmp.recycle()
        return out
    }

    fun writeJpeg(f: File, bitmap: Bitmap, quality: Int = STORE_QUALITY): Boolean = try {
        f.parentFile?.mkdirs()
        f.outputStream().use { it.write(jpeg(bitmap, quality)) }
        true
    } catch (_: Throwable) {
        false
    }

    fun fileToBase64(f: File): String? = try {
        Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
    } catch (_: Throwable) {
        null
    }
}

/**
 * 列表里的小缩略图：内存缓存 + 后台解码。
 *
 * 没用图片加载库，是因为这个 App 只有一种图片来源（本机笔记目录里的截图），
 * 几十行就够，还能少一个依赖。
 */
object Thumbs {

    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** [maxPx] 长边，[into] 会被打上 tag 防止滑动时串图。 */
    fun load(f: File, maxPx: Int, into: ImageView, tag: String) {
        into.tag = tag
        val key = f.absolutePath + ":" + f.lastModified() + ":" + maxPx
        cache.get(key)?.let {
            into.setImageBitmap(it)
            return
        }
        into.setImageDrawable(null)
        pool.execute {
            val bmp = ImageUtil.decode(f, maxPx)
            if (bmp == null) return@execute
            cache.put(key, bmp)
            main.post {
                if (into.tag == tag) into.setImageBitmap(bmp)
            }
        }
    }

    fun clear() = cache.evictAll()
}
