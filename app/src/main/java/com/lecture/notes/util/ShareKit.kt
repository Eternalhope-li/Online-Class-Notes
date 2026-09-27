package com.lecture.notes.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.android.material.color.MaterialColors
import com.lecture.notes.R
import com.lecture.notes.data.Note
import com.lecture.notes.data.NoteStore
import java.io.File

/**
 * 「分享」这一下要给出几种格式 —— 接收方可能是聊天窗口（只认纯文本），也可能是别人的笔记软件、
 * 电脑上的编辑器、浏览器（Markdown 和 HTML 才带得走结构）。
 *
 * 所以不猜，直接让用户挑：纯文本 / Markdown 文件 / 网页文件。三份内容都来自同一个整理稿，
 * 差别只在包装：
 *  - 纯文本：塞进聊天窗口、备忘录，能认富文本的 App 会保留排版；
 *  - Markdown 文件：`.md` 附件，给 Obsidian / Typora / 别的笔记软件；
 *  - 网页文件：`.html` 附件，**单文件、图片内嵌**，双击就能看排版，也能直接打印成 PDF。
 *
 * 选择框是自己拼的 View，不是 `setItems` 的列表：个别 ROM（vivo 上实测）会把 `setItems` 的列表
 * 压成 0 高，用户只看到一个标题加一段说明，三个选项全没了 —— 自己拼就绕开了这套系统模板。
 */
object ShareKit {

    private class Choice(val title: String, val desc: String, val run: () -> Unit)

    /** 弹出「用哪种格式分享」，选完就走系统分享面板。 */
    fun share(activity: Activity, note: Note, markdown: String) {
        val md = markdown.ifBlank { NoteStore.readDigest(note.id) ?: NoteStore.fullMarkdown(note) }
        val choices = listOf(
            Choice(
                activity.getString(R.string.share_plain),
                activity.getString(R.string.share_plain_desc)
            ) { sendText(activity, note, md) },
            Choice(
                activity.getString(R.string.share_md),
                activity.getString(R.string.share_md_desc)
            ) { sendFile(activity, note, md, "md") },
            Choice(
                activity.getString(R.string.share_html),
                activity.getString(R.string.share_html_desc)
            ) { sendFile(activity, note, md, "html") },
        )

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 8), dp(activity, 8))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.share_title)
            .setMessage(R.string.share_tip)
            .setView(box)
            .setNegativeButton(R.string.common_cancel, null)
            .create()
        for (c in choices) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12))
                isClickable = true
                background = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let { a ->
                    val d = a.getDrawable(0)
                    a.recycle()
                    d
                }
            }
            row.addView(label(activity, c.title, 16f, onSurface(activity, 0.92f)))
            row.addView(label(activity, c.desc, 12f, onSurface(activity, 0.6f)))
            row.setOnClickListener {
                dialog.dismiss()
                c.run()
            }
            box.addView(row)
        }
        dialog.show()
    }

    private fun label(activity: Activity, text: String, size: Float, color: Int): TextView =
        TextView(activity).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            setPadding(0, dp(activity, 2), 0, dp(activity, 2))
        }

    private fun onSurface(activity: Activity, alpha: Float): Int {
        val base = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurface, 0xFF1B1B1F.toInt())
        return androidx.core.graphics.ColorUtils.setAlphaComponent(base, (255 * alpha).toInt())
    }

    private fun dp(activity: Activity, v: Int): Int =
        (v * activity.resources.displayMetrics.density).toInt()

    /** 纯文本 + 富文本：两个都塞进 extra，能认 HTML 的 App 会保留排版。 */
    private fun sendText(activity: Activity, note: Note, md: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, note.title)
            .putExtra(Intent.EXTRA_TEXT, MiniMarkdown.plain(md))
            .putExtra(Intent.EXTRA_HTML_TEXT, NoteStore.digestHtml(note, md))
        chooser(activity, i)
    }

    /**
     * 以文件分享。写进 `cache/share/`（FileProvider 已经声明了这个目录），接收方才拿得到。
     * 每次分享前先把这个目录清一遍：分享是高频动作，不清就会在缓存里越堆越多。
     */
    private fun sendFile(activity: Activity, note: Note, md: String, ext: String) {
        try {
            val dir = File(activity.cacheDir, "share")
            dir.listFiles()?.forEach { if (it.isFile) it.delete() }
            dir.mkdirs()
            val name = safeName(note.title) + "." + ext
            val file = File(dir, name)
            val body = if (ext == "html") {
                MiniMarkdown.toHtml(NoteStore.inlineImages(note, md), note.title)
            } else {
                md
            }
            file.writeText(body)
            val uri = FileProvider.getUriForFile(activity, activity.packageName + ".files", file)
            val type = if (ext == "html") "text/html" else "text/markdown"
            val i = Intent(Intent.ACTION_SEND).setType(type)
                .putExtra(Intent.EXTRA_SUBJECT, note.title)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TEXT, md)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            chooser(activity, i)
        } catch (t: Throwable) {
            toast(activity, activity.getString(R.string.share_fail, t.message ?: "打不开分享"))
        }
    }

    private fun chooser(activity: Activity, i: Intent) {
        try {
            activity.startActivity(Intent.createChooser(i, activity.getString(R.string.share_title)))
        } catch (t: Throwable) {
            toast(activity, activity.getString(R.string.share_fail, t.message ?: "没有可用的应用"))
        }
    }

    private fun safeName(title: String): String {
        val t = title.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().take(40)
        return t.ifBlank { "网课笔记" }
    }

    /** 详情页那条「复制全文」也走这儿：复制成富文本，粘进 Word / WPS 保留排版。 */
    fun copyRich(activity: Activity, note: Note, md: String) {
        val plain = MiniMarkdown.plain(md)
        val html = NoteStore.digestHtml(note, md)
        activity.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newHtmlText(note.title, plain, html))
        toast(activity, activity.getString(R.string.detail_copied))
    }

    private fun toast(activity: Activity, msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }
}
