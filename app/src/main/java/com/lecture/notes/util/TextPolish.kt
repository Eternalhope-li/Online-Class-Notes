package com.lecture.notes.util

/**
 * 把识别出来的原始文本整理成能看的笔记：
 * 去掉 SenseVoice 的事件标记 / 语气词、合并空格、规范标点。
 */
object TextPolish {

    private val TAG_RE = Regex("<\\|[^|>]*\\|>")
    private val MULTI_SPACE = Regex("[ \\t\\u3000]{1,}")
    private val FILLER_HEAD = Regex("^(嗯+|呃+|啊+|哦+|额+|那个|就是|然后)[，,、]?\\s*")
    private val FILLER_TAIL = Regex("\\s*[，,、](嗯+|呃+|啊+|哦+|额+)$")
    private val REPEAT_PUNCT = Regex("([，。！？；：、,.!?;:])\\1{1,}")

    /** 老师讲到这些词，大概率是要考的。 */
    private val KEYWORDS = listOf(
        "重点", "考点", "必考", "必背", "要考", "考到", "记住", "记一下", "记笔记", "记下来",
        "注意", "关键", "核心", "划重点", "总结一下", "总结", "考试", "公式", "定理", "定义",
        "易错", "容易错", "坑", "复习", "作业", "布置", "第一章", "第二章", "第三章",
        "第一点", "第二点", "第三点", "第四个", "重点掌握", "掌握"
    )

    private const val PUNCT = "。，！？；：、,.!?;:"

    /** 去掉 <|Speech|><|NEUTRAL|> 之类的标记，并对齐标点 / 语气词。 */
    fun polish(raw: String): String {
        var s = TAG_RE.replace(raw, "")
        s = s.trim()
        if (s.isEmpty()) return ""
        s = MULTI_SPACE.replace(s, " ")
        s = REPEAT_PUNCT.replace(s) { it.groupValues[1] }
        s = FILLER_HEAD.replace(s, "")
        s = FILLER_TAIL.replace(s, "")
        s = s.trim().trimStart(*PUNCT.toCharArray()).trim()
        if (s.isEmpty()) return ""
        // 长句没有句末标点时补一个，笔记看起来更像人写的
        if (s.length >= 10 && !PUNCT.contains(s.last())) s += "。"
        return s
    }

    fun isNoise(text: String): Boolean {
        if (text.length < 2) return true
        val meaningful = text.count { it.isLetterOrDigit() || it.code > 0x2E80 }
        if (meaningful < 2) return true
        // 整句都是重复的单字，例如「哈哈哈哈」
        return text.toSet().size <= 2 && text.length <= 4
    }

    fun isImportant(text: String): Boolean = KEYWORDS.any { text.contains(it) }

    /** 去掉重复的单字，例如「这这这个」→「这个」，识别器偶尔会复读。 */
    fun dedup(text: String): String {
        if (text.length < 4) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            var j = i
            while (j < text.length && text[j] == c) j++
            val run = j - i
            if (c.code > 0x2E80 && run >= 3) sb.append(c) else repeat(run) { sb.append(c) }
            i = j
        }
        return sb.toString()
    }
}