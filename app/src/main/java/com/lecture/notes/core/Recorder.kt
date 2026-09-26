package com.lecture.notes.core

import android.content.Context
import android.media.projection.MediaProjection
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.lecture.notes.R
import com.lecture.notes.data.Entry
import com.lecture.notes.data.Note
import com.lecture.notes.data.NoteStore
import com.lecture.notes.util.Prefs
import com.lecture.notes.util.TextPolish
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 记录会话的唯一状态源。
 *
 * 音频线程：AudioRecord → VAD（只在有人说话时才产出片段，静音时几乎不耗电）
 * 识别线程：单线程串行解码，保证时间戳顺序正确、不跟手机抢 CPU
 * 结果：每句立刻追加落盘，随时被杀进程都不会丢内容
 */
object Recorder {

    enum class Phase { IDLE, PREPARING, READY, RECORDING, PAUSED, ERROR }

    data class Line(val atMs: Long, val text: String, val star: Boolean, val manual: Boolean)

    data class State(
        val phase: Phase = Phase.IDLE,
        val noteId: String? = null,
        val title: String = "",
        val elapsedMs: Long = 0L,
        val lineCount: Int = 0,
        val charCount: Int = 0,
        val status: String = "",
        val level: Float = 0f,
        val source: String = Prefs.SOURCE_MIC,
        val skipped: Int = 0,
        val error: String? = null,
        val lines: List<Line> = emptyList()
    ) {
        val active: Boolean get() = phase == Phase.RECORDING || phase == Phase.PAUSED
    }

    private const val TAG = "Recorder"
    private const val QUEUE_SIZE = 8

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private lateinit var app: Context
    private var engine: AsrEngine? = null
    private var preparing = false
    private var prepareError: String? = null

    private var input: AudioInput? = null
    private var audioThread: Thread? = null
    private var workerThread: Thread? = null
    private val queue = ArrayBlockingQueue<Segment>(QUEUE_SIZE)

    @Volatile
    private var running = false

    @Volatile
    private var paused = false
    private var startedAt = 0L
    private var pausedAt = 0L
    private var pausedTotal = 0L
    private var fedSamples = 0L
    private var lastStartMs = 0L
    private var skipped = 0

    private val lineLock = Any()
    private var note: Note? = null
    private val lines = ArrayList<Line>()

    private class Segment(val startMs: Long, val samples: FloatArray)

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    private fun s(id: Int, vararg args: Any): String =
        if (args.isEmpty()) app.getString(id) else app.getString(id, *args)

    val isReady: Boolean get() = engine != null
    val isPreparing: Boolean get() = preparing

    // ---------------------------------------------------------------- 模型

    fun prepare() {
        if (engine != null) return
        synchronized(this) {
            if (preparing || engine != null) return
            preparing = true
            prepareError = null
        }
        _state.update { it.copy(phase = Phase.PREPARING, status = s(R.string.live_preparing), error = null) }
        Thread({
            try {
                val e = AsrEngine.create(app, Prefs.numThreads)
                synchronized(this) {
                    engine = e
                    preparing = false
                }
                _state.update { st -> if (st.active) st else st.copy(phase = Phase.READY, status = s(R.string.live_ready)) }
            } catch (t: Throwable) {
                Log.e(TAG, "prepare failed", t)
                val msg = t.message ?: t.javaClass.simpleName
                synchronized(this) {
                    preparing = false
                    prepareError = msg
                }
                _state.update { it.copy(phase = Phase.ERROR, error = msg, status = s(R.string.err_model, msg)) }
            }
        }, "asr-init").start()
    }

    /** 设置里改了线程数之后重建引擎（只在空闲时允许）。 */
    fun reloadEngine() {
        if (running) return
        val old = synchronized(this) {
            val o = engine
            engine = null
            o
        }
        Thread({
            old?.release()
            prepare()
        }, "asr-reload").start()
    }

    // ---------------------------------------------------------------- 会话

    fun startSession(source: String, projection: MediaProjection?): Boolean {
        if (running) return false
        prepare()
        var waited = 0
        while (engine == null && prepareError == null && waited < 30000) {
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                break
            }
            waited += 100
        }
        val eng = engine ?: return false

        val n = NoteStore.create(source)
        synchronized(lineLock) {
            lines.clear()
            note = n
        }
        skipped = 0
        fedSamples = 0
        lastStartMs = 0
        pausedTotal = 0
        pausedAt = 0
        paused = false
        queue.clear()
        eng.reset()

        val inp = try {
            if (source == Prefs.SOURCE_INTERNAL && projection != null) AudioInput.playback(projection)
            else AudioInput.microphone()
        } catch (t: Throwable) {
            Log.w(TAG, "open audio input failed", t)
            try {
                AudioInput.microphone()
            } catch (t2: Throwable) {
                null
            }
        } ?: return false

        input = inp
        try {
            inp.start()
        } catch (t: Throwable) {
            Log.e(TAG, "start recording failed", t)
            inp.release()
            input = null
            return false
        }

        running = true
        startedAt = SystemClock.elapsedRealtime()
        workerThread = Thread({ workerLoop(eng) }, "asr-worker").also { it.start() }
        audioThread = Thread({ audioLoop(eng, inp) }, "audio-capture").also { it.start() }

        _state.update {
            State(
                phase = Phase.RECORDING,
                noteId = n.id,
                title = n.title,
                source = source,
                status = s(R.string.live_listening)
            )
        }
        return true
    }

    fun togglePause() {
        if (paused) resume() else pause()
    }

    fun pause() {
        if (!running || paused) return
        paused = true
        pausedAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(phase = Phase.PAUSED, status = s(R.string.live_paused), level = 0f) }
    }

    fun resume() {
        if (!running || !paused) return
        pausedTotal += SystemClock.elapsedRealtime() - pausedAt
        paused = false
        _state.update { it.copy(phase = Phase.RECORDING, status = s(R.string.live_listening)) }
    }

    /** 手动打一个重点标记，方便回看时定位。 */
    fun markStar() {
        if (!running) return
        appendLine(elapsedMs(), s(R.string.star_marker), true, true)
    }

    /** 停止并保存。[title] 是界面上填的名字；空着就按这次记录的内容自动取一个。 */
    fun stop(title: String? = null): Note? {
        if (!running && input == null) return null
        running = false

        try {
            input?.stop()
        } catch (_: Throwable) {
        }
        audioThread?.let { t -> try { t.join(2000) } catch (_: InterruptedException) {} }
        audioThread = null

        // 把 VAD 缓冲区里最后半句吐出来
        val eng = engine
        if (eng != null) {
            try {
                eng.flush()
                while (eng.hasSegment()) {
                    val seg = eng.nextSegment()
                    offer(Segment(segmentStartMs(seg), seg.samples))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "flush failed", t)
            }
        }

        workerThread?.let { t -> try { t.join(30000) } catch (_: InterruptedException) {} }
        workerThread = null

        try {
            input?.release()
        } catch (_: Throwable) {
        }
        input = null
        queue.clear()

        val duration = elapsedMs()
        val finished = synchronized(lineLock) {
            val n = note
            if (n != null) {
                n.durationMs = duration
                n.updatedAt = System.currentTimeMillis()
                // 名字必须在最终状态发出去之前定下来：界面一收到 inactive 就跳去详情页，
                // 那之后再改 meta 就晚了，详情页会先显示旧名字。
                val wanted = title?.trim().orEmpty()
                n.title = if (wanted.isNotEmpty()) wanted else NoteStore.suggestTitle(n.id)
                try {
                    NoteStore.saveMeta(n)
                } catch (_: Throwable) {
                }
            }
            note = null
            n
        }

        startedAt = 0L
        paused = false
        _state.update {
            State(
                phase = if (engine != null) Phase.READY else Phase.IDLE,
                status = if (engine != null) s(R.string.live_ready) else "",
                noteId = finished?.id,
                title = finished?.title ?: ""
            )
        }
        return finished
    }

    fun tick() {
        if (!running) return
        _state.update { it.copy(elapsedMs = elapsedMs(), skipped = skipped) }
    }

    fun elapsedMs(): Long {
        if (startedAt == 0L) return 0L
        val now = SystemClock.elapsedRealtime()
        val extra = if (paused) now - pausedAt else 0L
        return (now - startedAt - pausedTotal - extra).coerceAtLeast(0L)
    }

    // ---------------------------------------------------------------- 内部循环

    private fun audioLoop(eng: AsrEngine, inp: AudioInput) {
        val buf = inp.buf
        var tick = 0
        while (running) {
            val n = inp.read()
            if (n <= 0) continue
            if (!paused) {
                eng.accept(buf.copyOf(n))
                fedSamples += n
                while (eng.hasSegment()) {
                    val seg = eng.nextSegment()
                    offer(Segment(segmentStartMs(seg), seg.samples))
                }
            }
            tick++
            if (tick >= 5) {
                tick = 0
                if (!paused) {
                    val lv = inp.lastLevel
                    _state.update { it.copy(level = lv.coerceIn(0f, 1f)) }
                }
            }
        }
    }

    private fun workerLoop(eng: AsrEngine) {
        while (running || queue.isNotEmpty()) {
            val seg = try {
                queue.poll(150, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            }
            if (seg == null) continue
            val raw = try {
                eng.decode(seg.samples)
            } catch (t: Throwable) {
                Log.w(TAG, "decode failed", t)
                ""
            }
            val text = TextPolish.dedup(TextPolish.polish(raw))
            if (text.isEmpty() || TextPolish.isNoise(text)) continue
            appendLine(seg.startMs, text, false, false)
        }
    }

    private fun segmentStartMs(seg: SpeechSegment): Long {
        var ms = seg.start.toLong() * 1000L / AsrEngine.SAMPLE_RATE
        if (ms < lastStartMs) ms = lastStartMs
        val fed = fedSamples * 1000L / AsrEngine.SAMPLE_RATE
        if (ms > fed) ms = fed
        lastStartMs = ms
        return ms
    }

    /** 队列满了就丢最旧的，宁可漏一句也不让延迟越滚越大。 */
    private fun offer(seg: Segment) {
        if (!queue.offer(seg)) {
            queue.poll()
            queue.offer(seg)
            skipped++
        }
    }

    private fun appendLine(atMs: Long, text: String, star: Boolean, manual: Boolean) {
        val isStar = star || (!manual && Prefs.autoHighlight && TextPolish.isImportant(text))
        val line = Line(atMs, text, isStar, manual)
        val snapshot: List<Line>
        synchronized(lineLock) {
            val prev = lines.lastOrNull()
            if (prev != null) {
                // 识别器偶尔会重复上一句 / 复读，去掉
                if (prev.text == text) return
                if (text.length >= 4 && prev.text.endsWith(text)) return
                if (prev.text.length >= 4 && text.startsWith(prev.text) && text.length - prev.text.length < 3) return
            }
            lines.add(line)
            snapshot = ArrayList(lines)
            note?.let { n ->
                val e = Entry(atMs, text, isStar)
                n.entries.add(e)
                n.updatedAt = System.currentTimeMillis()
                try {
                    NoteStore.appendEntry(n.id, e)
                    NoteStore.saveMeta(n)
                } catch (t: Throwable) {
                    Log.w(TAG, "write note failed", t)
                }
            }
        }
        var chars = 0
        for (l in snapshot) chars += l.text.length
        _state.update { it.copy(lineCount = snapshot.size, charCount = chars, lines = snapshot) }
    }
}
