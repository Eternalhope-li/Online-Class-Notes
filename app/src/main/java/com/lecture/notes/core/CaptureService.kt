package com.lecture.notes.core

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.lecture.notes.App
import com.lecture.notes.R
import com.lecture.notes.ui.LiveActivity
import com.lecture.notes.util.Formats
import com.lecture.notes.util.Prefs

/**
 * 让记录在后台活着：前台服务 + 部分唤醒锁 + 通知栏快捷操作。
 * 识别本身在 [Recorder] 里，这里只负责“别被系统杀掉”和“给用户一个开关”。
 */
class CaptureService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false
    private var stopping = false
    private var pendingTitle: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            val st = Recorder.state.value
            if (!st.active) return
            Recorder.tick()
            notifyState()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                pendingTitle = intent.getStringExtra(EXTRA_TITLE)
                stopEverything()
                return START_NOT_STICKY
            }

            ACTION_TOGGLE -> {
                Recorder.togglePause()
                notifyState()
                return START_STICKY
            }

            ACTION_START -> {
                if (!started) begin(intent)
                return START_STICKY
            }
        }
        if (!started) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        if (Recorder.state.value.active) {
            Thread({ Recorder.stop() }, "session-destroy").start()
        }
        releaseWakeLock()
        releaseProjection()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 启动流程

    private fun begin(intent: Intent) {
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: Prefs.SOURCE_MIC
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        started = true

        val granted = resultData != null && resultCode == Activity.RESULT_OK
        // 悬浮截图已经拿过授权就直接共用那一个：再申请一次会把正在用的授权掐掉，
        // 反过来也一样（先开录音再开截图会把内录掐断）。
        val shared = if (granted) null else ShotGate.current()

        // Android 14 的硬性顺序：先在 Activity 里拿到用户授权，再带着 mediaProjection 类型
        // 启动前台服务，最后才能调用 getMediaProjection()。顺序反了会抛 SecurityException。
        val wantsProjection = source == Prefs.SOURCE_INTERNAL && (granted || shared != null)
        startForegroundCompat(withProjection = wantsProjection)

        if (wantsProjection) {
            projection = if (shared != null) {
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
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    // 用户在系统界面里结束了录屏
                    handler.post { stopEverything() }
                }
            }, handler)
            // 登记进共享池，悬浮截图可以直接拿去抓帧
            projection?.let { ShotGate.publish(it, ShotGate.OWNER_RECORD) }
        }

        acquireWakeLock()

        Thread({
            val ok = Recorder.startSession(source, projection)
            if (!ok) {
                handler.post { stopEverything() }
            }
        }, "session-start").start()

        handler.postDelayed(ticker, 1000)
    }

    private fun startForegroundCompat(withProjection: Boolean) {
        val notification = buildNotification(Recorder.state.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (withProjection) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopEverything() {
        if (stopping) return
        stopping = true
        handler.removeCallbacks(ticker)
        Thread({
            val note = try {
                // 名字交给 Recorder 在最终状态发出去之前定好，界面跳转时才不会看到旧名字
                Recorder.stop(pendingTitle)
            } catch (t: Throwable) {
                Log.w(TAG, "stop failed", t)
                null
            }
            handler.post {
                releaseWakeLock()
                releaseProjection()
                started = false
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                lastStoppedNoteId = note?.id
            }
        }, "session-stop").start()
    }

    private fun notifyState() {
        val st = Recorder.state.value
        if (!st.active) return
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(st))
        } catch (_: Throwable) {
        }
    }

    private fun buildNotification(st: Recorder.State): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, LiveActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleLabel = if (st.phase == Recorder.Phase.PAUSED) {
            getString(R.string.notif_resume)
        } else {
            getString(R.string.notif_pause)
        }
        val toggle = PendingIntent.getService(
            this, 1,
            Intent(this, CaptureService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val lines = getString(R.string.live_lines, st.lineCount, st.charCount)
        val text = Formats.hms(st.elapsedMs) + " · " + lines
        return NotificationCompat.Builder(this, App.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(st.title.ifBlank { getString(R.string.notif_title) })
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, toggleLabel, toggle)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LectureNotes:recording").apply {
            setReferenceCounted(false)
            try {
                acquire(6 * 60 * 60 * 1000L)
            } catch (_: Throwable) {
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    private fun releaseProjection() {
        // 只退掉自己的登记；悬浮截图还在用的话不能停，否则会把人家掐断
        ShotGate.release(ShotGate.OWNER_RECORD)
        projection = null
    }

    companion object {
        private const val TAG = "CaptureService"
        private const val NOTIF_ID = 0x10C7

        const val ACTION_START = "com.lecture.notes.START"
        const val ACTION_STOP = "com.lecture.notes.STOP"
        const val ACTION_TOGGLE = "com.lecture.notes.TOGGLE"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TITLE = "title"

        /** 停止之后是谁的笔记，界面上用来跳转。 */
        @Volatile
        var lastStoppedNoteId: String? = null

        fun start(ctx: Context, source: String, resultCode: Int = 0, data: Intent? = null) {
            val intent = Intent(ctx, CaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SOURCE, source)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            androidx.core.content.ContextCompat.startForegroundService(ctx, intent)
        }

        fun stop(ctx: Context, title: String? = null) {
            val intent = Intent(ctx, CaptureService::class.java).setAction(ACTION_STOP)
            if (!title.isNullOrBlank()) intent.putExtra(EXTRA_TITLE, title)
            ctx.startService(intent)
        }
    }
}
