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
    var source: String,
    /**
     * 标题还是 App 自动起的（「网课笔记 09-27 17:22」或者从开头几句里挑的一句），
     * 用户没有自己命名过。AI 整理出这节课的题目之后，可以拿题目换掉这个名字；
     * 用户自己改过的名字是 false —— 整理多少次都一个字不动。
     */
    var autoTitle: Boolean = true,
    /**
     * 名字是 AI 整理时按这节课的题目起的。这种名字只起一次 —— 之后再点「重新 AI 整理」，
     * 名字不会再被换掉：不然用户重跑一次，笔记名就跟着变一次，翻笔记时根本对不上。
     */
    var aiTitle: Boolean = false
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
