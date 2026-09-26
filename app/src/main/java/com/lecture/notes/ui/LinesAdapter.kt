package com.lecture.notes.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lecture.notes.R
import com.lecture.notes.databinding.ItemLineBinding
import com.lecture.notes.util.Formats
import com.lecture.notes.util.Thumbs
import java.io.File

/**
 * 实时记录页和笔记详情页共用的一行。
 *
 * [image] 不为空时这一行是课堂截图：[imageFile] 是磁盘上的图，[caption] 是 AI 写的说明，
 * [analyzed] 为 false 说明还没分析出来，行里会出现一个「AI 分析这张图」的按钮。
 */
data class Row(
    val atMs: Long,
    val text: String,
    val star: Boolean,
    val manual: Boolean = false,
    val image: String? = null,
    val caption: String = "",
    val analyzed: Boolean = true,
    val imageFile: File? = null,
    val analyzing: Boolean = false
)

class LinesAdapter : RecyclerView.Adapter<LinesAdapter.VH>() {

    private val items = ArrayList<Row>()

    var textSizeSp: Float = 16f
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, items.size)
        }

    /** 点截图看大图。 */
    var onImageClick: ((Row) -> Unit)? = null

    /** 点「AI 分析这张图」。 */
    var onAnalyze: ((Row) -> Unit)? = null

    class VH(val b: ItemLineBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemLineBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        val b = holder.b
        b.time.text = Formats.mmss(row.atMs)
        b.text.textSize = textSizeSp
        b.tag.visibility = if (row.star) View.VISIBLE else View.GONE
        if (row.star) {
            b.root.setBackgroundResource(R.drawable.bg_star_line)
        } else {
            b.root.setBackgroundResource(0)
        }

        val rel = row.image
        if (rel == null) {
            b.shotBox.visibility = View.GONE
            b.text.visibility = View.VISIBLE
            b.text.text = if (row.manual) "★ " + row.text else row.text
            return
        }

        b.text.visibility = if (row.text.isBlank()) View.GONE else View.VISIBLE
        b.text.text = row.text
        b.shotBox.visibility = View.VISIBLE

        val f = row.imageFile
        if (f != null && f.exists()) {
            Thumbs.load(f, 1200, b.shot, rel)
            b.shot.setOnClickListener { onImageClick?.invoke(row) }
        } else {
            b.shot.setOnClickListener(null)
        }

        val caption = tidyShotCaption(row.caption)
        if (caption.isEmpty()) {
            b.shotCaption.visibility = View.GONE
        } else {
            b.shotCaption.visibility = View.VISIBLE
            b.shotCaption.textSize = 13f * (textSizeSp / 16f).coerceIn(0.85f, 1.4f)
            b.shotCaption.text = caption
        }

        val needAnalyze = row.caption.isBlank() || !row.analyzed
        b.btnAnalyze.visibility = if (needAnalyze) View.VISIBLE else View.GONE
        if (!needAnalyze) {
            b.btnAnalyze.setOnClickListener(null)
            return
        }
        val ctx = b.root.context
        b.btnAnalyze.text = ctx.getString(
            when {
                row.analyzing -> R.string.entry_shot_analyzing
                row.caption.isBlank() -> R.string.entry_shot_analyze
                else -> R.string.entry_shot_reanalyze
            }
        )
        b.btnAnalyze.isEnabled = !row.analyzing
        b.btnAnalyze.alpha = if (row.analyzing) 0.55f else 1f
        b.btnAnalyze.setOnClickListener { if (!row.analyzing) onAnalyze?.invoke(row) }
    }

    fun clear() {
        if (items.isEmpty()) return
        val n = items.size
        items.clear()
        notifyItemRangeRemoved(0, n)
    }

    fun appendAll(rows: List<Row>) {
        if (rows.isEmpty()) return
        val start = items.size
        items.addAll(rows)
        notifyItemRangeInserted(start, rows.size)
    }

    fun submitRows(rows: List<Row>) {
        items.clear()
        items.addAll(rows)
        notifyDataSetChanged()
    }
}

/** AI 写出来的图片说明是 Markdown 列表，界面上换成更好读的圆点。 */
internal fun tidyShotCaption(raw: String): String {
    if (raw.isBlank()) return ""
    return raw.trim().lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n") { line ->
            if (line.startsWith("- ") || line.startsWith("* ")) {
                "· " + line.substring(2).trim()
            } else {
                line
            }
        }
}
