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
import java.util.ArrayDeque
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
        val message: String = "",
        /** 批量整理时后面还排着几篇（0 就是当前这篇是最后一篇）。 */
        val queued: Int = 0
    ) {
        val running: Boolean get() = phase == Phase.RUNNING
    }

    private const val NOTIF_DONE = 0x10C9
    private const val NOTIF_PROGRESS = 0x10CA

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** 排队等着整理的笔记：批量整理时一篇跑完自动接下一篇。 */
    private val pending = ArrayDeque<Note>()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun isRunning(): Boolean = _state.value.running
    fun isRunning(id: String?): Boolean = id != null && _state.value.running && _state.value.noteId == id
    val runningNoteId: String? get() = if (_state.value.running) _state.value.noteId else null

    /** 开始整理一篇笔记：[enqueue] 的单篇版本，返回是否排上了。 */
    fun start(note: Note): Boolean = enqueue(listOf(note)) > 0

    /**
     * 排队整理一批笔记（首页多选之后「AI 整理」走的就是这里）。
     *
     * 一次只跑一篇 —— 反正模型那边并发也快不了，还会互相抢带宽、更容易失败。排进来的
     * 一篇接一篇自动跑完，进度共用一条通知，用户选完就能退出页面去干别的。
     *
     * 正在整理的那篇、已经在队列里的、没填 Key 的都会被跳过，所以「选了 5 篇实际排了 3 篇」
     * 是正常结果；返回值就是真正排进去的篇数。
     */
    fun enqueue(notes: List<Note>): Int {
        if (Prefs.llmKey.isBlank()) return 0
        val cur = _state.value
        var added = 0
        for (n in notes) {
            if (cur.running && cur.noteId == n.id) continue
            if (pending.any { it.id == n.id }) continue
            pending.addLast(n)
            added++
        }
        if (added == 0) return 0
        if (_state.value.running) {
            _state.value = _state.value.copy(queued = pending.size)
        } else {
            next()
        }
        return added
    }

    /** 从队列里取下一篇开跑。 */
    private fun next() {
        val note = pending.pollFirst()
        if (note == null) {
            _state.value = _state.value.copy(queued = 0)
            return
        }
        run(note)
    }

    private fun run(note: Note) {
        _state.value = State(
            phase = Phase.RUNNING,
            noteId = note.id,
            noteTitle = note.title,
            startedAt = System.currentTimeMillis(),
            label = App.instance.getString(R.string.digest_ai_start),
            queued = pending.size
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
                finished(note.id)
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
                // 用户掐断的是整批：队列一起清掉，别在他走了之后接着跑
                if (cancelled) pending.clear() else finished(note.id)
            } finally {
                cancelProgress()
            }
        }
    }

    /** 一篇跑完：队列里还有就接着跑下一篇，没有就把排队数收回 0。 */
    private fun finished(id: String) {
        val note = pending.pollFirst()
        if (note != null) {
            run(note)
            return
        }
        if (_state.value.noteId == id) _state.value = _state.value.copy(queued = 0)
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
        pending.clear()
        job?.cancel()
        cancelProgress()
    }

    /** 页面把「完成 / 失败」提示消化掉之后调一下，免得下次进页面又弹一遍。 */
    fun consume() {
        if (!_state.value.running && pending.isEmpty()) _state.value = State()
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
        val text = when {
            st.queued > 0 ->
                App.instance.getString(R.string.digest_ai_notif_queue, st.noteTitle, st.queued)

            st.total > 1 ->
                App.instance.getString(R.string.digest_ai_notif_progress_multi, st.noteTitle, st.done, st.total)

            else -> App.instance.getString(R.string.digest_ai_notif_progress, st.noteTitle)
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
