package com.lecture.notes.data

/**
 * 一条笔记内容。
 *
 * 绝大多数是语音转写出来的文字；开了悬浮截图之后，也可以是一张截图：
 * [image] 是相对笔记目录的路径（如 `shots/2026-09-26-101530.jpg`），
 * [caption] 是 AI 视觉模型对这张图的分析说明，[analyzed] 标记是否分析成功。
 *
 * 图片条目同样带时间戳，所以在时间轴上和转写文字是混在一起的。
 */
data class Entry(
    val atMs: Long,
    val text: String,
    val star: Boolean = false,
    val image: String? = null,
    val caption: String = "",
    val analyzed: Boolean = false
) {
    val isImage: Boolean get() = !image.isNullOrBlank()

    /** 参与整理稿的那一行：图片用它的说明文字顶上去。 */
    val digestText: String
        get() = when {
            !isImage -> text
            caption.isBlank() -> "[图示]"
            else -> "[图示] " + caption.replace('\n', ' ')
        }
}

class Note(
    val id: String,
    var title: String,
    val createdAt: Long,
    var updatedAt: Long,
    var durationMs: Long,
    var source: String
) {
    val entries = ArrayList<Entry>()

    val charCount: Int get() = entries.sumOf { it.text.length + it.caption.length }
    val starCount: Int get() = entries.count { it.star }
    val imageCount: Int get() = entries.count { it.isImage }

    val preview: String
        get() {
            val text = entries.filter { !it.isImage && it.text.isNotBlank() }.take(2)
            if (text.isNotEmpty()) return text.joinToString(" ") { it.text }
            if (imageCount > 0) return "共 $imageCount 张课堂截图"
            return ""
        }
}
