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
import com.lecture.notes.data.Note
import com.lecture.notes.data.NoteStore
import com.lecture.notes.databinding.ActivityDigestBinding
import com.lecture.notes.net.LlmDigest
import com.lecture.notes.util.DemoNote
import com.lecture.notes.util.Formats
import com.lecture.notes.util.MiniMarkdown
import com.lecture.notes.util.NoteDigest
import com.lecture.notes.util.Prefs
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

        if (intent.getBooleanExtra(EXTRA_DEMO, false)) {
            isDemo = true
            note = DemoNote.note()
            show(DemoNote.markdown(), getString(R.string.digest_demo_status))
            return
        }

        val id = intent.getStringExtra(DetailActivity.EXTRA_ID)
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) {
                if (id == null) null else NoteStore.load(id)
            }
            if (n == null) {
                toast(getString(R.string.detail_empty))
                finish()
                return@launch
            }
            note = n
            val saved = withContext(Dispatchers.IO) { NoteStore.readDigest(n.id) }
            if (!saved.isNullOrBlank()) {
                isAi = saved.contains(getString(R.string.digest_tag_ai))
                show(saved, getString(R.string.digest_saved_status))
            } else {
                rebuild()
            }
        }
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
        val n = note ?: return
        if (busy) return
        if (!LlmDigest.isReady()) {
            toast(getString(R.string.digest_ai_need_key))
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        setBusy(true, getString(R.string.digest_ai_start), 0, 0)
        lifecycleScope.launch {
            try {
                val body = LlmDigest.digest(n) { done, total, label ->
                    runOnUiThread { setBusy(true, getString(R.string.digest_ai_loading, done, total, label), done, total) }
                }
                // 截图不属于「文字转写」，AI 也看不到图，所以整理完由 App 自己把「本课图示」拼在后面
                val md = header(n, getString(R.string.digest_tag_ai)) + body + "\n" + NoteStore.shotsSection(n)
                withContext(Dispatchers.IO) { NoteStore.saveDigest(n.id, md) }
                isAi = true
                show(md, getString(R.string.digest_ai_done, Prefs.llmModel))
            } catch (t: Throwable) {
                toast(getString(R.string.digest_failed, t.message ?: t.javaClass.simpleName))
            } finally {
                setBusy(false, null, 0, 0)
            }
        }
    }

    /** 整理稿的开头：标题 + 元信息 + 本课关键词（AI 的终稿同样接在这后面）。 */
    private fun header(n: Note, tag: String): String {
        val sb = StringBuilder()
        sb.append("# ").append(n.title).append("\n\n")
        sb.append("> ").append(Formats.dateTime(n.createdAt))
            .append("　时长 ").append(Formats.hms(n.durationMs))
            .append("　共 ").append(n.entries.size).append(" 句（").append(tag).append("）\n\n")
        val kw = NoteDigest.terms(n.entries, 16)
        if (kw.isNotEmpty()) {
            sb.append("## 本课关键词\n\n")
            for (k in kw) sb.append('`').append(k).append("` ")
            sb.append("\n\n")
        }
        return sb.toString()
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

            R.id.action_share -> {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, n.title)
                    .putExtra(Intent.EXTRA_TEXT, MiniMarkdown.plain(markdown))
                    .putExtra(Intent.EXTRA_HTML_TEXT, MiniMarkdown.toHtml(markdown, n.title))
                startActivity(Intent.createChooser(i, getString(R.string.digest_share)))
            }

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
            renderer.imageDir = if (n == null) null else NoteStore.shotsDir(n.id)
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
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        /** 打开「排版预览」用的示例笔记，不落盘。 */
        const val EXTRA_DEMO = "demo"
    }
}
