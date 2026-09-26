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
import com.lecture.notes.util.Prefs
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class LiveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLiveBinding
    private lateinit var adapter: LinesAdapter
    private var autoScroll = true
    private var navigated = false
    private var wasActive = false

    /** 记录页的截图：还没有录屏授权时先要一次，拿到之后圆钮也会一起挂出来。 */
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                ShotService.start(this, result.resultCode, data, bySession = true)
                binding.btnShot.postDelayed({ ShotService.capture(this) }, 400)
            } else {
                toast(getString(R.string.toast_projection_denied))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    /**
     * 记录页的「课件截图」。
     *
     * 有授权就直接让悬浮截图服务抓一帧（和点圆钮完全一样），还没有授权就先要一次。
     * 一个 App 只能有一个 MediaProjection，内录正在用的时候不能重新申请，
     * 所以那种情况只提示、不弹框，免得把正在录的声音掐断。
     */
    private fun shot() {
        if (ShotGate.isReady()) {
            ShotService.start(this)
            ShotService.capture(this)
            toast(getString(R.string.shot_capturing))
            return
        }
        if (Prefs.audioSource == Prefs.SOURCE_INTERNAL) {
            toast(getString(R.string.live_shot_need_grant))
            return
        }
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
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

        if (st.lineCount < adapter.itemCount) adapter.clear()
        if (st.lineCount > adapter.itemCount) {
            val from = adapter.itemCount
            val rows = st.lines.subList(from, minOf(st.lineCount, st.lines.size)).map {
                Row(it.atMs, it.text, it.star, it.manual)
            }
            adapter.appendAll(rows)
            if (autoScroll) binding.lines.scrollToPosition(adapter.itemCount - 1)
        }

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
