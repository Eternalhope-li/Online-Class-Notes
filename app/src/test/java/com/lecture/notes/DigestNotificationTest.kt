package com.lecture.notes

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.lecture.notes.core.DigestJob
import com.lecture.notes.core.CaptureService
import com.lecture.notes.core.ShotService
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
            .getNotification(DigestJob.NOTIF_DIGEST)

    /** 假装上一次整理留下的那条通知还在通知栏里。 */
    private fun leaveStaleProgress() {
        val n = NotificationCompat.Builder(app, App.CHANNEL_DIGEST)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(app.getString(R.string.digest_ai_notif_title))
            .setOngoing(true)
            .build()
        NotificationManagerCompat.from(app).notify(DigestJob.NOTIF_DIGEST, n)
    }

    /** 「还剩几篇」优先于分段进度：排队时说还剩多少，最有用。 */
    @Test
    fun progressLineCountsTheQueueFirst() {
        assertEquals("《第一讲》· 还剩 2 篇", DigestJob.progressLine("第一讲", 2, 3, 5))
        assertEquals("《第一讲》· 第 3/5 段", DigestJob.progressLine("第一讲", 0, 3, 5))
        assertEquals("《第一讲》· 正在通读", DigestJob.progressLine("第一讲", 0, 1, 1))
    }

    /** 整理好一篇占一个坑：同一篇永远落回同一个坑，且绝不撞上那条进度通知。 */
    @Test
    fun eachNoteOwnsOneResultSlot() {
        val a = DigestJob.noteNotifId("note-a")
        assertEquals(a, DigestJob.noteNotifId("note-a"))

        val b = DigestJob.noteNotifId("note-b")
        assertNotEquals(a, b)

        // 一篇一条地发，最怕两件事：把常驻的进度通知顶掉，或者跑到别的通知身上去
        assertNotEquals(DigestJob.NOTIF_DIGEST, a)
        assertNotEquals(CaptureService.NOTIF_ID, a)
        assertNotEquals(ShotService.NOTIF_ID, a)
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
