package com.lecture.notes

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.lecture.notes.core.DigestJob
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 「AI 正在整理笔记」那条常驻通知的生命周期。
 *
 * 真机上踩过：整理跑到一半，App 被系统在后台杀掉（或者用户从最近任务里划掉），
 * 常驻的进度通知不会自己消失 —— 用户下次打开笔记会看到一条永远转不完的
 * 「AI 正在整理」，而那条笔记其实早就整理好了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
class DigestNotificationTest {

    private val app: Context = ApplicationProvider.getApplicationContext()

    private fun progressNotification() =
        shadowOf(app.getSystemService(NotificationManager::class.java))
            .getNotification(DigestJob.NOTIF_PROGRESS)

    /** 假装上一次整理留下的那条通知还在通知栏里。 */
    private fun leaveStaleProgress() {
        val n = NotificationCompat.Builder(app, App.CHANNEL_DIGEST)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(app.getString(R.string.digest_ai_notif_title))
            .setOngoing(true)
            .build()
        NotificationManagerCompat.from(app).notify(DigestJob.NOTIF_PROGRESS, n)
    }

    @Test
    fun staleProgressNotificationIsDroppedWhenProcessStarts() {
        leaveStaleProgress()
        assertNotNull(progressNotification())

        // 进程重新起来（App.onCreate 走一遍）：队列本来就是空的，这条只可能是上一次遗留的
        (app as App).onCreate()

        assertNull(progressNotification())
        assertNull(DigestJob.runningNoteId)
    }

    @Test
    fun cancelAlsoClearsLeftoverNotification() {
        leaveStaleProgress()

        // 通知上那颗「取消生成」：没有任务在跑时也得把这条假通知撤掉
        DigestJob.cancel()

        assertNull(progressNotification())
    }
}
