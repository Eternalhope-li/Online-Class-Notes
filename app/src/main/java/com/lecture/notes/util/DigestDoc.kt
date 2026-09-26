package com.lecture.notes.util

import com.lecture.notes.data.Note

/**
 * 整理稿文档的拼装。
 *
 * 单独抽一层，是因为「本地整理」「AI 整理」和「后台整理任务」三条路都要生成同一份文档，
 * 头部必须完全一致，重新打开才不会看起来像换了一篇。
 */
object DigestDoc {

    /** 标题 + 元信息 + 本课关键词。AI 的终稿接在这后面。 */
    fun header(note: Note, tag: String): String {
        val sb = StringBuilder()
        sb.append("# ").append(note.title).append("\n\n")
        sb.append("> ").append(Formats.dateTime(note.createdAt))
            .append("　时长 ").append(Formats.hms(note.durationMs))
            .append("　共 ").append(note.entries.size).append(" 句（").append(tag).append("）\n\n")
        val kw = NoteDigest.terms(note.entries, 16)
        if (kw.isNotEmpty()) {
            sb.append("## 本课关键词\n\n")
            for (k in kw) sb.append('`').append(k).append("` ")
            sb.append("\n\n")
        }
        return sb.toString()
    }

    /**
     * AI 整理稿的完整文档。
     *
     * [body] 是模型的正文，里面已经按 `[[IMGn]]` 记号把截图还原成了真图片
     * （见 [com.lecture.notes.net.LlmDigest.embedImages]）。
     */
    fun ai(note: Note, tag: String, body: String): String = header(note, tag) + body.trim() + "\n"
}
