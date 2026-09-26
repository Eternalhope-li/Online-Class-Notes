package com.lecture.notes.core

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.lecture.notes.App
import com.lecture.notes.R
import com.lecture.notes.data.Note
import com.lecture.notes.data.NoteStore
import com.lecture.notes.databinding.ViewShotBallBinding
import com.lecture.notes.databinding.ViewShotBubbleBinding
import com.lecture.notes.net.LlmDigest
import com.lecture.notes.util.ImageUtil
import com.lecture.notes.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 悬浮截图按钮。
 *
 * 屏幕上挂一个能随便拖的小圆钮，点一下就把当前画面截下来、存进笔记，
 * 顺手丢给视觉模型（默认 glm-4v-flash，免费）分析成要点。
 *
 * 授权方式和内容：[Recorder] 内录用的是同一个 MediaProjection 机制，
 * 所以授权流程和 [CaptureService] 完全一致 —— 先在 Activity 里拿到用户同意，
 * 再带着类型启动前台服务，最后才 getMediaProjection()。
 */
class ShotService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var wm: WindowManager? = null
    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var bubbleView: View? = null
    private var started = false
    private var capturing = false
    private var waitRetries = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }

            ACTION_CAPTURE -> {
                if (started) capture()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                if (!started) begin(intent)
                return START_NOT_STICKY
            }
        }
        if (!started) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        removeOverlays()
        // 只退掉自己的登记；录音还在用同一个授权的话不能停，否则会把录音掐断
        ShotGate.release(ShotGate.OWNER_SHOT)
        Prefs.shotFloat = false
        super.onDestroy()
    }

    // ------------------------------------------------------------ 启动

    private fun begin(intent: Intent) {
        val reuse = intent.getBooleanExtra(EXTRA_REUSE_PROJECTION, false)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        // 录音已经在用录屏授权了就直接复用，别再申请一次（系统会把上一次掐掉）
        val shared = if (reuse) ShotGate.current() else null
        if (reuse && shared == null && waitRetries < 5) {
            // 录音那边是 startForegroundService 拉起来的，MediaProjection 可能还没登记完。
            // 稍等一下再来一次（不阻塞主线程），别抢跑导致截图按钮一闪就没了。
            waitRetries++
            handler.postDelayed({ begin(intent) }, 250)
            return
        }
        waitRetries = 0
        if (shared == null && (resultCode != Activity.RESULT_OK || resultData == null)) {
            Prefs.shotFloat = false
            stopSelf()
            return
        }

        started = true
        startForegroundCompat()

        val p = if (shared != null) {
            shared
        } else {
            try {
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mgr.getMediaProjection(resultCode, resultData!!)
            } catch (t: Throwable) {
                Log.w(TAG, "getMediaProjection failed", t)
                null
            }
        }
        if (p == null) {
            bubble(getString(R.string.shot_failed))
            handler.postDelayed({ stopEverything() }, 1200)
            return
        }
        ShotGate.publish(p, ShotGate.OWNER_SHOT)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // 用户在系统的录屏状态条上点了「停止」
                bubble(getString(R.string.shot_projection_lost))
                handler.postDelayed({ stopEverything() }, 1200)
            }
        }, handler)

        if (!Settings.canDrawOverlays(this)) {
            bubble(getString(R.string.shot_need_overlay))
            handler.postDelayed({ stopEverything() }, 1500)
            return
        }
        showOverlays()
        Prefs.shotFloat = true
        notifyState()
    }

    private fun startForegroundCompat() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun stopEverything() {
        Prefs.shotFloat = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------ 悬浮窗

    private fun showOverlays() {
        val w = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = w
        val binding = ViewShotBallBinding.inflate(LayoutInflater.from(this))
        val dm = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val sx = Prefs.shotX
            val sy = Prefs.shotY
            x = if (sx < 0) dm.widthPixels - dp(70) else sx.toInt()
            y = if (sy < 0) (dm.heightPixels * 0.35f).toInt() else sy.toInt()
        }
        ballParams = params

        val drag = DragTouch()
        binding.root.setOnTouchListener(drag)
        binding.ball.setOnTouchListener(DragTouch { capture() })
        binding.close.setOnTouchListener(DragTouch { stopEverything() })

        w.addView(binding.root, params)
        ballView = binding.root

        val bubble = ViewShotBubbleBinding.inflate(LayoutInflater.from(this))
        val bp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(96)
        }
        bubble.root.visibility = View.GONE
        w.addView(bubble.root, bp)
        bubbleView = bubble.root
    }

    private fun removeOverlays() {
        val w = wm ?: return
        try {
            ballView?.let { w.removeView(it) }
        } catch (_: Throwable) {
        }
        try {
            bubbleView?.let { w.removeView(it) }
        } catch (_: Throwable) {
        }
        ballView = null
        bubbleView = null
        wm = null
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private inner class DragTouch(private val onTap: (() -> Unit)? = null) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var fromX = 0
        private var fromY = 0
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val p = ballParams ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    fromX = p.x
                    fromY = p.y
                    moved = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(5) || abs(dy) > dp(5)) moved = true
                    p.x = fromX + dx.toInt()
                    p.y = fromY + dy.toInt()
                    clamp(p)
                    try {
                        wm?.updateViewLayout(ballView, p)
                    } catch (_: Throwable) {
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (moved) snap(p) else onTap?.invoke()
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (moved) snap(p)
                    return true
                }
            }
            return false
        }
    }

    private fun clamp(p: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        p.x = p.x.coerceIn(-dp(12), dm.widthPixels - dp(56))
        p.y = p.y.coerceIn(0, (dm.heightPixels - dp(120)).coerceAtLeast(0))
    }

    /** 松手后贴边，并记住位置。 */
    private fun snap(p: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        val tw = ballView?.width ?: dp(66)
        p.x = if (p.x + tw / 2 < dm.widthPixels / 2) dp(4) else dm.widthPixels - tw - dp(4)
        clamp(p)
        try {
            wm?.updateViewLayout(ballView, p)
        } catch (_: Throwable) {
        }
        Prefs.shotX = p.x.toFloat()
        Prefs.shotY = p.y.toFloat()
    }

    private fun bubble(text: String) {
        handler.post {
            val v = bubbleView ?: return@post
            (v as? android.widget.TextView)?.text = text
            v.visibility = View.VISIBLE
            handler.removeCallbacks(hideBubble)
            handler.postDelayed(hideBubble, 2200)
        }
    }

    /** 抓图的那一瞬间把自己藏起来，否则圆钮和气泡会被一起截进画面。 */
    private fun setOverlayVisible(visible: Boolean) {
        handler.post {
            ballView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            if (!visible) bubbleView?.visibility = View.GONE
        }
    }

    private val hideBubble = Runnable {
        bubbleView?.visibility = View.GONE
    }

    // ------------------------------------------------------------ 截图

    private fun capture() {
        if (capturing) return
        capturing = true
        setOverlayVisible(false)
        handler.postDelayed({
            scope.launch {
                val bitmap = try {
                    ShotGate.grab(this@ShotService)
                } catch (t: Throwable) {
                    Log.w(TAG, "grab", t)
                    null
                }
                setOverlayVisible(true)
                if (bitmap == null) {
                    capturing = false
                    bubble(getString(R.string.shot_failed))
                    return@launch
                }
                bubble(getString(R.string.shot_capturing))

                var visionPayload: String? = null
                if (Prefs.shotAutoAnalyze && LlmDigest.isReady()) {
                    visionPayload = try {
                        ImageUtil.visionBase64(bitmap)
                    } catch (t: Throwable) {
                        Log.w(TAG, "encode", t)
                        null
                    }
                }

                val note = targetNote()
                val rel = NoteStore.saveShot(note.id, bitmap)
                bitmap.recycle()
                if (rel == null) {
                    capturing = false
                    bubble(getString(R.string.shot_failed))
                    return@launch
                }
                val atMs = offsetOf(note)
                NoteStore.appendImage(note.id, atMs, rel, "")
                capturing = false
                bubble(getString(R.string.shot_saved, note.title))

                if (Prefs.shotAutoAnalyze) {
                    if (visionPayload == null) {
                        if (!LlmDigest.isReady()) bubble(getString(R.string.shot_need_key))
                    } else {
                        analyze(note.id, atMs, rel, visionPayload, note.title)
                    }
                }
            }
        }, HIDE_BEFORE_GRAB_MS)
    }

    private suspend fun analyze(id: String, atMs: Long, rel: String, base64: String, title: String) {
        bubble(getString(R.string.shot_analyzing))
        try {
            val caption = LlmDigest.analyzeImage(base64, title)
            if (caption.isBlank()) throw LlmDigest.LlmException("模型没有返回内容")
            NoteStore.setImageCaption(id, atMs, rel, caption, true)
            bubble(getString(R.string.shot_analyzed))
        } catch (t: Throwable) {
            NoteStore.setImageCaption(id, atMs, rel, "", false)
            bubble(getString(R.string.shot_analyze_failed, t.message ?: t.javaClass.simpleName))
        }
    }

    private fun targetNote(): Note {
        val st = Recorder.state.value
        val id = st.noteId
        if (Prefs.shotToRecording && st.active && id != null) {
            NoteStore.load(id)?.let { return it }
        }
        return NoteStore.todayShots()
    }

    private fun offsetOf(note: Note): Long {
        val st = Recorder.state.value
        if (st.active && st.noteId == note.id) return Recorder.elapsedMs()
        return (System.currentTimeMillis() - note.createdAt).coerceAtLeast(0L)
    }

    // ------------------------------------------------------------ 通知

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.lecture.notes.ui.SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val shoot = PendingIntent.getService(
            this, 1,
            Intent(this, ShotService::class.java).setAction(ACTION_CAPTURE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, ShotService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, App.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(getString(R.string.shot_notif_title))
            .setContentText(getString(R.string.shot_notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.shot_notif_capture), shoot)
            .addAction(0, getString(R.string.shot_notif_stop), stop)
            .build()
    }

    private fun notifyState() {
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification())
        } catch (_: Throwable) {
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val TAG = "ShotService"
        private const val NOTIF_ID = 0x10C8

        const val ACTION_START = "com.lecture.notes.SHOT_START"
        const val ACTION_STOP = "com.lecture.notes.SHOT_STOP"
        const val ACTION_CAPTURE = "com.lecture.notes.SHOT_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_REUSE_PROJECTION = "reuse_projection"

        /** 藏起自己之后等一小会儿再抓，给系统留出重新合成的两帧。 */
        private const val HIDE_BEFORE_GRAB_MS = 140L

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val intent = Intent(ctx, ShotService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            androidx.core.content.ContextCompat.startForegroundService(ctx, intent)
        }

        /** 复用 [ShotGate] 里已有的录屏授权（录音在用那个），不重复弹授权框。 */
        fun start(ctx: Context) {
            val intent = Intent(ctx, ShotService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_REUSE_PROJECTION, true)
            androidx.core.content.ContextCompat.startForegroundService(ctx, intent)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ShotService::class.java).setAction(ACTION_STOP))
        }

        /** 让正在跑的悬浮按钮立刻截一张（记录页那颗「课件截图」走的就是这条）。 */
        fun capture(ctx: Context) {
            ctx.startService(Intent(ctx, ShotService::class.java).setAction(ACTION_CAPTURE))
        }
    }
}
