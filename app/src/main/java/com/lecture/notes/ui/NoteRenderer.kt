package com.lecture.notes.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import com.lecture.notes.R
import com.lecture.notes.util.MiniMarkdown
import com.lecture.notes.util.MiniMarkdown.Block
import com.lecture.notes.util.MiniMarkdown.Callout
import com.lecture.notes.util.MiniMarkdown.Span
import com.lecture.notes.util.Thumbs
import kotlin.math.max
import kotlin.math.min

/**
 * 把整理稿渲染成「排版好」的一页笔记：
 *
 * - `#`  课程标题（带品牌色下划线）
 * - `##` 章节：序号徽标 + 可点击折叠 / 展开，小节头部自动进目录
 * - `###` 子知识点：品牌色小圆点
 * - `-`  要点：圆点项目符号（`★` 自动换成星标），`[mm:ss]` 与行内代码渲染成彩色胶囊
 * - `>`  提示块：左侧彩条卡片（重点 = 橙、总结 = 绿、提示 = 蓝）
 * - 表格：表头底色 + 斑马纹 + 圆角卡片，手机上也能看清
 * - 关键词行：胶囊标签自动换行
 *
 * 本地整理和 AI 整理产出的都是同一套 Markdown，所以共用这套排版。
 */
class NoteRenderer(context: Context) {

    private val ctx = context

    /** 字号倍率，详情页的「字号」菜单直接改它。 */
    var scale = 1f

    /** 目录卡片的标题，由界面从字符串资源传进来。 */
    var tocTitle = "本页目录（点一下跳过去）"

    /**
     * 正文栏有多宽（dp），由页面算好传进来（横屏两栏时正文只有右边那一块）。
     * 0 表示没人告诉过 —— 那就不限制截图宽度，按老规矩铺满整栏。
     */
    var columnWidthDp = 0

    /**
     * 整理稿里截图的宽度上限（dp）。页面按横竖屏给不同的值：横过来屏幕矮，图更该让位给正文，
     * 一张图占掉半屏、字就只剩两行了。
     */
    var shotMaxDp = SHOT_MAX_DP

    /** 截图所在的笔记目录：渲染 `![图示](shots/x.jpg)` 时从这里取图。 */
    var imageDir: java.io.File? = null

    /** 点图放大看。参数是相对路径。 */
    var onImageClick: ((String) -> Unit)? = null

    private val surface = mc(com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF.toInt())
    private val onSurface = mc(com.google.android.material.R.attr.colorOnSurface, 0xFF1B1B1F.toInt())
    private val onVariant = mc(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF55565E.toInt())
    private val surfaceVar = mc(com.google.android.material.R.attr.colorSurfaceVariant, 0xFFE3E3EA.toInt())
    private val outline = mc(com.google.android.material.R.attr.colorOutline, 0xFF9A9AA5.toInt())
    private val primary = ContextCompat.getColor(ctx, R.color.brand)
    private val starColor = ContextCompat.getColor(ctx, R.color.star)
    private val okColor = 0xFF2E9E6B.toInt()
    private val isDark = ColorUtils.calculateLuminance(surface) < 0.5

    // ------------------------------------------------------------------ 对外

    /**
     * 把 [markdown] 渲染进 [container]。
     * [scrollParent] 传入包裹 container 的 ScrollView，点击目录可直接滚过去。
     * [tocHost] 是目录的去处：横屏时正文右边只够放一条阅读栏，目录就被移进左边那根侧栏，
     * 而不是继续压在正文最上面占掉本就金贵的高度。传 null 就还是老样子（目录放正文最前面）。
     */
    fun render(
        container: LinearLayout,
        markdown: String,
        scrollParent: View? = null,
        tocHost: LinearLayout? = null
    ) {
        container.removeAllViews()
        tocHost?.removeAllViews()
        val blocks = MiniMarkdown.parse(markdown)
        val toc = ArrayList<Pair<String, View>>()
        var section = 0
        var i = 0

        while (i < blocks.size) {
            val b = blocks[i]
            if (b is Block.Heading && b.level == 1) {
                container.addView(titleView(b.text))
                i++
                continue
            }
            if (b is Block.Heading && b.level == 2) {
                section++
                val (ordinal, title) = MiniMarkdown.splitOrdinal(b.text)
                val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                val wrap = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(sectionHeader(ordinal?.let { cnNumber(it) }, title, body))
                    addView(sectionDivider())
                    addView(body)
                }
                container.addView(wrap)
                toc.add(title to wrap)
                i++
                while (i < blocks.size) {
                    val n = blocks[i]
                    if (n is Block.Heading && n.level <= 2) break
                    addBlock(body, n)
                    i++
                }
                continue
            }
            addBlock(container, b)
            i++
        }

        if (toc.size >= 3) {
            // 目录搬到侧栏（横屏）时这张卡住在一条窄栏里，字号和内边距都收一档
            val card = tocCard(toc, scrollParent, compact = tocHost != null)
            if (tocHost != null) tocHost.addView(card) else container.addView(card, 0)
        }
    }

    // ------------------------------------------------------------------ 标题

    private fun titleView(text: String): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6f), 0, dp(4f))
        }
        val title = tv(21f, onSurface, true).apply {
            this.text = rich(text)
            setLineSpacing(dp(3f).toFloat(), 1f)
        }
        val rule = View(ctx).apply {
            background = roundBg(primary, 2f)
            layoutParams = LinearLayout.LayoutParams(dp(46f), dp(4f)).apply { topMargin = dp(8f) }
        }
        box.addView(title)
        box.addView(rule)
        return box
    }

    /**
     * 大节标题。
     *
     * [badge] 是这一节的编号：正文大节用它的中文序号（一、二、……），
     * 「本课知识框架 / 术语与公式」这类固定栏目不给编号，改用一根主色竖条。
     * 以前一律按出现顺序编号，结果「框架」被编成第 1 节、正文的「一、」反而成了第 2 节，
     * 序号和标题对不上，一眼看过去就是乱的。
     */
    private fun sectionHeader(badge: String?, title: String, body: LinearLayout): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16f), 0, dp(8f))
            isClickable = true
            val out = TypedValue()
            if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                setBackgroundResource(out.resourceId)
            }
        }
        val badgeView: View = if (badge == null) {
            // 没有序号的栏目（知识框架、术语表……）不编造数字，用竖条表示「这是一节」
            View(ctx).apply { background = roundBg(primary, 2f) }
        } else {
            TextView(ctx).apply {
                text = badge
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * scale)
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = roundBg(primary, 7f)
                includeFontPadding = false
            }
        }
        val label = tv(17.5f, onSurface, true).apply {
            // 标题里也可能夹着 `00:12-00:31` 这类行内代码 / 时间戳，交给 rich() 画成胶囊，
            // 不然反引号会原样露在标题上
            this.text = rich(title)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(10f) }
        }
        val chev = tv(12f, onVariant).apply {
            text = "▾"
            layoutParams = LinearLayout.LayoutParams(dp(20f), ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.END
        }
        // 有序号时是装数字的圆徽标，没序号时是那根竖条 —— 尺寸得分开给，别把竖条撑成大方块
        row.addView(
            badgeView,
            if (badge == null) LinearLayout.LayoutParams(dp(4f), dp(18f))
            else LinearLayout.LayoutParams(dp(22f), dp(22f))
        )
        row.addView(label)
        row.addView(chev)
        row.setOnClickListener {
            val collapsed = body.visibility == View.VISIBLE
            body.visibility = if (collapsed) View.GONE else View.VISIBLE
            chev.text = if (collapsed) "▸" else "▾"
        }
        return row
    }

    /** 大节标题下面那条细线：每一节的边界一眼可见。 */
    private fun sectionDivider(): View = View(ctx).apply {
        setBackgroundColor(blend(outline, 0.45f))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, max(1, dp(0.7f))
        ).apply { bottomMargin = dp(6f) }
    }

    private fun subHeading(text: String, level: Int): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(if (level == 3) 16f else 10f), 0, dp(5f))
        }
        if (level == 3) {
            // 左边一根竖条：三级标题一眼就能和普通要点区分开
            val bar = View(ctx).apply { background = roundBg(primary, 2f) }
            row.addView(bar, LinearLayout.LayoutParams(dp(3f), dp(15f)).apply {
                marginStart = dp(1f)
                marginEnd = dp(9f)
            })
        }
        // 「1.1」这种小节号单独用主色标出来，标题本身就不必再连着一串数字，看着清爽
        val (ordinal, head) = if (level == 3) MiniMarkdown.splitOrdinal(text) else null to text
        if (ordinal != null) {
            row.addView(tv(13f, primary, true).apply {
                this.text = ordinal
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8f) }
            })
        }
        val t = tv(if (level == 3) 15.5f else 14.5f, if (level == 3) onSurface else onVariant, true).apply {
            this.text = rich(head)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(t)
        return row
    }

    // ------------------------------------------------------------------ 块

    private fun addBlock(parent: LinearLayout, b: Block) {
        when (b) {
            is Block.Heading -> parent.addView(subHeading(b.text, b.level))
            is Block.Para -> parent.addView(paraView(b.text))
            is Block.Bullet -> parent.addView(bulletRow(b.text, b.depth, null))
            is Block.Ordered -> parent.addView(bulletRow(b.text, b.depth, b.marker))
            is Block.Quote -> parent.addView(quoteView(b.text, b.kind))
            is Block.Table -> parent.addView(tableView(b))
            is Block.Code -> parent.addView(codeView(b.lines))
            is Block.Chips -> parent.addView(chipsView(b.items))
            is Block.Image -> parent.addView(imageView(b))
            Block.Rule -> parent.addView(ruleView())
        }
    }

    /**
     * 找出 `![alt](src)` 对应磁盘上哪张图。
     *
     * 整理稿里写的是相对笔记目录的 `shots/xxx.jpg`，而界面可能把 [imageDir] 设成笔记目录、
     * 也可能直接设成 shots 目录 —— 两种约定都认，免得图片静默变成空白框。
     */
    internal fun resolveImage(src: String): java.io.File? {
        val dir = imageDir ?: return null
        if (src.isBlank()) return null
        val direct = java.io.File(dir, src)
        if (direct.exists()) return direct
        val name = src.substringAfterLast('/')
        if (name != src) {
            val flat = java.io.File(dir, name)
            if (flat.exists()) return flat
        }
        return null
    }

    /** 整理稿里的截图：圆角卡片 + 说明；点一下交给界面放大看。 */
    private fun imageView(b: Block.Image): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6f), 0, dp(6f))
        }
        // 图和它的说明一起装在这个「撑到上限就停」的容器里，宽度等这一栏量出来之后再定。
        // 为什么不在 ImageView 上写 maxWidth：那个属性只在 wrap_content 下生效，而 wrap_content
        // 在小屏上又会被图片的原始像素撑出一栏之外 —— 两条路都不行，索性自己算。
        val holder = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                shotWidthDp(), ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val iv = android.widget.ImageView(ctx).apply {
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            background = roundBg(surfaceVar, 12f)
            minimumHeight = dp(110f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val file = resolveImage(b.src)
        if (file != null) {
            Thumbs.load(file, 1100, iv, b.src)
            iv.isClickable = true
            iv.setOnClickListener { onImageClick?.invoke(b.src) }
        }
        holder.addView(iv)
        if (b.alt.isNotBlank()) {
            holder.addView(tv(12.5f, onVariant).apply {
                text = rich(b.alt)
                setPadding(0, dp(6f), 0, 0)
            })
        }
        box.addView(holder)
        return box
    }

    /**
     * 图连同它的说明占多宽：栏宽和 [shotMaxDp] 里小的那个；栏宽不知道（没人告诉过）
     * 就还按老规矩铺满整栏。
     *
     * 叫「略微缩小」而不是「缩略图」：整栏宽的时候一张截图能占满整个屏，翻起来全是图、
     * 看不到字；手机上这个上限比栏还宽，等于没限制。
     */
    private fun shotWidthDp(): Int {
        val room = columnWidthDp
        if (room <= 0) return ViewGroup.LayoutParams.MATCH_PARENT
        return dp(min(room, shotMaxDp).toFloat())
    }

    private fun paraView(text: String): View = tv(15f, onSurface).apply {
        this.text = rich(text)
        setLineSpacing(dp(4f).toFloat(), 1f)
        setPadding(0, dp(4f), 0, dp(4f))
    }

    private fun bulletRow(text: String, depth: Int, marker: String?): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(3f), 0, dp(3f))
        }
        val body = text.trimStart().removePrefix("★").trim()
        val starred = text.trimStart().startsWith("★")
        val sizeSp = 15f - depth * 0.4f
        val glyph = tv(if (starred) 13f else 14f, if (starred) starColor else primary, true).apply {
            this.text = when {
                starred -> "★"
                marker != null -> "$marker."
                else -> if (depth == 0) "•" else "◦"
            }
            includeFontPadding = false
            gravity = if (marker != null) Gravity.END else Gravity.CENTER_HORIZONTAL
        }
        glyph.layoutParams = LinearLayout.LayoutParams(if (marker != null) dp(22f) else dp(15f), ViewGroup.LayoutParams.WRAP_CONTENT)
        val content = tv(sizeSp, onSurface).apply {
            this.text = rich(body)
            setLineSpacing(dp(4f).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(7f + depth * 14f)
            }
        }
        row.addView(glyph)
        row.addView(content)
        return row
    }

    private fun quoteView(text: String, kind: Callout): View {
        val accent = when (kind) {
            Callout.WARN -> starColor
            Callout.OK -> okColor
            Callout.INFO -> primary
        }
        val card = CalloutCard(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            clipToOutline = true
            background = roundBg(blend(accent, 0.10f), 12f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8f)
                bottomMargin = dp(6f)
            }
        }
        val bar = View(ctx).apply {
            background = GradientDrawable().apply {
                setColor(accent)
                cornerRadii = floatArrayOf(
                    dp(12f).toFloat(), dp(12f).toFloat(),
                    0f, 0f, 0f, 0f,
                    dp(12f).toFloat(), dp(12f).toFloat()
                )
            }
        }
        val body = tv(14.5f, onSurface).apply {
            this.text = rich(text)
            setLineSpacing(dp(4f).toFloat(), 1f)
            setPadding(dp(12f), dp(11f), dp(13f), dp(11f))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        card.addView(bar, LinearLayout.LayoutParams(dp(4f), ViewGroup.LayoutParams.MATCH_PARENT))
        card.addView(body)
        return card
    }

    private fun tableView(b: Block.Table): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipToOutline = true
            background = roundBg(blend(outline, if (isDark) 0.35f else 0.16f), 12f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10f)
                bottomMargin = dp(8f)
            }
        }
        val cols = max(b.header.size, b.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)

        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(blend(primary, if (isDark) 0.18f else 0.10f))
        }
        for (c in 0 until cols) {
            val cell = b.header.getOrNull(c) ?: ""
            head.addView(cellView(cell, cols, true))
        }
        card.addView(head)

        b.rows.forEachIndexed { index, row ->
            val line = View(ctx).apply { setBackgroundColor(blend(outline, 0.30f)) }
            card.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max(1, dp(0.7f))))
            val r = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                if (index % 2 == 1) setBackgroundColor(blend(primary, if (isDark) 0.07f else 0.035f))
            }
            for (c in 0 until cols) r.addView(cellView(row.getOrNull(c) ?: "", cols, false))
            card.addView(r)
        }
        return card
    }

    private fun cellView(text: String, cols: Int, header: Boolean): View = tv(13.5f, if (header) onSurface else onVariant, header).apply {
        this.text = rich(text)
        setLineSpacing(dp(2f).toFloat(), 1f)
        setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f / cols)
    }

    private fun codeView(lines: List<String>): View {
        val box = TextView(ctx).apply {
            text = lines.joinToString("\n")
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * scale)
            setTextColor(onSurface)
            setBackground(roundBg(blend(onSurface, if (isDark) 0.10f else 0.05f), 10f))
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            setLineSpacing(dp(3f).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f) }
        }
        return box
    }

    private fun chipsView(items: List<String>): View {
        val flow = FlowLayout(ctx).apply {
            hGap = dp(7f)
            vGap = dp(7f)
            setPadding(0, dp(6f), 0, dp(6f))
        }
        for (it in items) {
            val chip = TextView(ctx).apply {
                text = it
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f * scale)
                setTextColor(primary)
                background = roundBg(blend(primary, if (isDark) 0.22f else 0.12f), 999f)
                setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
                includeFontPadding = false
            }
            flow.addView(chip)
        }
        return flow
    }

    private fun ruleView(): View = View(ctx).apply {
        setBackgroundColor(blend(outline, 0.35f))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, max(1, dp(0.7f))
        ).apply {
            topMargin = dp(16f)
            bottomMargin = dp(10f)
        }
    }

    // ------------------------------------------------------------------ 目录

    private fun tocCard(items: List<Pair<String, View>>, scrollParent: View?, compact: Boolean = false): View {
        // 住进侧栏时这张卡只有 200dp 宽：正文里合适的那点留白，搁在这儿就又空又挤
        val pad = dp(if (compact) 10f else 14f)
        val vPad = dp(if (compact) 10f else 12f)
        val titleSize = if (compact) 11.5f else 12.5f
        val numSize = if (compact) 12f else 13f
        val itemSize = if (compact) 13.5f else 14.5f
        val rowPad = dp(if (compact) 6f else 7f)
        val numWidth = dp(if (compact) 18f else 20f)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipToOutline = true
            background = roundBg(blend(primary, if (isDark) 0.12f else 0.06f), 14f)
            setPadding(pad, vPad, pad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = if (compact) 0 else dp(6f) }
        }
        card.addView(tv(titleSize, onVariant, true).apply {
            text = tocTitle
            setPadding(0, 0, 0, dp(if (compact) 4f else 6f))
        })
        items.forEachIndexed { index, (title, target) ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setPadding(0, rowPad, 0, rowPad)
            }
            row.addView(tv(numSize, primary, true).apply {
                text = (index + 1).toString()
                layoutParams = LinearLayout.LayoutParams(numWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            row.addView(tv(itemSize, onSurface).apply {
                text = title
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.setOnClickListener {
                (scrollParent as? android.widget.ScrollView)
                    ?.smoothScrollTo(0, max(0, target.top - dp(10f)))
            }
            card.addView(row)
            if (index < items.size - 1) {
                card.addView(View(ctx).apply { setBackgroundColor(blend(onSurface, 0.08f)) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max(1, dp(0.7f))))
            }
        }
        return card
    }

    // ------------------------------------------------------------------ 行内

    /**
     * 「一」「十二」这种中文序号换成阿拉伯数字，好塞进 22dp 的小徽标里。
     * 认不出来（模型写了别的花样）就原样返回，宁可丑一点也不能把序号弄丢。
     */
    private fun cnNumber(s: String): String {
        val t = s.trim()
        if (t.isEmpty() || t.all { it.isDigit() }) return t
        val d = "零一二三四五六七八九"
        return when {
            t.length == 1 && t[0] in d -> d.indexOf(t[0]).toString()
            t == "十" -> "10"
            t.length == 2 && t[0] == '十' && t[1] in d -> "1" + d.indexOf(t[1])
            t.length == 2 && t[1] == '十' && t[0] in d -> d.indexOf(t[0]).toString() + "0"
            t.length == 3 && t[1] == '十' && t[0] in d && t[2] in d ->
                d.indexOf(t[0]).toString() + d.indexOf(t[2])
            else -> t
        }
    }

    /** 把整理稿的行内记号变成带样式的文字：加粗、行内代码胶囊、时间戳胶囊、星标。 */
    private fun rich(text: String): CharSequence {
        val sb = SpannableStringBuilder()
        for (s in MiniMarkdown.inline(text)) {
            val start = sb.length
            when (s) {
                is Span.Text -> sb.append(s.text)
                is Span.Bold -> {
                    sb.append(s.text)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, SPAN_FLAG)
                }
                is Span.Em -> {
                    sb.append(s.text)
                    sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, SPAN_FLAG)
                }
                is Span.Code -> {
                    sb.append(s.text)
                    sb.setSpan(
                        PillSpan(blend(onSurface, if (isDark) 0.14f else 0.07f), onSurface, true, 0.94f),
                        start, sb.length, SPAN_FLAG
                    )
                }
                is Span.Time -> {
                    sb.append(s.text)
                    // 时间戳是「回头翻录播」用的索引，不该跟知识点抢注意力：灰底灰字、比正文略小
                    sb.setSpan(
                        PillSpan(blend(onSurface, if (isDark) 0.10f else 0.06f), onVariant, true, 0.84f),
                        start, sb.length, SPAN_FLAG
                    )
                }
                Span.Star -> {
                    sb.append("★")
                    sb.setSpan(ForegroundColorSpan(starColor), start, sb.length, SPAN_FLAG)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, SPAN_FLAG)
                }
                is Span.Image -> sb.append(s.alt.ifBlank { "[截图]" })
            }
        }
        return sb
    }

    // ------------------------------------------------------------------ 工具

    private fun tv(sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp * scale)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            includeFontPadding = false
            setLineSpacing(0f, 1f)
        }

    private fun dp(v: Float): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    private fun mc(attr: Int, fallback: Int): Int = MaterialColors.getColor(ctx, attr, fallback)

    /** 把 [color] 按 [alpha] 叠在页面底色上，得到一个可用的实色。 */
    private fun blend(color: Int, alpha: Float): Int =
        ColorUtils.blendARGB(surface, color, alpha.coerceIn(0f, 1f))

    private fun roundBg(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    /** 行内胶囊：圆角底色 + 自己的文字。 */
    private class PillSpan(
        private val bg: Int,
        private val fg: Int,
        private val mono: Boolean = false,
        private val sizeScale: Float = 1f,
        private val bold: Boolean = false
    ) : ReplacementSpan() {

        private fun styled(base: Paint): Paint = Paint(base).apply {
            color = fg
            textSize = base.textSize * sizeScale
            isFakeBoldText = bold
            if (mono) typeface = Typeface.MONOSPACE
        }

        private fun padH(base: Paint): Float = base.textSize * 0.34f

        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int {
            val p = styled(paint)
            if (fm != null) {
                val m = p.fontMetricsInt
                fm.ascent = m.ascent - (p.textSize * 0.10f).toInt()
                fm.descent = m.descent + (p.textSize * 0.10f).toInt()
                fm.top = fm.ascent
                fm.bottom = fm.descent
                fm.leading = 0
            }
            return (p.measureText(text, start, end) + padH(paint) * 2f).toInt()
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            val p = styled(paint)
            val fm = p.fontMetrics
            val ph = padH(paint)
            val left = x
            val right = x + p.measureText(text, start, end) + ph * 2f
            val rect = RectF(
                left,
                y + fm.ascent - p.textSize * 0.12f,
                right,
                y + fm.descent + p.textSize * 0.12f
            )
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
            val radius = rect.height() / 2f
            canvas.drawRoundRect(rect, radius, radius, bgPaint)
            canvas.drawText(text, start, end, left + ph, y.toFloat(), p)
        }
    }

    /**
     * 提示块容器：让左侧彩条始终和卡片一样高。
     * 普通横向 LinearLayout 里，layout_height=match_parent 的空 View 会被量成 0 高。
     */
    private class CalloutCard(context: Context) : LinearLayout(context) {
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            val bar = getChildAt(0) ?: return
            val body = getChildAt(1) ?: return
            val w = bar.measuredWidth
            val h = height
            if (w <= 0 || h <= 0) return
            if (bar.measuredHeight != h) {
                bar.measure(
                    MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
                )
            }
            bar.layout(0, 0, w, h)
            body.layout(w, 0, width, h)
        }
    }

    /** 自动换行的标签容器（关键词胶囊用）。 */
    private class FlowLayout(context: Context) : ViewGroup(context) {

        var hGap = 0
        var vGap = 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            var x = 0
            var lineHeight = 0
            var totalHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                measureChild(child, MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), heightMeasureSpec)
                val cw = child.measuredWidth
                val ch = child.measuredHeight
                if (x > 0 && x + cw > width) {
                    totalHeight += lineHeight + vGap
                    x = 0
                    lineHeight = 0
                }
                x += cw + hGap
                lineHeight = max(lineHeight, ch)
            }
            totalHeight += lineHeight
            setMeasuredDimension(width, totalHeight + paddingTop + paddingBottom)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val width = measuredWidth - paddingLeft - paddingRight
            var x = paddingLeft
            var y = paddingTop
            var lineHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                val cw = child.measuredWidth
                val ch = child.measuredHeight
                if (x > paddingLeft && x + cw > paddingLeft + width) {
                    x = paddingLeft
                    y += lineHeight + vGap
                    lineHeight = 0
                }
                child.layout(x, y, x + cw, y + ch)
                x += cw + hGap
                lineHeight = max(lineHeight, ch)
            }
        }
    }

    companion object {
        private const val SPAN_FLAG = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

        /** 整理稿里截图的宽度上限（dp），比它更窄的栏就按栏宽来。 */
        const val SHOT_MAX_DP = 500
    }
}
