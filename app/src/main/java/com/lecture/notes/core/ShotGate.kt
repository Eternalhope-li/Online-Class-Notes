package com.lecture.notes.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 截取一帧屏幕。
 *
 * 系统不允许 App 偷偷截屏，必须先拿到用户授权（MediaProjection）。而这个授权系统规定
 * 一个 App 同时只能有一个：谁要是再申请一次，上一次会被立刻掐掉。内录音频的 [CaptureService]
 * 和悬浮截图的 [ShotService] 都要用，所以必须共用同一个，先开的那边负责登记，
 * 后开的那边直接拿来用，最后一个退出的才真正停掉。
 *
 * 抓一帧的做法是临时建一个 VirtualDisplay 把它投到 ImageReader 上，拿到第一帧就拆掉，
 * 所以不会一直占着编码器，也不会影响内录。
 */
object ShotGate {

    private const val TAG = "ShotGate"

    const val OWNER_RECORD = "record"
    const val OWNER_SHOT = "shot"

    private val book = ProjectionBook<MediaProjection>()

    private val thread = HandlerThread("lecture-shot").apply { start() }
    private val handler = Handler(thread.looper)
    private val readerHandler = Handler(thread.looper)

    /** 登记一个使用方（内录 / 悬浮截图）。 */
    fun publish(p: MediaProjection, from: String) = book.put(p, from)

    /** 现成能用的授权；null 说明得去弹授权框。 */
    fun current(): MediaProjection? = book.get()

    /** 使用方退出；只剩最后一个的时候才真正停掉授权。 */
    fun release(from: String) {
        val dead = book.drop(from)
        try {
            dead?.stop()
        } catch (_: Throwable) {
        }
    }

    fun isReady(): Boolean = book.isReady()

    /** 抓一帧屏幕；没授权、屏幕没亮或者超时返回 null。 */
    fun grab(ctx: Context, timeoutMs: Long = 3000): Bitmap? {
        val proj = book.get()
        if (proj == null) {
            Log.w(TAG, "grab: no projection")
            return null
        }
        val metrics = DisplayMetrics()
        val size = try {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        } catch (t: Throwable) {
            Log.w(TAG, "grab: size", t)
            return null
        }
        val w = size.first
        val h = size.second
        if (w <= 0 || h <= 0) return null

        var reader: ImageReader? = null
        var display: VirtualDisplay? = null
        return try {
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader = r
            val latch = CountDownLatch(1)
            val once = AtomicBoolean(false)
            var shot: Bitmap? = null
            r.setOnImageAvailableListener({ source ->
                if (!once.compareAndSet(false, true)) {
                    source.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }
                try {
                    val image = source.acquireLatestImage()
                    if (image != null) {
                        shot = toBitmap(image, w, h)
                        image.close()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "grab: decode", t)
                } finally {
                    latch.countDown()
                }
            }, readerHandler)

            display = proj.createVirtualDisplay(
                "lecture-shot",
                w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler
            )
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            shot
        } catch (t: Throwable) {
            Log.w(TAG, "grab failed", t)
            null
        } finally {
            try {
                display?.release()
            } catch (_: Throwable) {
            }
            try {
                reader?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun toBitmap(image: android.media.Image, width: Int, height: Int): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val raw = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buffer)
        if (paddedWidth == width) return raw
        val out = Bitmap.createBitmap(raw, 0, 0, width, height)
        raw.recycle()
        return out
    }
}
