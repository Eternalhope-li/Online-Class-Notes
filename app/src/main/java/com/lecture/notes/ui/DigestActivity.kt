package com.lecture.notes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lecture.notes.R
import com.lecture.notes.core.DigestJob
import com.lecture.notes.data.Note
import com.lecture.notes.data.NoteStore
import com.lecture.notes.databinding.ActivityDigestBinding
import com.lecture.notes.net.LlmDigest
import com.lecture.notes.util.DemoNote
import com.lecture.notes.util.MiniMarkdown
import com.lecture.notes.util.NoteDigest
import com.lecture.notes.util.Prefs
import com.lecture.notes.util.ShareKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 知识点笔记页（整理稿）。
 *
 * 打开就先用本地算法把逐句转写整理成提纲（毫秒级、不联网、马上能看），
 * 想要更成体系就点「用 AI 体系化整理」，交给大模型重排成一份带框架 / 术语表 / 易错点的终稿。
 * 两种整理稿都按同一套排版渲染：章节徽标、可折叠小节、时间戳胶囊、重点卡片、表格。
 * 排版视图和 Markdown 源码可以随时切换；导出时会同时给出 .md 和排好版的 .html。
 */
class DigestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDigestBinding
    private lateinit var renderer: NoteRenderer
    private var note: Note? = null
    private var markdown: String = ""
    private var busy = false
    private var isAi = false
    private var isDemo = false
    /** 本页面发起的那次后台整理，进度才往这个页面上回馈。 */
    private var aiWatch = false
    /** 开场那一版整理稿读完了没（onResume 靠它避免抢跑）。 */
    private var loaded = false
    private var autoScroll = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDigestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        renderer = NoteRenderer(this)
        renderer.tocTitle = getString(R.string.digest_toc)

        binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { onMenu(it.itemId) }
        binding.toolbar.menu.findItem(R.id.action_view)?.title =
            getString(if (Prefs.digestRich) R.string.digest_view_raw else R.string.digest_view_rich)

        // 顶部那颗按钮：平时是「用 AI 体系化整理」，后台正在整理这篇时变成「取消生成」
        binding.btnAi.setOnClickListener {
            if (DigestJob.isRunning(note?.id)) DigestJob.cancel() else if (!busy) runAi()
        }
        binding.btnStaleUpdate.setOnClickListener { updateStale() }
        binding.scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            // 用户往上翻看前面内容时就别再自动往下滚了
            autoScroll = !binding.scroll.canScrollVertically(1)
        }

        // 后台整理任务的进度：只给一条干净的状态，模型半成品的文字一律不往用户眼前刷
        lifecycleScope.launch { DigestJob.state.collect { onJobState(it) } }

        if (intent.getBooleanExtra(EXTRA_DEMO, false)) {
            isDemo = true
            note = DemoNote.note()
            show(DemoNote.markdown(), getString(R.string.digest_demo_status))
            return
        }

        val id = intent.getStringExtra(DetailActivity.EXTRA_ID)
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { if (id == null) null else NoteStore.load(id) }
            if (n == null) {
                toast(getString(R.string.detail_empty))
                finish()
                return@launch
            }
            note = n
            if (DigestJob.isRunning(n.id)) aiWatch = true
            // 人都翻到这篇整理稿了，那条「整理好了」的通知就别再占着通知栏
            DigestJob.clearNoteNotif(n.id)
            bindNote()
        }
    }

    override fun onResume() {
        super.onResume()
        // 后台任务可能在别的页面（或从通知）跑完了，回到这里对一下磁盘上的版本
        val n = note ?: return
        if (isDemo || !loaded) return
        // 从别的页面（或从通知）回来时，这篇笔记的后台整理可能还在跑：
        // 接上进度，别让页面看起来像「什么都没发生」。
        if (DigestJob.isRunning(n.id)) aiWatch = true
        DigestJob.clearNoteNotif(n.id)
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { NoteStore.readDigest(n.id) }
            if (!saved.isNullOrBlank() && saved != markdown) {
                isAi = saved.contains(getString(R.string.digest_tag_ai))
                show(saved, getString(R.string.digest_saved_status))
            }
        }
    }

    /**
     * 决定开场看哪一版整理稿。
     *
     *  - 还没有整理稿 → 立刻做一份本地整理（毫秒级、不联网）；
     *  - 有整理稿，但笔记在那之后又新增了内容（多半是新截的课件图）→ 本地整理稿直接重做；
     *    AI 整理稿只在顶上提示「有新内容」，要不要再花一次 token 由用户自己决定。
     */
    private fun bindNote() {
        val n = note ?: return
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { NoteStore.readDigest(n.id) }
            val stale = withContext(Dispatchers.IO) { NoteStore.digestStale(n.id) }
            isAi = !saved.isNullOrBlank() && saved.contains(getString(R.string.digest_tag_ai))
            loaded = true
            if (saved.isNullOrBlank() || (stale && !isAi)) {
                rebuild()
                return@launch
            }
            show(saved, getString(R.string.digest_saved_status))
            binding.staleBar.visibility = if (stale) View.VISIBLE else View.GONE
        }
    }

    /** 顶上「有新内容」那条：AI 整理稿就再跑一次，本地整理稿就地重做。 */
    private fun updateStale() {
        binding.staleBar.visibility = View.GONE
        if (isAi) runAi() else rebuild()
    }

    // ------------------------------------------------------------ 本地整理

    private fun rebuild() {
        val n = note ?: return
        if (busy) return
        setBusy(true, getString(R.string.digest_working), 0, 0)
        lifecycleScope.launch {
            try {
                val pair = withContext(Dispatchers.Default) { NoteDigest.build(n) }
                val md = pair.first
                val r = pair.second
                withContext(Dispatchers.IO) { NoteStore.saveDigest(n.id, md) }
                isAi = false
                binding.staleBar.visibility = View.GONE
                show(
                    md,
                    getString(R.string.digest_local_done, r.rawCount, r.sections.size, r.keptCount)
                )
            } catch (t: Throwable) {
                toast(getString(R.string.digest_failed, t.message ?: t.javaClass.simpleName))
            } finally {
                setBusy(false, null, 0, 0)
            }
        }
    }

    // ------------------------------------------------------------ AI 体系化整理

    private fun runAi() {
        if (isDemo) {
            toast(getString(R.string.digest_demo_readonly))
            return
        }
        val n = note ?: return
        if (DigestJob.isRunning()) {
            if (DigestJob.isRunning(n.id)) {
                DigestJob.cancel()
                toast(getString(R.string.digest_ai_cancelled))
            } else {
                toast(getString(R.string.digest_ai_busy))
            }
            return
        }
        if (!LlmDigest.isReady()) {
            toast(getString(R.string.digest_ai_need_key))
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        // 交给进程级的后台任务：用户可以立刻退出这个页面，整理完发通知
        aiWatch = true
        if (DigestJob.start(n)) toast(getString(R.string.digest_ai_background))
        else aiWatch = false
    }

    /**
     * 后台任务的进度 / 结果。
     *
     * 只有「本页面发起的那一次」才回馈到界面上 —— 别处（通知、别的笔记）发起的任务，
     * 让通知去说话，不要在这个页面上突然冒出进度。
     */
    private fun onJobState(st: DigestJob.State) {
        val n = note ?: return
        if (!aiWatch || st.noteId != n.id) return
        when (st.phase) {
            DigestJob.Phase.RUNNING -> setBusy(true, aiStatus(st.done, st.total), st.done, st.total)
            DigestJob.Phase.DONE -> {
                aiWatch = false
                DigestJob.consume()
                setBusy(false, null, 0, 0)
                reload(getString(R.string.digest_ai_done, Prefs.llmModel))
            }

            DigestJob.Phase.FAILED -> {
                aiWatch = false
                val msg = st.message.ifBlank { getString(R.string.digest_ai_cancelled) }
                DigestJob.consume()
                setBusy(false, null, 0, 0)
                restore(msg)
                toast(msg)
            }

            DigestJob.Phase.IDLE -> Unit
        }
    }

    /** 重新读磁盘上的整理稿并渲染（后台任务写完盘之后靠它把结果接上）。 */
    private fun reload(status: String?) {
        val n = note ?: return
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { NoteStore.readDigest(n.id) }
            if (saved.isNullOrBlank()) return@launch
            val stale = withContext(Dispatchers.IO) { NoteStore.digestStale(n.id) }
            isAi = saved.contains(getString(R.string.digest_tag_ai))
            // 整理稿刚写完时通常已经不落后了；但截图的 AI 说明是之后回写的，
            // 真比整理稿新还是得提示 —— 现算一次，别把上一轮的状态留在屏幕上
            binding.staleBar.visibility = if (stale) View.VISIBLE else View.GONE
            show(saved, status)
        }
    }

    private fun aiStatus(done: Int, total: Int): String = if (total > 1) {
        getString(R.string.digest_ai_running_multi, done, total)
    } else {
        getString(R.string.digest_ai_running_page)
    }

    /** 失败或取消后回到上一版（后台任务没写盘，磁盘上还是旧的那份）。 */
    private fun restore(msg: String) {
        if (markdown.isNotBlank()) show(markdown, null)
        binding.status.text = msg
    }

    // ------------------------------------------------------------ 菜单

    private fun onMenu(id: Int): Boolean {
        val n = note ?: return false
        if (isDemo && (id == R.id.action_ai || id == R.id.action_rebuild || id == R.id.action_export)) {
            toast(getString(R.string.digest_demo_readonly))
            return true
        }
        when (id) {
            R.id.action_ai -> runAi()
            R.id.action_rebuild -> rebuild()
            R.id.action_view -> {
                Prefs.digestRich = !Prefs.digestRich
                binding.toolbar.menu.findItem(R.id.action_view)?.title =
                    getString(if (Prefs.digestRich) R.string.digest_view_raw else R.string.digest_view_rich)
                show(markdown, getString(if (Prefs.digestRich) R.string.digest_mode_rich else R.string.digest_mode_raw))
            }

            R.id.action_font -> {
                Prefs.digestFontScale = nextScale(Prefs.digestFontScale)
                show(markdown, getString(R.string.digest_font_now, (Prefs.digestFontScale * 100).toInt()))
            }

            R.id.action_copy -> {
                val plain = MiniMarkdown.plain(markdown)
                val html = MiniMarkdown.toHtml(markdown, n.title)
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newHtmlText(n.title, plain, html))
                toast(getString(R.string.digest_copied))
            }

            // 分享这一步要挑格式：聊天窗口只认纯文本，别的笔记软件要 Markdown，
            // 想连排版和截图一起带走就用单文件的网页
            R.id.action_share -> ShareKit.share(this, n, markdown)

            R.id.action_export -> export(n)
            else -> return false
        }
        return true
    }

    private fun nextScale(cur: Float): Float = when {
        cur < 0.95f -> 1.0f
        cur < 1.05f -> 1.15f
        cur < 1.2f -> 1.3f
        else -> 0.9f
    }

    private fun export(n: Note) {
        Thread({
            val md = markdown
            val msg = try {
                val (a, b) = NoteStore.exportAll(this, n, md)
                getString(R.string.export_done_two, a, b)
            } catch (t: Throwable) {
                getString(R.string.digest_failed, t.message ?: "未知错误")
            }
            runOnUiThread { toast(msg) }
        }, "digest-export").start()
    }

    // ------------------------------------------------------------ 杂项

    private fun show(md: String, status: String?) {
        markdown = md
        val scale = Prefs.digestFontScale
        if (Prefs.digestRich) {
            binding.contentBox.visibility = View.VISIBLE
            binding.content.visibility = View.GONE
            renderer.scale = scale
            val n = if (isDemo) null else note
            renderer.imageDir = if (n == null) null else NoteStore.noteDir(n.id)
            renderer.onImageClick = { rel -> openShot(rel) }
            renderer.render(binding.contentBox, md, binding.scroll)
        } else {
            binding.contentBox.visibility = View.GONE
            binding.content.visibility = View.VISIBLE
            binding.content.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f * scale)
            binding.content.text = md
        }
        binding.empty.visibility = if (md.isBlank()) View.VISIBLE else View.GONE
        binding.scroll.scrollTo(0, 0)
        if (status != null) {
            binding.status.text = when {
                isDemo -> status
                isAi -> getString(R.string.digest_status_ai, status)
                else -> getString(R.string.digest_status_local, status)
            }
        }
        // 已经有 AI 整理稿时按钮改叫「重新整理一遍」，不然用户以为要重新生成一份
        if (!busy) binding.btnAi.text = getString(aiButtonLabel())
    }

    /** 整理稿里点一张截图 → 打开大图页。 */
    private fun openShot(rel: String) {
        if (isDemo) return
        val n = note ?: return
        val at = n.entries.firstOrNull { it.image == rel }?.atMs ?: 0L
        startActivity(
            Intent(this, ShotActivity::class.java)
                .putExtra(ShotActivity.EXTRA_ID, n.id)
                .putExtra(ShotActivity.EXTRA_REL, rel)
                .putExtra(ShotActivity.EXTRA_AT, at)
        )
    }

    private fun setBusy(b: Boolean, msg: String?, done: Int, total: Int) {
        busy = b
        binding.progress.visibility = if (b) View.VISIBLE else View.GONE
        if (b && total > 0) {
            binding.progress.isIndeterminate = false
            binding.progress.max = total
            binding.progress.progress = done
        } else {
            binding.progress.isIndeterminate = true
        }
        if (msg != null) binding.status.text = msg
        binding.btnAi.text = getString(aiButtonLabel())
        binding.toolbar.menu.findItem(R.id.action_ai)?.isEnabled = !b
        binding.toolbar.menu.findItem(R.id.action_rebuild)?.isEnabled = !b
        binding.toolbar.menu.findItem(R.id.action_export)?.isEnabled = !b
    }

    /** 顶部按钮的文案：整理中 = 取消；已经有 AI 整理稿 = 再整理一遍；否则 = 开始整理。 */
    private fun aiButtonLabel(): Int = when {
        busy -> R.string.digest_ai_cancel
        isAi -> R.string.digest_ai_redo
        else -> R.string.digest_ai_do
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        /** 打开「排版预览」用的示例笔记，不落盘。 */
        const val EXTRA_DEMO = "demo"
    }
}
