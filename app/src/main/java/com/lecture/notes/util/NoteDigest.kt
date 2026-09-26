package com.lecture.notes.util

import com.lecture.notes.data.Entry
import com.lecture.notes.data.Note
import com.lecture.notes.data.NoteStore
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * 离线「知识点笔记」生成器：把逐句转写整理成提纲式笔记。
 *
 * 三步走：按时间和话题分段 -> 每段挑信息量最高的句子当要点 -> 抽术语当小标题。
 * 全部在本地算，不联网，几千句的笔记也是毫秒级，录完立刻能看。
 */
object NoteDigest {

    data class Section(
        val startMs: Long,
        val endMs: Long,
        val title: String,
        val bullets: List<Entry>,
        val terms: List<String>
    )

    data class Result(
        val sections: List<Section>,
        val keywords: List<String>,
        val highlights: List<Entry>,
        val rawCount: Int,
        val keptCount: Int
    )

    private const val MAX_SECTION_CHARS = 300
    private const val GAP_MS = 10_000L
    private const val MAX_SECTIONS = 44
    private const val MIN_LEN = 6

    /** 每句话之间插的分隔符，避免 n-gram 跨句乱组词。 */
    private const val SEP = '\u0001'

    private val CN_NUM = listOf(
        "一", "二", "三", "四", "五", "六", "七", "八", "九", "十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十"
    )

    /** 这些字出现在词组里基本就是虚词，抽术语时直接跳过。 */
    private const val FUNC_CHARS = "的了是在和与或就都也很不我你他她它们个这那什么怎样吗呢吧啊哦嗯呀嘛之其此该等有"

    /** 常见口语组合，避免被当成术语。 */
    private val BAD_TERMS = setOf(
        "这个", "那个", "什么", "怎么", "然后", "就是", "所以", "因为", "但是", "如果",
        "可以", "这样", "那样", "现在", "时候", "我们", "你们", "他们", "大家", "同学",
        "老师", "一下", "这些", "那些", "这里", "那里", "已经", "还是", "知道", "觉得",
        "应该", "可能", "需要", "进行", "或者", "以及", "而且", "不过", "还有", "那么",
        "其实", "真的", "非常", "比较", "一定", "来看", "看一下", "对不对", "是不是",
        "有没有", "怎么样", "为什么", "一点点", "有一点点", "比方说", "比如说", "对吧",
        "今天", "这次", "上次", "下次", "接着", "内容", "部分", "地方", "东西", "情况",
        "第一个", "第二个", "第三个", "最后一个", "主要", "直接", "具体", "一般",
        "一个", "一种", "两种", "三个", "三种", "四个", "四种", "五个", "两种方法"
    )

    private val EN_STOP = setOf(
        "the", "and", "for", "you", "that", "this", "with", "are", "was", "not",
        "but", "his", "her", "its", "our", "their", "have", "has", "had", "will",
        "can", "could", "would", "should", "there", "from", "they", "what", "when"
    )

    /** 出现这些词，说明这句大概率是个知识点：定义、因果、列举、考点。 */
    private val SIGNAL_WORDS = listOf(
        "叫做", "称为", "是指", "意思", "定义", "概念", "含义", "包括", "分为", "分成",
        "组成", "结构", "原理", "公式", "定理", "定律", "结论", "条件", "步骤", "方法",
        "因为", "所以", "因此", "由于", "导致", "结果", "原因", "作用", "功能", "特点",
        "优点", "缺点", "区别", "联系", "性质", "首先", "其次", "最后", "例如", "比如",
        "注意", "重点", "关键", "记住", "易错", "考点", "考试", "总结", "小结",
        "掌握", "理解", "计算", "证明", "推导", "应用", "目的是", "也就是说"
    )

    /** 定义类句子，整理笔记时最不能丢。 */
    private val DEF_WORDS = listOf(
        "是指", "叫做", "称为", "定义", "概念", "含义", "分为", "分成", "包括",
        "组成", "公式", "定理", "定律", "结论", "性质"
    )

    /** 这些是学生真正要抄下来的东西，分数再低也必须留下。 */
    private val MUST_KEEP = listOf(
        "作业", "考试", "考点", "必考", "布置", "划重点", "记住", "记一下", "记下来",
        "公式", "定理", "定义", "结论", "是指", "叫做", "称为"
    )
    /** 句首出现这些词，说明老师还在铺垫，不算知识点。 */
    private val LEAD_WORDS = listOf(
        "那么", "然后", "这个", "那个", "我们来看", "我们看", "来看", "接下来",
        "下面", "大家", "同学们", "对不对", "是不是", "好吧", "OK", "ok",
        "上次", "刚才", "嗯", "呃", "啊", "好"
    )

    /** 老师要翻篇的信号，遇到就另起一节。 */
    private val TURN_WORDS = listOf(
        "接下来", "下一个", "接下来我们", "下面我们", "再来看", "再来看一下",
        "第一点", "第二点", "第三点", "第四点", "第一", "第二", "第三", "第四", "第五", "第六",
        "首先", "其次", "最后一点", "另外一点", "总结一下", "小结一下", "回顾一下", "复习一下",
        "我们接着", "接着说", "我们讲", "下面讲", "下面说", "我们来说", "我们来看一个",
        "最后一个部分", "最后我们讲", "最后讲", "最后说", "再说一个", "还有一点"
    )

    private val EN_WORD = Regex("[A-Za-z][A-Za-z0-9_+#.-]{1,}")

    // ------------------------------------------------------------------ 对外

    /** 生成知识点笔记的 Markdown 全文。 */
    fun build(note: Note): Pair<String, Result> {
        val r = analyze(note)
        return toMarkdown(note, r) to r
    }

    fun analyze(note: Note): Result {
        val raw = note.entries.filter { it.text.isNotBlank() }
        val cleaned = ArrayList<Entry>(raw.size)
        for (e in raw) {
            val t = TextPolish.dedup(e.text.trim())
            if (t.isEmpty()) continue
            if (!e.star && TextPolish.isNoise(t)) continue
            cleaned.add(Entry(e.atMs, t, e.star))
        }

        val terms = terms(cleaned, 14)
        val termSet = terms.toHashSet()

        var cap = MAX_SECTION_CHARS
        var groups = split(cleaned, cap)
        while (groups.size > MAX_SECTIONS && cap < 6000) {
            cap = cap * 3 / 2
            groups = split(cleaned, cap)
        }

        val sections = ArrayList<Section>(groups.size)
        var kept = 0
        for (g in groups) {
            val sec = shrink(g, termSet) ?: continue
            sections.add(sec)
            kept += sec.bullets.size
        }

        val highlights = cleaned.filter { it.star }.take(40)
        return Result(sections, terms, highlights, raw.size, kept)
    }

    fun toMarkdown(note: Note, r: Result): String {
        val sb = StringBuilder()
        sb.append("# ").append(note.title).append("\n\n")
        sb.append("> ").append(Formats.dateTime(note.createdAt))
            .append("　时长 ").append(Formats.hms(note.durationMs))
            .append("　原文 ").append(r.rawCount).append(" 句 → ")
            .append(r.sections.size).append(" 节 / ").append(r.keptCount).append(" 个要点")
            .append("（本地离线整理）\n\n")

        if (r.keywords.isNotEmpty()) {
            sb.append("## 本课关键词\n\n")
            for (k in r.keywords) sb.append('`').append(k).append("` ")
            sb.append("\n\n")
        }

        if (r.highlights.isNotEmpty()) {
            sb.append("## 重点回顾\n\n")
            for (e in r.highlights) {
                sb.append("- `[").append(Formats.mmss(e.atMs)).append("]` ").append(e.text).append('\n')
            }
            sb.append('\n')
        }

        if (r.sections.isEmpty()) {
            sb.append("_这篇记录还没有足够的内容可以整理。_\n")
            sb.append(NoteStore.shotsSection(note))
            return sb.toString()
        }

        r.sections.forEachIndexed { i, sec ->
            sb.append("## ").append(CN_NUM.getOrElse(i) { "${i + 1}" }).append("、").append(sec.title)
            sb.append("　`").append(Formats.mmss(sec.startMs)).append("-")
                .append(Formats.mmss(sec.endMs)).append("`\n\n")
            for (e in sec.bullets) {
                sb.append("- `[").append(Formats.mmss(e.atMs)).append("]` ")
                if (e.star) sb.append("**★** ")
                sb.append(e.text).append('\n')
            }
            sb.append('\n')
        }
        sb.append(NoteStore.shotsSection(note))
        return sb.toString().trim() + "\n"
    }

    // ------------------------------------------------------------------ 分段

    private fun split(entries: List<Entry>, maxChars: Int): List<List<Entry>> {
        val out = ArrayList<List<Entry>>()
        var cur = ArrayList<Entry>()
        var chars = 0
        var lastAt = 0L
        for (e in entries) {
            if (cur.isNotEmpty()) {
                val gap = e.atMs - lastAt > GAP_MS
                val full = chars >= maxChars
                val turn = chars >= 80 && isTurn(e.text)
                if (gap || full || turn) {
                    out.add(cur)
                    cur = ArrayList()
                    chars = 0
                }
            }
            cur.add(e)
            chars += e.text.length
            lastAt = e.atMs
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    private fun isTurn(s: String): Boolean =
        s.length >= 8 && TURN_WORDS.any { s.startsWith(it) }

    /** 把一段压成「标题 + 要点」，整段都是废话就返回 null。 */
    private fun shrink(items: List<Entry>, terms: Set<String>): Section? {
        val scored = ArrayList<Pair<Int, Double>>(items.size)
        for ((i, e) in items.withIndex()) {
            if (e.text.length < MIN_LEN && !e.star) continue
            scored.add(i to score(e, terms))
        }
        if (scored.isEmpty()) return null

        val best = scored.maxOf { it.second }
        if (best < 1.0 && items.none { it.star }) return null

        val chars = items.sumOf { it.text.length }
        val cap = (chars / 70).coerceIn(3, 9)
        val cutoff = (best * 0.42).coerceAtLeast(0.9)

        val picked = ArrayList<Pair<Int, Entry>>()
        fun take(i: Int) {
            val e = items[i]
            if (picked.any { it.first == i }) return
            if (picked.any { similar(it.second.text, e.text) }) return
            picked.add(i to e)
        }

        for ((i, sc) in scored.sortedByDescending { it.second }) {
            if (picked.size >= cap) break
            if (sc < cutoff) break
            take(i)
        }

        // 作业 / 公式 / 定义这些，宁可多留也不放过
        var forced = 0
        for ((i, e) in items.withIndex()) {
            if (forced >= 2) break
            if (e.text.length < MIN_LEN && !e.star) continue
            if (picked.any { it.first == i }) continue
            if (!MUST_KEEP.any { e.text.contains(it) }) continue
            val before = picked.size
            take(i)
            if (picked.size > before) forced++
        }

        if (picked.isEmpty()) return null
        picked.sortBy { it.first }
        while (picked.size > cap + 2) picked.removeAt(picked.size - 1)

        val bullets = picked.map { it.second }
        val secTerms = terms.filter { t -> items.any { it.text.contains(t) } }
            .sortedByDescending { t -> items.count { it.text.contains(t) } * t.length }
            .take(2)
        val title = if (secTerms.isNotEmpty()) secTerms.joinToString(" · ")
        else bullets.first().text.take(14).trimEnd('，', '。', '、') + "…"

        return Section(
            startMs = items.first().atMs,
            endMs = items.last().atMs,
            title = title,
            bullets = bullets,
            terms = secTerms
        )
    }

    // ------------------------------------------------------------------ 打分

    private fun score(e: Entry, terms: Set<String>): Double {
        val s = e.text
        var sc = s.length.coerceAtMost(45) / 45.0 * 1.8
        if (s.length < 10) sc -= 1.6
        if (s.length > 90) sc -= 0.5

        var hits = 0
        for (t in terms) if (s.contains(t)) hits++
        sc += hits.coerceAtMost(4) * 0.8

        if (SIGNAL_WORDS.any { s.contains(it) }) sc += 1.4
        if (DEF_WORDS.any { s.contains(it) }) sc += 1.0
        if (s.any { it.isDigit() }) sc += 0.5
        if (isLead(s)) sc -= 1.0
        if (e.star) sc += 1.5
        return sc
    }

    private fun isLead(s: String): Boolean {
        for (w in LEAD_WORDS) {
            if (!s.startsWith(w)) continue
            if (w.length >= 2) return true
            val next = s.getOrNull(w.length)
            if (next == null || !next.isLetterOrDigit()) return true
        }
        return false
    }

    // ------------------------------------------------------------------ 术语

    /**
     * 抽取高频术语。
     *
     * 光看词频会把「中序遍」「储结构」这种半个词抽出来，所以还要看左右邻接字的熵：
     * 真词左右两边接什么都不固定（熵高），碎片往往只接同一个字（熵为 0），直接淘汰。
     */
    fun terms(entries: List<Entry>, top: Int): List<String> {
        if (entries.isEmpty()) return emptyList()
        val text = entries.joinToString(SEP.toString()) { it.text }
        val chars = text.toCharArray()

        val counts = HashMap<String, Int>()
        val lefts = HashMap<String, HashMap<Char, Int>>()
        val rights = HashMap<String, HashMap<Char, Int>>()

        for (n in 2..4) {
            var i = 0
            while (i + n <= chars.size) {
                var ok = true
                for (k in i until i + n) {
                    val c = chars[k]
                    if (c.code < 0x4E00 || c.code > 0x9FFF || FUNC_CHARS.indexOf(c) >= 0) {
                        ok = false
                        break
                    }
                }
                if (ok) {
                    val g = String(chars, i, n)
                    counts[g] = (counts[g] ?: 0) + 1
                    if (i > 0) {
                        val m = lefts.getOrPut(g) { HashMap() }
                        m[chars[i - 1]] = (m[chars[i - 1]] ?: 0) + 1
                    }
                    if (i + n < chars.size) {
                        val m = rights.getOrPut(g) { HashMap() }
                        m[chars[i + n]] = (m[chars[i + n]] ?: 0) + 1
                    }
                }
                i++
            }
        }

        for (m in EN_WORD.findAll(text)) {
            val g = m.value
            if (g.length > 24 || EN_STOP.contains(g.lowercase())) continue
            counts[g] = (counts[g] ?: 0) + 2
        }

        val cand = ArrayList<Pair<String, Double>>()
        for ((g, c) in counts) {
            if (c < 2 || g.length > 8 || BAD_TERMS.contains(g)) continue
            val l = entropy(lefts[g])
            val r = entropy(rights[g])
            val boundary = min(l, r)
            // 出现得少就必须边界干净；出现得多可以宽松一点
            if (c < 4 && boundary < 0.9) continue
            if (boundary < 0.45) continue
            cand.add(g to c * (1.0 + 0.4 * (g.length - 2)) * (0.5 + boundary))
        }
        cand.sortByDescending { it.second }

        val picked = ArrayList<String>()
        for ((g, _) in cand) {
            if (picked.size >= top) break
            if (picked.any { it.contains(g) || g.contains(it) || edgeOverlap(it, g) }) continue
            // 如果某个「稍长一点」的候选出现次数和它差不多，说明它只是个词头，让长的上
            val cg = counts[g] ?: 1
            var shadowed = false
            for ((h, hc) in counts) {
                if (h.length <= g.length || h.length > g.length + 2) continue
                if (!h.contains(g)) continue
                if (hc >= max(2, (cg * 0.5).toInt())) {
                    shadowed = true
                    break
                }
            }
            if (shadowed) continue
            picked.add(g)
        }
        return picked
    }

    /**
     * 「讲二叉」和「二叉树」互相不包含，但一个是另一个错位一格的滑窗，
     * 这种边缘重叠说明其中一个是半个词，只留分数高的那个。
     */
    private fun edgeOverlap(a: String, b: String): Boolean {
        val k = minOf(a.length, b.length) - 1
        if (k < 2) return false
        return a.takeLast(k) == b.take(k) || a.take(k) == b.takeLast(k)
    }
    private fun entropy(m: HashMap<Char, Int>?): Double {
        if (m == null || m.isEmpty()) return 0.0
        var total = 0
        for (v in m.values) total += v
        if (total <= 0) return 0.0
        var h = 0.0
        for (v in m.values) {
            val p = v.toDouble() / total
            h -= p * (ln(p) / ln(2.0))
        }
        return h
    }

    // ------------------------------------------------------------------ 工具

    private fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        val sa = grams(a)
        val sb = grams(b)
        if (sa.isEmpty() || sb.isEmpty()) return false
        var inter = 0
        for (x in sa) if (sb.contains(x)) inter++
        val union = sa.size + sb.size - inter
        return union > 0 && inter.toDouble() / union >= 0.62
    }

    private fun grams(s: String): Set<String> {
        val out = HashSet<String>()
        for (i in 0 until s.length - 1) {
            val c0 = s[i]
            val c1 = s[i + 1]
            if (c0.isWhitespace() || c1.isWhitespace()) continue
            out.add(String(charArrayOf(c0, c1)))
        }
        return out
    }
}
