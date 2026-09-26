package com.lecture.notes

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.lecture.notes.core.Recorder
import com.lecture.notes.data.NoteStore
import com.lecture.notes.util.Prefs

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        // 进程是新起的，就说明悬浮截图服务早就没了（录屏授权也不可复用），把开关状态归位
        Prefs.shotFloat = false
        NoteStore.init(this)
        Recorder.init(this)
        Recorder.prepare()
        createChannel()
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
