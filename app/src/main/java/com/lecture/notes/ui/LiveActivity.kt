package com.lecture.notes.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lecture.notes.R
import com.lecture.notes.core.CaptureService
import com.lecture.notes.core.Recorder
import com.lecture.notes.core.ShotGate
import com.lecture.notes.core.ShotService
import com.lecture.notes.data.NoteStore
import com.lecture.notes.databinding.ActivityLiveBinding
import com.lecture.notes.util.Formats
import com.lecture.notes.util.OverlayPerm
import com.lecture.notes.util.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class LiveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLiveBinding
    private lateinit var adapter: LinesAdapter
    private var autoScroll = true
    private var navigated = false
    private var wasActive = false

    /** 列表里已经画出来的转写行数（截图行另算）。 */
    private var linesInList = 0

    /** 已经画进列表的那一份截图；换了对象才需要整表重建。 */
    private var listedShots: List<Row>? = null

    /** 截图读盘的缓存：笔记 id + 截图版本号都没变就不必再读一遍盘。 */
    private var shotsNoteId: String? = null
    private var shotsTickSeen = -1L
    private var shotsCache: List<Row> = emptyList()

    /** 正在手动分析的那张图（相对路径）。 */
    private var analyzingShot: String? = null

    /** 这次要录屏授权是为了「顺手截一张」还是「只为挂圆钮」。 */
    private var wantCapture = false

    /** 记录页的截图：还没有录屏授权时先要一次，拿到之后圆钮也会一起挂出来。 */
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                ShotService.start(this, result.resultCode, data, bySession = true)
                // 用户点的是「课件截图」时才顺手截这一张；只是为挂圆钮要的授权不截
                if (wantCapture) binding.btnShot.postDelayed({ ShotService.capture(this) }, 400)
            } else {
                toast(getString(R.string.toast_projection_denied))
            }
            wantCapture = false
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 录课时一直亮着：看板书的人不会去点屏幕，让它按系统超时灭屏反而碍事。
        // 真要省电（放兜里只听声音）按一下电源键就行，这个标记不拦电源键。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityLiveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_font) {
                cycleFont()
                true
            } else {
                false
            }
        }

        adapter = LinesAdapter().apply { textSizeSp = 16f * Prefs.fontScale }
        // 截图点开看大图；还没说明的那张，就地也能点「AI 分析这张图」
        adapter.onImageClick = { row ->
            Recorder.state.value.noteId?.let { ShotRowActions.open(this, it, row) }
        }
        adapter.onAnalyze = { row -> analyzeShot(row) }
        binding.lines.layoutManager = LinearLayoutManager(this)
        binding.lines.adapter = adapter
        binding.lines.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                // 贴着底部就继续自动滚动；手动往上翻就不打扰
                autoScroll = !rv.canScrollVertically(1)
            }
        })

        binding.btnPause.setOnClickListener { Recorder.togglePause() }
        binding.btnStar.setOnClickListener {
            Recorder.markStar()
            toast(getString(R.string.star_marker))
        }
        binding.btnStop.setOnClickListener { confirmStop() }
        binding.btnShot.setOnClickListener { shot() }

        if (Prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        lifecycleScope.launch {
            Recorder.state.collect { render(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        // 刚从系统设置里给完悬浮窗权限回来：把挂在半路的事（挂圆钮 / 截这一张）接着做完
        OverlayPerm.resume(this)
        askBallPerm()
    }

    /**
     * 记录页问一次「要不要开截图圆钮」。
     *
     * 没有「显示在其他应用上层」权限就挂不出圆钮，而这里才是用户看得见对话框的地方
     * （首页一按开始记录就跳过来了，在那儿问会被这一页盖住）。只问一次：
     * 用户说过「这次不用」就不再打扰，想开的时候设置页里的开关随时能开。
     */
    private fun askBallPerm() {
        if (Prefs.overlayAsked) return
        if (!Prefs.autoShot) return
        if (OverlayPerm.granted(this)) return
        Prefs.overlayAsked = true
        OverlayPerm.ensure(this) { attachBall() }
    }

    /** 权限到手之后把圆钮挂出来（内录的授权可能刚登记好，等一下）。 */
    private fun attachBall() {
        lifecycleScope.launch {
            var waited = 0
            while (!ShotGate.isReady() && waited < 2000) {
                delay(100)
                waited += 100
            }
            if (ShotGate.isReady()) {
                ShotService.start(this@LiveActivity)
                ShotService.showBall(this@LiveActivity)
                return@launch
            }
            // 用麦克风录的时候圆钮得靠自己那份录屏授权，这里补要一次（只要授权，不顺手截图）
            if (Prefs.audioSource == Prefs.SOURCE_MIC) {
                wantCapture = false
                toast(getString(R.string.toast_projection_scope))
                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mgr.createScreenCaptureIntent())
            }
        }
    }

    /**
     * 记录页的「课件截图」。
     *
     * 有授权就直接让悬浮截图服务抓一帧（和点圆钮完全一样），还没有授权就先要一次。
     * 一个 App 只能有一个 MediaProjection，内录正在用的时候不能重新申请，
     * 所以那种情况只提示、不弹框，免得把正在录的声音掐断。
     */
    private fun shot() {
        if (!OverlayPerm.granted(this)) {
            // 圆钮要「显示在其他应用上层」：先解释一句、跳去开，回来再接着截这一张
            OverlayPerm.ensure(this) { shot() }
            return
        }
        if (ShotGate.isReady()) {
            ShotService.start(this)
            // 权限是刚给的话，服务在跑但还没有圆钮，这时补一个
            ShotService.showBall(this)
            ShotService.capture(this)
            toast(getString(R.string.shot_capturing))
            return
        }
        if (Prefs.audioSource == Prefs.SOURCE_INTERNAL) {
            toast(getString(R.string.live_shot_need_grant))
            return
        }
        toast(getString(R.string.toast_projection_scope))
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        wantCapture = true
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun confirmStop() {
        // 默认名字直接取这次记录开头的字，先填好，想改就在框里改
        val id = Recorder.state.value.noteId
        val suggested = id?.let { NoteStore.suggestTitle(it) }
            .orEmpty()
            .ifBlank {
                getString(R.string.note_default_title, Formats.shortStamp(System.currentTimeMillis()))
            }
        val input = EditText(this).apply {
            setText(suggested)
            setSelection(suggested.length)
            setHint(R.string.note_title_hint)
            maxLines = 1
        }
        val pad = (resources.displayMetrics.density * 20f).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, 0, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.note_save_title)
            .setMessage(R.string.live_stop_confirm)
            .setView(box)
            .setPositiveButton(R.string.common_save) { _, _ ->
                CaptureService.stop(this, input.text?.toString()?.trim())
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun render(st: Recorder.State) {
        binding.timer.text = Formats.hms(st.elapsedMs)
        binding.status.text = if (st.source == Prefs.SOURCE_INTERNAL && st.lineCount == 0 && st.elapsedMs > 30000) {
            getString(R.string.live_internal_silent)
        } else {
            st.status
        }
        binding.level.progress = (st.level * 100f).roundToInt().coerceIn(0, 100)

        val sourceLabel = if (st.source == Prefs.SOURCE_INTERNAL) {
            getString(R.string.live_internal)
        } else {
            getString(R.string.live_mic)
        }
        var count = getString(R.string.live_lines, st.lineCount, st.charCount) + " · " + sourceLabel
        if (st.skipped > 0) count += " · " + getString(R.string.live_skipped, st.skipped)
        binding.count.text = count

        binding.hint.visibility = if (st.lineCount == 0) View.VISIBLE else View.GONE
        binding.btnPause.text = if (st.phase == Recorder.Phase.PAUSED) {
            getString(R.string.live_resume)
        } else {
            getString(R.string.live_pause)
        }

        syncList(st)

        if (st.active) wasActive = true
        if (wasActive && !st.active && !navigated) {
            val id = st.noteId ?: CaptureService.lastStoppedNoteId
            navigated = true
            if (id != null) {
                // 录完最想看的是「整理好的笔记」，不是原始转写：直接去整理稿页（打开就地做本地整理），
                // 从那儿返回才是详情页 —— 把「保存 → 整理 → 阅读」串成一步，少点一次
                startActivity(Intent(this, DigestActivity::class.java).putExtra(DetailActivity.EXTRA_ID, id))
            }
            finish()
        }
    }

    /**
     * 列表 = 转写行 + 本课截图行，按时间戳混排。
     *
     * 截图走整表重建（一节课也就几张，不心疼），转写走末尾追加 —— 新句子永远比已经画出来的
     * 东西新，接在后面就对；只有「来了张图 / 说明写回来了 / 列表被清过」才整表重建。
     */
    private fun syncList(st: Recorder.State) {
        val shots = shotsOf(st)
        if (shots !== listedShots || st.lineCount < linesInList) {
            listedShots = shots
            linesInList = st.lineCount
            adapter.submitRows(mergeRecordRows(rowsOf(st), shots))
            scrollToEnd()
            return
        }
        if (st.lineCount > linesInList) {
            val from = linesInList
            val fresh = st.lines.subList(from, minOf(st.lineCount, st.lines.size)).map { lineRow(it) }
            linesInList = st.lineCount
            adapter.appendAll(fresh)
            scrollToEnd()
        }
    }

    private fun scrollToEnd() {
        if (autoScroll && adapter.itemCount > 0) {
            binding.lines.scrollToPosition(adapter.itemCount - 1)
        }
    }

    private fun lineRow(l: Recorder.Line) = Row(l.atMs, l.text, l.star, l.manual)

    private fun rowsOf(st: Recorder.State): List<Row> =
        st.lines.take(minOf(st.lineCount, st.lines.size)).map { lineRow(it) }

    /** 本课的截图行；只有截图版本号变了、或者换了笔记，才去读盘。 */
    private fun shotsOf(st: Recorder.State): List<Row> {
        val id = st.noteId ?: return emptyList()
        val tick = NoteStore.shotsTick.value
        if (id != shotsNoteId || tick != shotsTickSeen) {
            shotsNoteId = id
            shotsTickSeen = tick
            shotsCache = readShots(id)
        }
        return shotsCache
    }

    private fun readShots(id: String): List<Row> {
        val note = NoteStore.load(id) ?: return emptyList()
        return note.entries.filter { it.isImage }.map { e ->
            Row(
                atMs = e.atMs,
                text = "",
                star = e.star,
                image = e.image,
                caption = e.caption,
                analyzed = e.analyzed,
                imageFile = e.image?.let { NoteStore.shotFile(id, it) },
                analyzing = e.image != null && e.image == analyzingShot
            )
        }
    }

    /** 记录页自己发起的「AI 分析这张图」：跑完让列表重画一次。 */
    private fun analyzeShot(row: Row) {
        val st = Recorder.state.value
        val id = st.noteId ?: return
        val rel = row.image ?: return
        if (analyzingShot != null) return
        analyzingShot = rel
        refreshShots()
        ShotRowActions.analyze(this, id, st.title, row) {
            analyzingShot = null
            refreshShots()
        }
    }

    /** 截图那边有动静（自己分析的、后台分析完写回来的）就重读一次再重画。 */
    private fun refreshShots() {
        shotsTickSeen = -1L
        syncList(Recorder.state.value)
    }

    private fun cycleFont() {
        val next = when {
            Prefs.fontScale < 0.95f -> 1.0f
            Prefs.fontScale < 1.15f -> 1.3f
            else -> 0.85f
        }
        Prefs.fontScale = next
        adapter.textSizeSp = 16f * next
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
