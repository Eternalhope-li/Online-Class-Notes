package com.lecture.notes.util

/**
 * 整理稿排版引擎的「解析层」。
 *
 * 只认整理稿真正会产出的那部分 Markdown：标题、段落、要点、编号、引用、表格、
 * 代码块、分隔线、关键词行。刻意不做通用 Markdown 解析，解析结果同时喂给两条链路：
 *
 *   ui/NoteRenderer.kt   渲染成手机上看的排版稿（标题徽标、要点圆点、时间戳标签、表格卡片）
 *   MiniMarkdown.toHtml  渲染成自带样式的 HTML（导出网页 / 复制到 Word、WPS 里保留排版）
 *
 * 这里不引用任何 Android 类，纯 Kotlin，方便用 JVM 单测覆盖。
 */
object MiniMarkdown {

    /** 引用块的语气，决定渲染时的配色（普通提示 / 重点警告 / 结论小结）。 */
    enum class Callout { INFO, WARN, OK }

    sealed class Block {
        data class Heading(val level: Int, val text: String) : Block()
        data class Para(val text: String) : Block()
        data class Bullet(val text: String, val depth: Int) : Block()
        data class Ordered(val marker: String, val text: String, val depth: Int) : Block()
        data class Quote(val text: String, val kind: Callout) : Block()
        data class Table(val header: List<String>, val rows: List<List<String>>) : Block()
        data class Code(val lines: List<String>) : Block()
        data class Chips(val items: List<String>) : Block()
        /** 整理稿里的截图：`![alt](shots/xxx.jpg)`。 */
        data class Image(val src: String, val alt: String) : Block()
        object Rule : Block()
    }

    /** 行内片段。时间戳和行内代码在界面上会渲染成小胶囊。 */
    sealed class Span {
        data class Text(val text: String) : Span()
        data class Bold(val text: String) : Span()
        data class Code(val text: String) : Span()
        data class Time(val text: String) : Span()
        object Star : Span()
        /** 要点行中间的截图 `![alt](shots/x.jpg)`：导出时要变成真的图片标签。 */
        data class Image(val src: String, val alt: String) : Span()
    }

    private val HEAD_RE = Regex("^(#{1,6})\\s+(.*)$")
    private val RULE_RE = Regex("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$")
    private val BULLET_RE = Regex("^(\\s*)[-*+•]\\s+(.*)$")
    private val ORDERED_RE = Regex("^(\\s*)(?:(\\d{1,2})[.、)）]|[（(](\\d{1,2})[)）])\\s*(.*)$")
    private val DOTTED_RE = Regex("^\\s*(\\d+(?:\\.\\d+)+)\\.?\\s+(.*)$")
    private val ORDINAL_RE = Regex("^\\s*(?:第\\s*)?([一二三四五六七八九十]+|\\d{1,2})\\s*[、.．)）:：]\\s*(.*)$")
    private val TIME_RE = Regex("\\[\\d{1,3}:\\d{2}\\]")
    private val TABLE_SEP_RE = Regex("^\\s*\\|?\\s*:?-{1,}:?\\s*(?:\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$")
    private val IMAGE_RE = Regex("^!\\[(.*?)]\\((.*?)\\)$")
    /** 行内出现的截图（要点行中间那种），只在行内解析里用。 */
    private val INLINE_IMAGE_RE = Regex("!\\[(.*?)]\\((.*?)\\)")

    // ------------------------------------------------------------------ 解析

    fun parse(md: String): List<Block> {
        val out = ArrayList<Block>()
        val lines = md.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val para = StringBuilder()

        fun flushPara() {
            val text = para.toString().trim()
            para.setLength(0)
            if (text.isEmpty()) return
            val chips = chipItems(text)
            if (chips != null && chips.size >= 2) out.add(Block.Chips(chips)) else out.add(Block.Para(text))
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trimEnd()
            if (line.isBlank()) {
                flushPara()
                i++
                continue
            }

            if (line.trimStart().startsWith("```")) {
                flushPara()
                i++
                val buf = ArrayList<String>()
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    buf.add(lines[i].trimEnd())
                    i++
                }
                if (i < lines.size) i++
                out.add(Block.Code(buf))
                continue
            }

            val h = HEAD_RE.find(line)
            if (h != null) {
                flushPara()
                out.add(Block.Heading(h.groupValues[1].length, h.groupValues[2].trim()))
                i++
                continue
            }

            val standalone = IMAGE_RE.find(line.trim())
            if (standalone != null) {
                flushPara()
                out.add(Block.Image(standalone.groupValues[2].trim(), standalone.groupValues[1].trim()))
                i++
                continue
            }

            if (RULE_RE.matches(line)) {
                flushPara()
                out.add(Block.Rule)
                i++
                continue
            }

            if (line.trimStart().startsWith(">")) {
                flushPara()
                val buf = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    val t = lines[i].trimStart().removePrefix(">").trim()
                    if (buf.isNotEmpty()) buf.append(' ')
                    buf.append(t)
                    i++
                }
                val text = buf.toString().trim()
                out.add(Block.Quote(text, calloutOf(text)))
                continue
            }

            if (line.trimStart().startsWith("|") && i + 1 < lines.size &&
                TABLE_SEP_RE.matches(lines[i + 1].trimEnd()) && lines[i + 1].contains('-')
            ) {
                flushPara()
                val header = cells(line)
                val rows = ArrayList<List<String>>()
                i += 2
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    val r = cells(lines[i])
                    if (r.isNotEmpty()) rows.add(r)
                    i++
                }
                out.add(Block.Table(header, rows))
                continue
            }

            val b = BULLET_RE.find(line)
            if (b != null) {
                flushPara()
                val body = b.groupValues[2].trim()
                val img = IMAGE_RE.find(body)
                if (img != null) {
                    out.add(Block.Image(img.groupValues[2].trim(), img.groupValues[1].trim()))
                } else {
                    out.add(Block.Bullet(body, depthOf(b.groupValues[1])))
                }
                i++
                continue
            }

            if (!line.trimStart().startsWith("**")) {
                val o = ORDERED_RE.find(line)
                if (o != null) {
                    flushPara()
                    val num = o.groupValues[2].ifEmpty { o.groupValues[3] }
                    out.add(Block.Ordered(num, o.groupValues[4].trim(), depthOf(o.groupValues[1])))
                    i++
                    continue
                }
            }

            if (para.isNotEmpty()) para.append(' ')
            para.append(line.trim())
            i++
        }
        flushPara()
        return out
    }

    private fun cells(line: String): List<String> {
        val s = line.trim().removePrefix("|").removeSuffix("|")
        return s.split('|').map { it.trim() }
    }

    private fun depthOf(indent: String): Int = (indent.replace("\t", "  ").length / 2).coerceIn(0, 3)

    private fun calloutOf(text: String): Callout = when {
        containsAny(text, "注意", "易错", "必考", "重点", "警告", "不要", "别忘") -> Callout.WARN
        containsAny(text, "一句话总结", "小结", "总结", "结论", "记住", "核心") -> Callout.OK
        else -> Callout.INFO
    }

    private fun containsAny(s: String, vararg keys: String): Boolean = keys.any { s.contains(it) }

    /** 整行都是行内代码（如「`二叉树` `遍历` `存储`」）就当成关键词胶囊行。 */
    private fun chipItems(text: String): List<String>? {
        var i = 0
        val items = ArrayList<String>()
        val rest = StringBuilder()
        while (i < text.length) {
            if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    items.add(text.substring(i + 1, end).trim())
                    i = end + 1
                    continue
                }
            }
            rest.append(text[i])
            i++
        }
        if (items.size < 2 || !rest.isBlank()) return null
        return items
    }

    // ------------------------------------------------------------------ 行内

    fun inline(text: String): List<Span> {
        val out = ArrayList<Span>()
        val sb = StringBuilder()

        fun flush() {
            if (sb.isNotEmpty()) {
                out.add(Span.Text(sb.toString()))
                sb.setLength(0)
            }
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    val v = text.substring(i + 1, end).trim()
                    // 整理稿里的时间戳是写成 `[03:12]` 的：单独认出来，渲染成彩色胶囊
                    if (TIME_RE.matchEntire(v) != null) {
                        flush()
                        out.add(Span.Time(v))
                    } else if (v.isNotEmpty()) {
                        flush()
                        out.add(Span.Code(v))
                    }
                    i = end + 1
                    continue
                }
            }
            if (c == '*' && i + 1 < text.length && text[i + 1] == '*') {
                val end = text.indexOf("**", i + 2)
                if (end > i + 1) {
                    flush()
                    val v = text.substring(i + 2, end).trim()
                    if (v.isNotEmpty()) out.add(Span.Bold(v))
                    i = end + 2
                    continue
                }
            }
            if (c == '!' && i + 1 < text.length && text[i + 1] == '[') {
                val m = INLINE_IMAGE_RE.find(text, i)
                if (m != null && m.range.first == i) {
                    flush()
                    out.add(Span.Image(m.groupValues[2].trim(), m.groupValues[1].trim()))
                    i = m.range.last + 1
                    continue
                }
            }
            if (c == '[') {
                val m = TIME_RE.find(text, i)
                if (m != null && m.range.first == i) {
                    flush()
                    out.add(Span.Time(m.value))
                    i = m.range.last + 1
                    continue
                }
            }
            if (c == '★') {
                flush()
                out.add(Span.Star)
                i++
                continue
            }
            sb.append(c)
            i++
        }
        flush()
        return out
    }

    /** 行内片段的纯文本形式（复制、分享兜底）。 */
    fun inlinePlain(text: String): String = buildString {
        for (s in inline(text)) when (s) {
            is Span.Text -> append(s.text)
            is Span.Bold -> append(s.text)
            is Span.Code -> append('`').append(s.text).append('`')
            is Span.Time -> append(s.text)
            Span.Star -> append('★')
            is Span.Image -> append("![").append(s.alt).append("](").append(s.src).append(")")
        }
    }

    /** 把「一、二叉树的定义」拆成「一」和「二叉树的定义」；没有序号就返回 null。 */
    fun splitOrdinal(text: String): Pair<String?, String> {
        val t = text.trim()
        val d = DOTTED_RE.find(t)
        if (d != null) return d.groupValues[1] to d.groupValues[2].trim()
        val o = ORDINAL_RE.find(t)
        if (o != null) {
            val rest = o.groupValues[2].trim()
            if (rest.isNotEmpty()) return o.groupValues[1] to rest
        }
        return null to t
    }

    // ------------------------------------------------------------------ HTML

    fun toHtml(md: String, title: String): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"utf-8\">\n")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        sb.append("<title>").append(esc(title)).append("</title>\n<style>\n").append(CSS)
        sb.append("\n</style>\n</head>\n<body>\n<article class=\"note\">\n")

        val blocks = parse(md)
        var i = 0
        while (i < blocks.size) {
            val b = blocks[i]
            when (b) {
                is Block.Bullet -> {
                    val buf = ArrayList<String>()
                    while (i < blocks.size && blocks[i] is Block.Bullet) {
                        val x = blocks[i] as Block.Bullet
                        buf.add("<li class=\"d" + x.depth + "\">" + inlineHtml(x.text) + "</li>")
                        i++
                    }
                    sb.append("<ul>\n").append(buf.joinToString("\n")).append("\n</ul>\n")
                }
                is Block.Ordered -> {
                    val buf = ArrayList<String>()
                    while (i < blocks.size && blocks[i] is Block.Ordered) {
                        val x = blocks[i] as Block.Ordered
                        buf.add("<li class=\"d" + x.depth + "\">" + inlineHtml(x.text) + "</li>")
                        i++
                    }
                    sb.append("<ol>\n").append(buf.joinToString("\n")).append("\n</ol>\n")
                }
                is Block.Quote -> {
                    val buf = ArrayList<String>()
                    var kind = Callout.INFO
                    while (i < blocks.size && blocks[i] is Block.Quote) {
                        val x = blocks[i] as Block.Quote
                        if (x.kind == Callout.WARN) kind = Callout.WARN
                        buf.add(inlineHtml(x.text))
                        i++
                    }
                    sb.append("<blockquote class=\"").append(kindClass(kind)).append("\">")
                        .append(buf.joinToString("<br>")).append("</blockquote>\n")
                }
                is Block.Heading -> {
                    sb.append("<h").append(b.level).append(">").append(inlineHtml(b.text))
                        .append("</h").append(b.level).append(">\n")
                    i++
                }
                is Block.Para -> {
                    sb.append("<p>").append(inlineHtml(b.text)).append("</p>\n")
                    i++
                }
                is Block.Table -> {
                    sb.append("<table>\n<thead><tr>")
                    for (c in b.header) sb.append("<th>").append(inlineHtml(c)).append("</th>")
                    sb.append("</tr></thead>\n<tbody>\n")
                    for (r in b.rows) {
                        sb.append("<tr>")
                        for (c in r) sb.append("<td>").append(inlineHtml(c)).append("</td>")
                        sb.append("</tr>\n")
                    }
                    sb.append("</tbody>\n</table>\n")
                    i++
                }
                is Block.Code -> {
                    sb.append("<pre><code>")
                    sb.append(b.lines.joinToString("\n") { esc(it) })
                    sb.append("</code></pre>\n")
                    i++
                }
                is Block.Chips -> {
                    sb.append("<p class=\"chips\">")
                    for (c in b.items) sb.append("<span class=\"chip\">").append(esc(c)).append("</span>")
                    sb.append("</p>\n")
                    i++
                }
                is Block.Image -> {
                    sb.append("<figure class=\"shot\"><img src=\"").append(esc(b.src)).append("\" alt=\"")
                        .append(esc(b.alt)).append("\">")
                    if (b.alt.isNotBlank()) sb.append("<figcaption>").append(esc(b.alt)).append("</figcaption>")
                    sb.append("</figure>\n")
                    i++
                }
                Block.Rule -> {
                    sb.append("<hr>\n")
                    i++
                }
            }
        }
        sb.append("</article>\n</body>\n</html>\n")
        return sb.toString()
    }

    private fun kindClass(k: Callout): String = when (k) {
        Callout.WARN -> "warn"
        Callout.OK -> "ok"
        Callout.INFO -> "info"
    }

    private fun inlineHtml(text: String): String = buildString {
        for (s in inline(text)) when (s) {
            is Span.Text -> append(esc(s.text))
            is Span.Bold -> append("<strong>").append(esc(s.text)).append("</strong>")
            is Span.Code -> append("<code>").append(esc(s.text)).append("</code>")
            is Span.Time -> append("<span class=\"ts\">").append(esc(s.text)).append("</span>")
            Span.Star -> append("<span class=\"star\">★</span>")
            is Span.Image -> append("<img class=\"shot-inline\" src=\"")
                .append(esc(s.src)).append("\" alt=\"").append(esc(s.alt)).append("\">")
        }
    }

    /** 去掉排版记号，得到一个干净的纯文本稿（分享 / 复制兜底）。 */
    fun plain(md: String): String {
        val sb = StringBuilder()
        for (b in parse(md)) {
            when (b) {
                is Block.Heading -> {
                    if (b.level <= 1) sb.append('\n').append(inlinePlain(b.text)).append('\n')
                    else if (b.level == 2) sb.append('\n').append("【").append(inlinePlain(b.text)).append("】\n")
                    else sb.append("  · ").append(inlinePlain(b.text)).append('\n')
                }
                is Block.Para -> sb.append(inlinePlain(b.text)).append('\n')
                is Block.Bullet -> sb.append("  ".repeat(b.depth)).append("• ").append(inlinePlain(b.text)).append('\n')
                is Block.Ordered -> sb.append("  ".repeat(b.depth)).append(b.marker).append(". ")
                    .append(inlinePlain(b.text)).append('\n')
                is Block.Quote -> sb.append("｜ ").append(inlinePlain(b.text)).append('\n')
                is Block.Table -> {
                    sb.append(b.header.joinToString(" ｜ ") { inlinePlain(it) }).append('\n')
                    for (r in b.rows) sb.append(r.joinToString(" ｜ ") { inlinePlain(it) }).append('\n')
                }
                is Block.Code -> b.lines.forEach { sb.append(it).append('\n') }
                is Block.Chips -> sb.append(b.items.joinToString(" ")).append('\n')
                is Block.Image -> sb.append("[图片] ").append(inlinePlain(b.alt)).append('\n')
                Block.Rule -> sb.append("――――――――\n")
            }
        }
        return sb.toString().trim() + "\n"
    }

    fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }

    private val CSS = """
        :root { color-scheme: light dark; }
        body { margin: 0; background: #f5f6fa; color: #1b1b1f;
               font: 16px/1.75 -apple-system, "Segoe UI", "Microsoft YaHei", "PingFang SC", sans-serif; }
        .note { max-width: 780px; margin: 0 auto; padding: 32px 28px 64px; background: #fff; }
        h1 { font-size: 26px; line-height: 1.35; margin: 0 0 10px; padding-bottom: 12px;
             border-bottom: 3px solid #4353E8; }
        h2 { font-size: 20px; margin: 34px 0 12px; padding-left: 12px; border-left: 5px solid #4353E8; }
        h3 { font-size: 17px; margin: 22px 0 8px; color: #2A37B8; }
        h4 { font-size: 15px; margin: 16px 0 6px; color: #555; }
        p { margin: 8px 0; }
        ul, ol { margin: 8px 0 8px 4px; padding-left: 22px; }
        li { margin: 5px 0; }
        li.d1 { margin-left: 18px; } li.d2 { margin-left: 36px; } li.d3 { margin-left: 54px; }
        code { font-family: "Cascadia Mono", Consolas, monospace; font-size: 13.5px;
               background: #eef0f7; padding: 1px 6px; border-radius: 5px; }
        .ts { font-family: "Cascadia Mono", Consolas, monospace; font-size: 13px; font-weight: 600;
               color: #4353E8; background: #e8eaff; padding: 1px 7px; border-radius: 9px; }
        .star { color: #F2A03D; font-weight: 700; }
        blockquote { margin: 14px 0; padding: 12px 16px; border-left: 5px solid #4353E8;
                     background: #f2f3fd; border-radius: 0 10px 10px 0; }
        blockquote.warn { border-left-color: #F2A03D; background: #fdf5e9; }
        blockquote.ok { border-left-color: #2E9E6B; background: #eaf7f1; }
        table { width: 100%; border-collapse: collapse; margin: 14px 0; font-size: 14.5px; }
        th, td { border: 1px solid #dfe1ea; padding: 9px 12px; text-align: left; vertical-align: top; }
        th { background: #eef0f7; font-weight: 600; }
        tr:nth-child(even) td { background: #fafbfe; }
        pre { background: #f4f5fa; border-radius: 10px; padding: 14px; overflow-x: auto; }
        pre code { background: none; padding: 0; }
        .chip { display: inline-block; margin: 3px 6px 3px 0; padding: 3px 12px; border-radius: 999px;
                background: #e8eaff; color: #2A37B8; font-size: 13.5px; }
        hr { border: none; border-top: 1px solid #e3e5ee; margin: 26px 0; }
        figure.shot { margin: 16px 0; padding: 10px; background: #f4f5fa; border-radius: 12px; text-align: center; }
        figure.shot img { max-width: 100%; height: auto; border-radius: 8px; border: 1px solid #dfe1ea; }
        figure.shot figcaption { margin-top: 8px; font-size: 13px; color: #6b6c76; }
        img.shot-inline { display: block; max-width: 100%; height: auto; margin: 10px 0; border-radius: 10px; border: 1px solid #dfe1ea; }
        @media (prefers-color-scheme: dark) {
            body { background: #121216; color: #e6e6ea; }
            .note { background: #1b1b21; }
            code { background: #2a2b33; }
            .ts { background: #29418a; color: #cfd6ff; }
            h2 { border-left-color: #8b96ff; } h3 { color: #a9b2ff; }
            h1 { border-bottom-color: #8b96ff; }
            blockquote { background: #232434; } blockquote.warn { background: #3a3123; }
            blockquote.ok { background: #1f3329; }
            th, td { border-color: #33343d; } th { background: #26272f; }
            tr:nth-child(even) td { background: #202127; }
            pre { background: #26272f; } .chip { background: #29418a; color: #cfd6ff; }
            hr { border-top-color: #33343d; }
            figure.shot { background: #26272f; }
            figure.shot img { border-color: #33343d; }
            figure.shot figcaption { color: #9a9aa5; }
            img.shot-inline { border-color: #33343d; }
        }
        @media print { body { background: #fff; } .note { max-width: none; padding: 0; } }
    """.trimIndent()
}
