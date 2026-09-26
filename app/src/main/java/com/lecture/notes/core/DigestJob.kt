package com.lecture.notes.core

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lecture.notes.App
import com.lecture.notes.R
import com.lecture.notes.data.Note
import com.lecture.notes.data.NoteStore
import com.lecture.notes.net.LlmDigest
import com.lecture.notes.ui.DetailActivity
import com.lecture.notes.ui.DigestActivity
import com.lecture.notes.util.DigestDoc
import com.lecture.notes.util.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 后台的「AI 体系化整理」。
 *
 * 一整堂课交给大模型重排，短则二三十秒、长则一两分钟。让用户对着进度条干等是最差的交互，
 * 所以这个任务挂在**进程**上而不是某个页面上：点完可以立刻退出整理稿页去干别的，
 * 整理好发一条通知，点通知直接看成品。页面上只留一条干净的进度，
 * 不把模型一半一半生成的文字刷给用户看。
 */
object DigestJob {

    enum class Phase { IDLE, RUNNING, DONE, FAILED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val noteId: String? = null,
        val noteTitle: String = "",
        val done: Int = 0,
        val total: Int = 0,
        val startedAt: Long = 0L,
        val label: String = "",
        val message: String = ""
    ) {
        val running: Boolean get() = phase == Phase.RUNNING
    }

    private const val NOTIF_DONE = 0x10C9
    private const val NOTIF_PROGRESS = 0x10CA

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun isRunning(): Boolean = _state.value.running
    fun isRunning(id: String?): Boolean = id != null && _state.value.running && _state.value.noteId == id
    val runningNoteId: String? get() = if (_state.value.running) _state.value.noteId else null

    /** 开始整理一篇笔记。已经有任务在跑、或者没填 Key，就返回 false。 */
    fun start(note: Note): Boolean {
        if (isRunning()) return false
        if (Prefs.llmKey.isBlank()) return false
        _state.value = State(
            phase = Phase.RUNNING,
            noteId = note.id,
            noteTitle = note.title,
            startedAt = System.currentTimeMillis(),
            label = App.instance.getString(R.string.digest_ai_start)
        )
        notifyProgress()
        job = scope.launch {
            try {
                val body = LlmDigest.digest(note, onProgress = { done, total, label ->
                    val cur = _state.value
                    if (cur.running) _state.value = cur.copy(done = done, total = total, label = label)
                })
                val tag = App.instance.getString(R.string.digest_tag_ai)
                NoteStore.saveDigest(note.id, DigestDoc.ai(note, tag, body))
                _state.value = _state.value.copy(phase = Phase.DONE, label = "")
                notifyDone(note)
            } catch (t: Throwable) {
                val cancelled = t is CancellationException || t.message == LlmDigest.CANCELLED
                val msg = if (cancelled) {
                    App.instance.getString(R.string.digest_ai_cancelled)
                } else {
                    App.instance.getString(R.string.digest_failed, t.message ?: t.javaClass.simpleName)
                }
                val prev = _state.value
                _state.value = prev.copy(phase = Phase.FAILED, label = "", message = msg)
                // 用户自己取消的就不用通知了，页面上给个提示即可
                if (!cancelled) notifyFailed(note, msg)
            } finally {
                cancelProgress()
            }
        }
        return true
    }

    /** 用户主动掐断：断开正在等的那趟请求，再取消协程。 */
    fun cancel() {
        val st = _state.value
        if (!st.running) return
        _state.value = st.copy(
            phase = Phase.FAILED,
            label = "",
            message = App.instance.getString(R.string.digest_ai_cancelled)
        )
        LlmDigest.cancelActive()
        job?.cancel()
        cancelProgress()
    }

    /** 页面把「完成 / 失败」提示消化掉之后调一下，免得下次进页面又弹一遍。 */
    fun consume() {
        if (!_state.value.running) _state.value = State()
    }

    // ------------------------------------------------------------ 通知

    private fun openNote(noteId: String): PendingIntent {
        val i = Intent(App.instance, DigestActivity::class.java)
            .putExtra(DetailActivity.EXTRA_ID, noteId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            App.instance, noteId.hashCode() and 0xFFFF, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notifyProgress() {
        val st = _state.value
        val id = st.noteId ?: return
        val text = if (st.total > 1) {
            App.instance.getString(R.string.digest_ai_notif_progress_multi, st.noteTitle, st.done, st.total)
        } else {
            App.instance.getString(R.string.digest_ai_notif_progress, st.noteTitle)
        }
        val n = NotificationCompat.Builder(App.instance, App.CHANNEL_DIGEST)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(App.instance.getString(R.string.digest_ai_notif_title))
            .setContentText(text)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openNote(id))
            .build()
        post(NOTIF_PROGRESS, n)
    }

    private fun notifyDone(note: Note) {
        val n = NotificationCompat.Builder(App.instance, App.CHANNEL_DIGEST)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(App.instance.getString(R.string.digest_ai_notif_done))
            .setContentText(note.title)
            .setAutoCancel(true)
            .setContentIntent(openNote(note.id))
            .build()
        post(NOTIF_DONE, n)
    }

    private fun notifyFailed(note: Note, msg: String) {
        val n = NotificationCompat.Builder(App.instance, App.CHANNEL_DIGEST)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(App.instance.getString(R.string.digest_ai_notif_failed))
            .setContentText(msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .setAutoCancel(true)
            .setContentIntent(openNote(note.id))
            .build()
        post(NOTIF_DONE, n)
    }

    private fun cancelProgress() {
        try {
            NotificationManagerCompat.from(App.instance).cancel(NOTIF_PROGRESS)
        } catch (_: Throwable) {
        }
    }

    private fun post(id: Int, n: android.app.Notification) {
        try {
            NotificationManagerCompat.from(App.instance).notify(id, n)
        } catch (_: Throwable) {
            // 没给通知权限也不影响整理本身，安静跳过
        }
    }
}
