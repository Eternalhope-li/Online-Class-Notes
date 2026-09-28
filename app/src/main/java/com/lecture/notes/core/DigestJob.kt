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
import com.lecture.notes.ui.MainActivity
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

    /**
     * 进度通知占的位置：现在在整理哪一篇、后面还排着几篇。
     *
     * 它只在这趟整理跑着的时候存在，整批一跑完就撤掉（它是常驻的，绝不能在通知栏里
     * 留到明天）。整理好的结果由每篇自己的那条通知去报，见 [noteNotifId]。
     */
    internal const val NOTIF_DIGEST = 0x10CA

    /**
     * 整理好一篇，就给它单独占一条结果通知，坑位按笔记 id 算。
     *
     * 同一篇重跑会顶掉自己上一次那条，不会越堆越多；一批多选整理完，通知栏里就是
     * 一篇一条，点哪条开哪篇、划掉哪条就哪条不再提醒。
     */
    internal fun noteNotifId(noteId: String): Int = NOTIF_NOTE_BASE + (noteId.hashCode() and 0xFF)
    private const val NOTIF_NOTE_BASE = 0x10D0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** 这一趟整理的编号：收尾时用它判断通知栏那条进度还是不是自己的。 */
    private var runSeq = 0L
    private var currentRun = 0L

    /** 这一批（一篇，或多选的好几篇）的账：进度通知上的「第 i/N 篇」全靠它。 */
    private var batchTotal = 0
    private var batchDone = 0
    private var batchFailed = 0
    private var lastNoteId: String? = null

    /** 排队等着整理的笔记：批量整理时一篇跑完自动接下一篇。 */
    private val pending = ArrayDeque<Note>()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun isRunning(): Boolean = _state.value.running
    fun isRunning(id: String?): Boolean = id != null && _state.value.running && _state.value.noteId == id
    val runningNoteId: String? get() = if (_state.value.running) _state.value.noteId else null

    /**
     * 进程刚起来时调一下（`App.onCreate`）。
     *
     * 队列在内存里：进程是新的，就说明上一趟整理早就没了 —— 多半是 App 在后台被系统杀掉、
     * 或者用户从最近任务里划掉了。可进度通知是**常驻**的，进程死了它也不会自己消失，
     * 于是用户会看到一条永远转不完的「AI 正在整理」，而那条笔记其实早就整理好了。
     * 所以进程一起来就把它撤掉（顺便把状态归零，反正队列本来就是空的）。
     */
    fun init() {
        _state.value = State()
        pending.clear()
        currentRun = 0L
        clearNotif()
    }

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
            // 正在跑：新选的并进同一批，通知上的「还剩 N 篇」跟着涨
            batchTotal += added
            _state.value = _state.value.copy(queued = pending.size)
        } else {
            // 全新的一批：账从头开始记
            batchTotal = added
            batchDone = 0
            batchFailed = 0
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

    /** 开跑一篇。上一篇的结果通知已经单独发过了，这里只管把那条进度顶上去。 */
    private fun run(note: Note) {
        val myRun = ++runSeq
        currentRun = myRun
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
                val draft = LlmDigest.digest(note, onProgress = { done, total, label ->
                    val cur = _state.value
                    if (cur.running) _state.value = cur.copy(done = done, total = total, label = label)
                })
                val tag = App.instance.getString(R.string.digest_tag_ai)
                // 名字还是 App 自动起的（「网课笔记 09-27 17:22」这种），就顺手换成这节课的题目。
                // 两种情况不动它：用户自己改过名字（autoTitle = false）；或者这个名字本来就是
                // AI 起的（aiTitle = true）—— 重跑一次不该让笔记名再变一次，翻笔记时对不上。
                if (draft.topic.isNotEmpty() && note.autoTitle && !note.aiTitle) {
                    NoteStore.rename(note.id, draft.topic, auto = true, ai = true)
                    note.title = draft.topic
                    note.aiTitle = true
                    _state.value = _state.value.copy(noteTitle = draft.topic)
                }
                NoteStore.saveDigest(note.id, DigestDoc.ai(note, tag, draft.body))
                _state.value = _state.value.copy(phase = Phase.DONE, label = "")
                finished(note.id, note.title, ok = true)
            } catch (t: Throwable) {
                val cancelled = t is CancellationException || t.message == LlmDigest.CANCELLED
                val msg = if (cancelled) {
                    App.instance.getString(R.string.digest_ai_cancelled)
                } else {
                    App.instance.getString(R.string.digest_failed, t.message ?: t.javaClass.simpleName)
                }
                val prev = _state.value
                _state.value = prev.copy(phase = Phase.FAILED, label = "", message = msg)
                // 用户掐断的是整批：队列一起清掉，别在他走了之后接着跑
                // （取消那条路不发结果通知 —— cancel() 已经给了一句「已取消」回执）
                if (cancelled) pending.clear() else finished(note.id, note.title, ok = false, message = msg)
            } finally {
                // 队列里接着跑下一篇时，这条通知已经属于下一篇了，不能把人家撤掉
                if (currentRun == myRun) clearNotif()
            }
        }
    }

    /**
     * 一篇收工（成功、失败都走这里）。
     *
     * 每整理好一篇当场发它自己那条通知（顺便响一声），然后接着跑队列里的下一篇；
     * 整批跑完，把那条常驻的进度通知撤掉 —— 通知栏里只留下每篇自己的结果。
     */
    private fun finished(id: String, title: String, ok: Boolean, message: String = "") {
        if (ok) batchDone++ else batchFailed++
        lastNoteId = id
        notifyNoteDone(id, title, ok, message)
        val nextNote = pending.pollFirst()
        if (nextNote != null) {
            _state.value = _state.value.copy(queued = pending.size)
            run(nextNote)
            return
        }
        if (_state.value.noteId == id) _state.value = _state.value.copy(queued = 0)
        clearNotif()
        resetBatch()
    }

    /**
     * 用户主动掐断：断开正在等的那趟请求，再取消协程。
     *
     * 也兼顾「压根没有任务在跑」的场面 —— 通知上那颗「取消生成」同时也是「让这条通知消失」：
     * 进程被系统在后台杀掉之后会剩一条假的常驻通知，点它一下就收拾干净。
     */
    fun cancel() {
        val st = _state.value
        LlmDigest.cancelActive()
        pending.clear()
        job?.cancel()
        // 还在跑的那一趟收尾时别再动通知（下面已经撤掉了）
        currentRun = 0L
        if (st.running) {
            _state.value = st.copy(
                phase = Phase.FAILED,
                label = "",
                message = App.instance.getString(R.string.digest_ai_cancelled)
            )
            // 通知栏那条换成一句回执：这次不会再通知你了
            notifyCancelled()
        } else {
            // 没有任务在跑：那条只可能是上次留下的假通知，撤掉就完事
            clearNotif()
        }
    }

    /** 页面把「完成 / 失败」提示消化掉之后调一下，免得下次进页面又弹一遍。 */
    fun consume() {
        if (_state.value.running || pending.isNotEmpty()) return
        _state.value = State()
    }

    /**
     * 用户已经翻到这篇整理稿了，那条「整理好了」的通知就没必要再占着通知栏。
     *
     * 正在整理的那一篇不动 —— 那条是进度，用户还要看着它（页面上也有「取消生成」）。
     */
    fun clearNoteNotif(noteId: String) {
        if (isRunning(noteId)) return
        try {
            NotificationManagerCompat.from(App.instance).cancel(noteNotifId(noteId))
        } catch (_: Throwable) {
        }
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
        val b = NotificationCompat.Builder(App.instance, App.CHANNEL_DIGEST)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(App.instance.getString(R.string.digest_ai_notif_title))
            .setContentText(progressLine(st.noteTitle, pending.size, st.done, st.total))
            .setOngoing(true)
            // 安静更新：分段进度刷得很勤，每段都响一声全是噪音。「整理好了」那一下
            // 由每篇自己的结果通知去响，进度这条只管报告「还在跑、还剩几篇」。
            .setSilent(true)
            .addAction(R.drawable.ic_close, App.instance.getString(R.string.digest_ai_cancel), cancelIntent())
            .setContentIntent(openNote(id))
            // 兜底：万一这趟整理整个卡死（进程被系统冻住、网络吊住），这条常驻通知
            // 也不该在通知栏里转到天荒地老。每次刷新进度都会重置这个计时。
            .setTimeoutAfter(20 * 60 * 1000L)
        // 好几篇的时候给一条能看出走了多少的进度条，右下角标「第 2/5 篇」；
        // 单篇没有总量可比，还是转圈
        if (batchTotal > 1) {
            val done = (batchDone + batchFailed).coerceAtMost(batchTotal)
            b.setProgress(batchTotal, done, false)
            b.setSubText(
                App.instance.getString(R.string.digest_ai_notif_batch_count, (done + 1).coerceAtMost(batchTotal), batchTotal)
            )
        } else {
            b.setProgress(0, 0, true)
        }
        post(NOTIF_DIGEST, b.build())
    }

    /** 进度通知第二行：「还剩几篇」优先，其次是分段进度，最后是通读中。 */
    internal fun progressLine(title: String, remaining: Int, done: Int, total: Int): String = when {
        remaining > 0 -> App.instance.getString(R.string.digest_ai_notif_batch_more, title, remaining)
        total > 1 -> App.instance.getString(R.string.digest_ai_notif_progress_multi, title, done, total)
        else -> App.instance.getString(R.string.digest_ai_notif_progress, title)
    }

    /** 通知上那颗「取消生成」：走一条广播，进程没活着也能把这条假通知收拾掉。 */
    private fun cancelIntent(): PendingIntent {
        val i = Intent(App.instance, DigestCancelReceiver::class.java)
            .setAction(DigestCancelReceiver.ACTION)
        return PendingIntent.getBroadcast(
            App.instance, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 一篇整理好了（或者没成）：给它单独发一条通知。
     *
     * 用户要的就是「整理好一篇就通知一篇」：一批多选整理下来，通知栏里一篇一条，
     * 各点各的、各划各的，不会互相顶掉。失败了把模型那句人话理由摊开写清楚。
     */
    private fun notifyNoteDone(noteId: String, title: String, ok: Boolean, message: String) {
        val b = NotificationCompat.Builder(App.instance, App.CHANNEL_DIGEST)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(
                App.instance.getString(
                    if (ok) R.string.digest_ai_notif_done else R.string.digest_ai_notif_failed
                )
            )
            .setContentText(App.instance.getString(R.string.digest_ai_notif_note, title))
            .setAutoCancel(true)
            .setContentIntent(openNote(noteId))
        if (!ok && message.isNotBlank()) {
            b.setStyle(NotificationCompat.BigTextStyle().bigText(message))
        }
        post(noteNotifId(noteId), b.build())
    }

    /** 用户按了「取消生成」之后的那句回执：让他知道这次不会再通知了。 */
    private fun notifyCancelled() {
        val title = _state.value.noteTitle
        val b = NotificationCompat.Builder(App.instance, App.CHANNEL_DIGEST)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(App.instance.getString(R.string.digest_ai_notif_cancelled_title))
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(lastNoteId?.let { openNote(it) } ?: openList())
        if (title.isNotEmpty()) {
            b.setContentText(App.instance.getString(R.string.digest_ai_notif_note, title))
        }
        post(NOTIF_DIGEST, b.build())
        resetBatch()
    }

    private fun resetBatch() {
        batchTotal = 0
        batchDone = 0
        batchFailed = 0
        lastNoteId = null
    }

    /** 打开笔记库：取消的回执没有具体指向哪一篇时，点它就回列表。 */
    private fun openList(): PendingIntent {
        val i = Intent(App.instance, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            App.instance, 0x10CB, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun clearNotif() {
        try {
            NotificationManagerCompat.from(App.instance).cancel(NOTIF_DIGEST)
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
