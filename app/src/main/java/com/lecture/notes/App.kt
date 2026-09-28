package com.lecture.notes

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import com.lecture.notes.core.DigestJob
import com.lecture.notes.core.Recorder
import com.lecture.notes.core.ShotService
import com.lecture.notes.data.NoteStore
import com.lecture.notes.util.Prefs

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        // 进程是新起的，就说明悬浮截图服务早就没了（录屏授权也不可复用），把开关状态归位
        Prefs.shotFloat = false
        // 整理任务的队列同样在内存里：进程是新的就说明没有任务在跑，通知栏那条
        // 「AI 正在整理」只可能是上一次被系统杀进程时留下的 —— 撤掉，别让它一直转
        DigestJob.init()
        NoteStore.init(this)
        // 回收站里放了七天以上的笔记，进程起来时清一次（别等用户想起来再删）
        Thread({ NoteStore.purgeTrash() }, "trash-purge").start()
        Recorder.init(this)
        Recorder.prepare()
        createChannel()
        // 悬浮圆钮只在「别的 App」里有用：自家页面一在前台就收起来，免得压住返回键和计时。
        // 用 started 计数而不是 resumed：A 切 B 时 A 先 pause、后 stop，不会中间闪一下。
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                started++
                ShotService.setHidden(true)
            }

            override fun onActivityStopped(activity: Activity) {
                started--
                if (started <= 0) {
                    started = 0
                    ShotService.setHidden(false)
                }
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_RECORDING,
                getString(R.string.channel_recording),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = getString(R.string.channel_recording_desc)
            channel.setShowBadge(false)
            channel.enableVibration(false)
            nm.createNotificationChannel(channel)

            // 后台整理：跑的时候安静地待着，整理好这一条要能弹出来让人知道
            val digest = NotificationChannel(
                CHANNEL_DIGEST,
                getString(R.string.channel_digest),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            digest.description = getString(R.string.channel_digest_desc)
            digest.setShowBadge(true)
            nm.createNotificationChannel(digest)
        }
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_DIGEST = "digest"

        lateinit var instance: App
            private set
    }
}
