package com.lecture.notes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lecture.notes.R
import com.lecture.notes.data.Entry
import com.lecture.notes.data.Note
import com.lecture.notes.data.NoteStore
import com.lecture.notes.databinding.ActivityDetailBinding
import com.lecture.notes.net.LlmDigest
import com.lecture.notes.util.Formats
import com.lecture.notes.util.ImageUtil
import com.lecture.notes.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailBinding
    private lateinit var adapter: LinesAdapter
    private var note: Note? = null
    private var editing = false
    /** 正在分析的那张图（相对路径），用来在行里显示「正在分析…」。 */
    private var analyzingRel: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        binding.toolbar.setNavigationOnClickListener { if (editing) setEditing(false) else finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (editing) {
                false
            } else {
                handleMenu(item.itemId)
            }
        }

        adapter = LinesAdapter().apply { textSizeSp = 15f * Prefs.fontScale }
        adapter.onImageClick = { row -> openShot(row) }
        adapter.onAnalyze = { row -> analyzeRow(row) }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.btnCancelEdit.setOnClickListener { setEditing(false) }
        binding.btnSaveEdit.setOnClickListener { saveEdit() }

        val id = intent.getStringExtra(EXTRA_ID)
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { if (id == null) null else NoteStore.load(id) }
            if (n == null) {
                toast(getString(R.string.detail_empty))
                finish()
                return@launch
            }
            note = n
            render()
        }
    }

    private fun render() {
        val n = note ?: return
        setEditing(false)
        binding.toolbar.title = n.title
        binding.meta.text = getString(
            R.string.detail_meta,
            Formats.friendly(n.createdAt),
            Formats.hms(n.durationMs),
            n.entries.size,
            n.charCount,
            n.starCount
        )
        adapter.submitRows(n.entries.map { rowOf(n, it) })
        binding.empty.visibility = if (n.entries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun rowOf(n: Note, e: Entry): Row = Row(
        atMs = e.atMs,
        text = if (e.isImage) "" else e.text,
        star = e.star,
        image = e.image,
        caption = e.caption,
        analyzed = e.analyzed,
        imageFile = e.image?.let { NoteStore.shotFile(n.id, it) },
        analyzing = e.image != null && e.image == analyzingRel
    )

    override fun onResume() {
        super.onResume()
        // 从看图页回来时，图片可能刚被分析过或者删掉了
        if (editing) return
        val n = note ?: return
        lifecycleScope.launch {
            val fresh = withContext(Dispatchers.IO) { NoteStore.load(n.id) }
            if (fresh != null) {
                note = fresh
                render()
            }
        }
    }

    private fun openShot(row: Row) {
        val n = note ?: return
        val rel = row.image ?: return
        startActivity(
            Intent(this, ShotActivity::class.java)
                .putExtra(ShotActivity.EXTRA_ID, n.id)
                .putExtra(ShotActivity.EXTRA_REL, rel)
                .putExtra(ShotActivity.EXTRA_AT, row.atMs)
        )
    }

    /** 行内的「AI 分析这张图」：分析完直接刷新这一行。 */
    private fun analyzeRow(row: Row) {
        val n = note ?: return
        val rel = row.image ?: return
        if (analyzingRel != null) return
        if (!LlmDigest.isReady()) {
            toast(getString(R.string.digest_ai_need_key))
            return
        }
        analyzingRel = rel
        render()
        lifecycleScope.launch {
            var err: String? = null
            withContext(Dispatchers.IO) {
                try {
                    val b64 = ImageUtil.visionBase64(NoteStore.shotFile(n.id, rel))
                        ?: throw IllegalStateException(getString(R.string.shot_missing))
                    val caption = LlmDigest.analyzeImage(b64, n.title)
                    if (caption.isBlank()) throw IllegalStateException("模型没有返回内容")
                    NoteStore.setImageCaption(n.id, row.atMs, rel, caption, true)
                } catch (t: Throwable) {
                    err = t.message ?: t.javaClass.simpleName
                    NoteStore.setImageCaption(n.id, row.atMs, rel, row.caption, row.analyzed)
                }
            }
            analyzingRel = null
            val fresh = withContext(Dispatchers.IO) { NoteStore.load(n.id) }
            if (fresh != null) note = fresh
            render()
            if (err == null) {
                toast(getString(R.string.shot_analyzed))
            } else {
                toast(getString(R.string.shot_analyze_failed, err!!))
            }
        }
    }

    private fun handleMenu(id: Int): Boolean {
        val n = note ?: return false
        when (id) {
            R.id.action_digest -> startActivity(
                Intent(this, DigestActivity::class.java).putExtra(EXTRA_ID, n.id)
            )

            R.id.action_edit -> setEditing(true)
            R.id.action_copy -> {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText(n.title, NoteStore.plainText(n)))
                toast(getString(R.string.detail_copied))
            }

            R.id.action_share -> {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, n.title)
                    .putExtra(Intent.EXTRA_TEXT, NoteStore.fullMarkdown(n))
                startActivity(Intent.createChooser(i, getString(R.string.detail_share)))
            }

            R.id.action_export -> export(n)
            R.id.action_rename -> renameDialog(n)
            R.id.action_delete -> AlertDialog.Builder(this)
                .setMessage(R.string.detail_delete_msg)
                .setPositiveButton(R.string.common_ok) { _, _ ->
                    NoteStore.delete(n.id)
                    toast(getString(R.string.toast_deleted))
                    finish()
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()

            else -> return false
        }
        return true
    }

    private fun export(n: Note) {
        Thread({
            val msg = try {
                val (mdPath, htmlPath) = NoteStore.exportAll(this, n, null)
                getString(R.string.export_done_two, mdPath, htmlPath)
            } catch (t: Throwable) {
                getString(R.string.detail_export_failed, t.message ?: "未知错误")
            }
            runOnUiThread { toast(msg) }
        }, "export").start()
    }

    private fun renameDialog(n: Note) {
        val input = EditText(this).apply {
            setText(n.title)
            setSelection(text.length)
        }
        val pad = (18 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this)
        container.addView(
            input,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(pad, pad / 2, pad, 0) }
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.detail_rename)
            .setView(container)
            .setPositiveButton(R.string.common_ok) { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    n.title = t
                    n.updatedAt = System.currentTimeMillis()
                    NoteStore.saveMeta(n)
                    binding.toolbar.title = t
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun setEditing(on: Boolean) {
        editing = on
        binding.editBox.visibility = if (on) View.VISIBLE else View.GONE
        binding.editBar.visibility = if (on) View.VISIBLE else View.GONE
        binding.list.visibility = if (on) View.GONE else View.VISIBLE
        binding.meta.visibility = if (on) View.GONE else View.VISIBLE
        if (on) {
            val n = note ?: return
            binding.editText.setText(NoteStore.plainText(n))
            binding.editHint.text = if (n.imageCount > 0) {
                getString(R.string.detail_plain_hint) + "\n" + getString(R.string.entry_shot_edit_hint, n.imageCount)
            } else {
                getString(R.string.detail_plain_hint)
            }
            binding.toolbar.title = getString(R.string.detail_edit)
            binding.empty.visibility = View.GONE
        } else {
            binding.toolbar.title = note?.title ?: getString(R.string.app_name)
            binding.empty.visibility = if (note?.entries?.isEmpty() == true) View.VISIBLE else View.GONE
        }
    }

    private fun saveEdit() {
        val n = note ?: return
        val parsed = NoteStore.parsePlainText(binding.editText.text.toString())
        // 截图不在编辑框里，保存文字时把它们按时间戳插回原位
        val entries = NoteStore.mergeEdit(n.entries, parsed)
        n.entries.clear()
        n.entries.addAll(entries)
        n.updatedAt = System.currentTimeMillis()
        NoteStore.rewriteEntries(n.id, entries)
        NoteStore.saveMeta(n)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.editText.windowToken, 0)
        render()
        toast(getString(R.string.detail_saved))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_ID = "note_id"
    }
}
