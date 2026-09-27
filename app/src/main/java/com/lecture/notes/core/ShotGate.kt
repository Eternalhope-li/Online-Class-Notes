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
import kotlin.math.sqrt

/**
 * 截取一帧屏幕。
 *
 * 系统不允许 App 偷偷截屏，必须先拿到用户授权（MediaProjection）。而这个授权系统规定
 * 一个 App 同时只能有一个：谁要是再申请一次，上一次会被立刻掐掉。内录音频的 [CaptureService]
 * 和悬浮截图的 [ShotService] 都要用，所以必须共用同一个，先开的那边负责登记，
 * 后开的那边直接拿来用，最后一个退出的才真正停掉。
 *
 * 「镜子」（VirtualDisplay + ImageReader）一个授权只建一次、建好就跟着一直用：
 * Android 14 起，在同一个 MediaProjection 上第二次 createVirtualDisplay() 会抛 SecurityException，
 * 而且系统会顺手把整个授权停掉 —— 内录用的正是同一个授权，于是连点两下截图就能把正在录的课一起掐了。
 * 所以「每张截图都临时建一次镜子」的老写法走不通。见 [MirrorSlot]。
 *
 * 常驻带来的新问题：系统只在画面**有变化**时才推新帧。视频一暂停、课件页一直停着，
 * 队列里就是空的，临时去要「一帧新的」只能等到超时 —— 表现就是屏幕明明亮着却提示「截图失败」。
 * 所以最近收到的那帧一直攥在 [FrameSlot] 里，新的来了才换，要帧的时候拿不到新的就用手里这张。
 *
 * 还有一条特别容易写反、写反就「怎么点都截不到」的规矩：**先挂 setOnImageAvailableListener，
 * 再 createVirtualDisplay / setSurface**。反过来首帧会赶在监听之前进队列，队列一满（[MAX_IMAGES]）
 * 系统就不再推帧，等到天荒地老也是空的（真机上踩过，旧版代码的写法才是对的）。
 */
object ShotGate {

    private const val TAG = "ShotGate"

    const val OWNER_RECORD = "record"
    const val OWNER_SHOT = "shot"

    /** 镜子刚建好时队列还是空的，第一帧要等一下。 */
    private const val FIRST_FRAME_TIMEOUT_MS = 1500L

    /**
     * 镜子的分辨率上限（像素总数）。
     *
     * 镜子是常驻的，屏幕每变一次就要多合成一帧、写一份 8 兆出头的缓冲：分辨率砍到这个量级，
     * 合成、解码、压缩全都跟着快一截，课件图在笔记里看依然清楚（视觉模型本来也会缩图）。
     * 只降不升，屏幕比这还小的机器不受影响。
     */
    private const val MAX_PIXELS = 2_600_000L

    /** 手里留一帧、队列里留两帧：既不会断粮，也不会把内存堆起来。 */
    private const val MAX_IMAGES = 3

    private val book = ProjectionBook<MediaProjection>()

    private val thread = HandlerThread("lecture-shot").apply { start() }
    private val handler = Handler(thread.looper)

    /** 应用上下文，用来问屏幕现在多大（转屏、折叠、换分辨率都要重新问）。 */
    @Volatile
    private var appCtx: Context? = null

    private class Target(val width: Int, val height: Int, val dpi: Int)

    private class Mirror(
        @Volatile var reader: ImageReader,
        @Volatile var width: Int,
        @Volatile var height: Int
    ) {
        /** 幕布。开镜子要「先挂监听、后建幕布」，所以先留空，建好了再填进来。 */
        @Volatile
        var display: VirtualDisplay? = null

        /** 最近一帧一直留在这儿（见 [FrameSlot]）。 */
        val frames = FrameSlot<android.media.Image>(dispose = { closeQuietly(it) })
    }

    private val mirror = MirrorSlot<MediaProjection, Mirror> { projection -> buildMirror(projection) }

    /** 登记一个使用方（内录 / 悬浮截图）。 */
    fun publish(ctx: Context, p: MediaProjection, from: String) {
        appCtx = ctx.applicationContext
        val same = book.get() === p
        book.put(p, from)
        // 换了一个新授权：旧镜子作废。同一个授权重复登记就别动它
        if (!same) closeMirror()
    }

    /** 现成能用的授权；null 说明得去弹授权框。 */
    fun current(): MediaProjection? = book.get()

    /** 使用方退出；只剩最后一个的时候才真正停掉授权。 */
    fun release(from: String) {
        val dead = book.drop(from) ?: return
        closeMirror()
        try {
            dead.stop()
        } catch (_: Throwable) {
        }
    }

    fun isReady(): Boolean = book.isReady()

    /**
     * 抓一帧屏幕；没授权、镜子建不出来、一帧都没等到才返回 null。
     *
     * [freshMs] 是「等一帧刚画出来的」上限：圆钮刚藏起来时要等系统重新合成一次，
     * 否则可能把圆钮自己也截进画面；画面完全静止、等不到新帧时，就用手里最新的那张兜底。
     * 传 0 表示不用等，直接用现成的最新一帧。
     */
    fun grab(freshMs: Long = 0): Bitmap? {
        val projection = book.get()
        if (projection == null) {
            Log.w(TAG, "grab: no projection")
            return null
        }
        val m = mirror.of(projection)
        if (m == null) {
            Log.w(TAG, "grab: no mirror")
            return null
        }
        // 转屏、折叠、切分辨率之后屏幕尺寸变了，给同一块幕布换一张新的底片（不新建 VirtualDisplay）
        appCtx?.let { retarget(m, it) }
        val first = shot(m, freshMs)
        if (first != null) return first
        // 一帧都没等到：给同一块幕布换张新底片再要一次。同一个授权只能换 surface、不能再建幕布，
        // 所以这条路是安全的；首帧本来就该到，这一下多半能把卡住的管道通开
        appCtx?.let { ctx -> target(ctx)?.let { t -> swapReader(m, t) } }
        val second = shot(m, freshMs)
        if (second == null) Log.w(TAG, "grab: no frame")
        return second
    }

    /** 从常驻的镜子里取一帧；一帧都还没来过时会等 [FIRST_FRAME_TIMEOUT_MS]。 */
    private fun shot(m: Mirror, freshMs: Long): Bitmap? =
        m.frames.use(freshMs = freshMs, firstMs = FIRST_FRAME_TIMEOUT_MS) { img ->
            toBitmap(img, m.width, m.height)
        }

    /** 屏幕尺寸变了（转屏、折叠、切分辨率）就换一块同样大的新底片。 */
    private fun retarget(m: Mirror, ctx: Context) {
        val t = target(ctx) ?: return
        if (m.width == t.width && m.height == t.height) return
        swapReader(m, t)
    }

    /**
     * 给同一块幕布换一张新底片：转屏时换尺寸，一帧都等不到时再要一次。
     *
     * 同一个授权不能再建第二个 VirtualDisplay，但可以换 surface。顺序仍然是那条铁律：
     * **先挂监听、再换底片** —— 反过来首帧会赶在监听之前进队列，队列一满就不推帧了。
     */
    private fun swapReader(m: Mirror, t: Target) {
        val display = m.display ?: return
        val reader = try {
            ImageReader.newInstance(t.width, t.height, PixelFormat.RGBA_8888, MAX_IMAGES)
        } catch (err: Throwable) {
            Log.w(TAG, "swap: reader", err)
            return
        }
        reader.setOnImageAvailableListener(listener(m), handler)
        val old = m.reader
        try {
            display.setSurface(reader.surface)
        } catch (err: Throwable) {
            Log.w(TAG, "swap: surface", err)
            reader.setOnImageAvailableListener(null, null)
            closeQuietly(reader)
            return
        }
        // 手里那帧出自旧底片，一起清掉；旧底片等系统那边换完手再收
        m.frames.clear()
        m.reader = reader
        m.width = t.width
        m.height = t.height
        handler.postDelayed({
            try {
                old.setOnImageAvailableListener(null, null)
            } catch (_: Throwable) {
            }
            closeQuietly(old)
        }, 500L)
    }

    /**
     * 建镜子：一块按当前屏幕尺寸的 VirtualDisplay，把屏幕内容投到 ImageReader 上。
     *
     * 拿到新授权之后只会被调一次；失败就不再重试 —— 那种情况下授权多半已经被系统废了，
     * 再试一次只会把内录也一起带走。
     */
    private fun buildMirror(projection: MediaProjection): Mirror? {
        val ctx = appCtx ?: return null
        val t = target(ctx) ?: return null
        return try {
            val reader = ImageReader.newInstance(t.width, t.height, PixelFormat.RGBA_8888, MAX_IMAGES)
            val m = Mirror(reader, t.width, t.height)
            // 顺序要紧：先挂监听、再开幕布。反过来的话首帧可能赶在监听之前进队列，
            // 队列一满系统就不再推帧 —— 怎么点都截不到（真机上踩过，见 README 第 39 条）
            reader.setOnImageAvailableListener(listener(m), handler)
            m.display = projection.createVirtualDisplay(
                "lecture-shot",
                t.width, t.height, t.dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )
            m
        } catch (t: Throwable) {
            Log.w(TAG, "mirror failed", t)
            null
        }
    }

    /** 来一帧就换一帧，最新那张一直留着（见 [Mirror.frames]）。 */
    private fun listener(m: Mirror): ImageReader.OnImageAvailableListener =
        ImageReader.OnImageAvailableListener { r ->
            val img: android.media.Image? = try {
                r.acquireLatestImage()
            } catch (t: Throwable) {
                Log.w(TAG, "acquire", t)
                null
            }
            if (img != null) m.frames.push(img)
        }

    /** 当前屏幕该用多大的镜子：像素总数封顶，长宽一起缩，不变形、不放大。 */
    private fun target(ctx: Context): Target? {
        val metrics = try {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val d = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
            DisplayMetrics().also {
                @Suppress("DEPRECATION")
                d.getRealMetrics(it)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "screen size", t)
            return null
        }
        val rw = metrics.widthPixels
        val rh = metrics.heightPixels
        if (rw <= 0 || rh <= 0) return null
        val k = sqrt(MAX_PIXELS.toDouble() / (rw.toDouble() * rh.toDouble())).toFloat().coerceAtMost(1f)
        // 取偶数：编码器对奇数边长不友好
        val w = ((rw * k).toInt() and 1.inv()).coerceAtLeast(2)
        val h = ((rh * k).toInt() and 1.inv()).coerceAtLeast(2)
        val dpi = (metrics.densityDpi * k).toInt().coerceAtLeast(1)
        return Target(w, h, dpi)
    }

    private fun closeMirror() {
        val old = mirror.clear() ?: return
        old.frames.clear()
        handler.post {
            try {
                old.display?.release()
            } catch (_: Throwable) {
            }
            try {
                old.reader.close()
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

private fun closeQuietly(c: AutoCloseable) {
    try {
        c.close()
    } catch (_: Throwable) {
    }
}