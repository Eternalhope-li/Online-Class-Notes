package com.lecture.notes

import com.lecture.notes.data.Entry
import com.lecture.notes.data.Note
import com.lecture.notes.util.ImageUtil
import com.lecture.notes.util.MiniMarkdown
import com.lecture.notes.util.MiniMarkdown.Block
import com.lecture.notes.util.MiniMarkdown.Callout
import com.lecture.notes.util.MiniMarkdown.Span
import com.lecture.notes.util.NoteDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排版引擎解析层的回归测试。渲染成 View 那部分要在手机上看，这里保证「块」和「行内片段」
 * 拆得对：拆错了排版就会散架（表格变段落、时间戳不变胶囊之类）。
 * 直接跑：gradlew testReleaseUnitTest
 */
class MiniMarkdownTest {

    private val sample = """
        # 数据结构 第 3 讲 · 二叉树

        > 2026-09-26 20:31　时长 42:10　共 386 句

        ## 本课关键词

        `二叉树` `前序遍历` `中序遍历`

        ## 一、二叉树的定义

        ### 1.1 定义

        - `[00:09]` 二叉树是指每个节点最多有两个子节点的树结构
          - 左右子树顺序不能颠倒
        - ★ `[00:23]` 这是和有根无序树的区别

        > 注意：两条性质里的 2 都是「每层最多两个分支」推出来的。

        ## 术语与公式

        | 术语 | 含义 |
        | --- | --- |
        | 二叉树 | 每个节点最多两个子节点 |

        ```
        i 的左孩子 = 2i
        ```

        ---

        1. 第一点
    """.trimIndent()

    @Test
    fun parsesBlocks() {
        val blocks = MiniMarkdown.parse(sample)

        val h1 = blocks.filterIsInstance<Block.Heading>().first()
        assertEquals(1, h1.level)
        assertEquals("数据结构 第 3 讲 · 二叉树", h1.text)

        assertTrue(blocks.any { it is Block.Quote && it.kind == Callout.WARN })
        assertTrue(blocks.any { it is Block.Chips && it.items.contains("前序遍历") })
        assertTrue(blocks.any { it is Block.Rule })
        assertTrue(blocks.any { it is Block.Code && it.lines.first().contains("2i") })
        assertTrue(blocks.any { it is Block.Ordered })

        val table = blocks.filterIsInstance<Block.Table>().single()
        assertEquals(listOf("术语", "含义"), table.header)
        assertEquals(listOf("二叉树", "每个节点最多两个子节点"), table.rows.single())
    }

    @Test
    fun keepsNestedBulletDepth() {
        val blocks = MiniMarkdown.parse(sample)
        val bullets = blocks.filterIsInstance<Block.Bullet>()
        assertTrue(bullets.any { it.depth == 0 })
        assertTrue(bullets.any { it.depth == 1 })
    }

    @Test
    fun splitsInlineSpans() {
        val spans = MiniMarkdown.inline("`[03:12]` 定义：**二叉树** 是 `树结构` ★")
        assertTrue(spans.any { it is Span.Time && it.text == "[03:12]" })
        assertTrue(spans.any { it is Span.Bold && it.text == "二叉树" })
        assertTrue(spans.any { it is Span.Code && it.text == "树结构" })
        assertTrue(spans.contains(Span.Star))
    }

    @Test
    fun splitsOrdinalFromHeading() {
        assertEquals("一" to "二叉树的定义", MiniMarkdown.splitOrdinal("一、二叉树的定义"))
        assertEquals("1.1" to "定义", MiniMarkdown.splitOrdinal("1.1 定义"))
        assertEquals(null to "本课关键词", MiniMarkdown.splitOrdinal("本课关键词"))
        assertEquals("三" to "存储结构", MiniMarkdown.splitOrdinal("三、存储结构"))
    }

    @Test
    fun readsItalicEmphasis() {
        val spans = MiniMarkdown.inline("_这篇记录还没有足够的内容可以整理。_")
        assertTrue(spans.any { it is Span.Em && it.text == "这篇记录还没有足够的内容可以整理。" })
        assertTrue(MiniMarkdown.inline("*旁的备注*").any { it is Span.Em && it.text == "旁的备注" })
        // 文件名和算式里的记号不能被吃掉
        assertTrue(MiniMarkdown.inline("看 note_store 这个文件").none { it is Span.Em })
        assertTrue(MiniMarkdown.inline("3 * 4 * 5").none { it is Span.Em })
    }

    @Test
    fun htmlIsStyledAndEscaped() {
        val html = MiniMarkdown.toHtml("## 一、A & B\n\n- 用到 `<div>` 标签", "测试")
        assertFalse(html.contains("<table"))
        assertTrue(html.contains("A &amp; B"))
        assertTrue(html.contains("&lt;div&gt;"))
        assertTrue(html.contains("<li"))
        assertFalse(html.contains("<blockquote"))
        assertTrue(html.startsWith("<!DOCTYPE html>"))
    }

    @Test
    fun plainKeepsTimestampsAndText() {
        val plain = MiniMarkdown.plain(sample)
        assertTrue(plain.contains("[00:09]"))
        assertTrue(plain.contains("二叉树是指每个节点最多有两个子节点的树结构"))
        assertTrue(plain.contains("•"))
        assertFalse(plain.contains("**"))
    }

    /** 本地整理稿也要能被排版引擎吃下去（两条链路共用一套排版）。 */
    @Test
    fun localDigestIsRenderable() {
        val n = Note(
            id = "t",
            title = "数据结构 第 3 讲",
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L,
            durationMs = 40 * 60 * 1000L,
            source = "mic"
        )
        n.entries.add(Entry(9_000L, "二叉树是指每个节点最多有两个子节点的树结构。"))
        n.entries.add(Entry(37_000L, "性质一，在二叉树的第 i 层上最多有 2 的 i 减 1 次方个节点。"))
        n.entries.add(Entry(170_000L, "作业是课后习题 3-1 到 3-8，下节课讲二叉搜索树。"))

        val (md, _) = NoteDigest.build(n)
        val blocks = MiniMarkdown.parse(md)
        assertTrue(blocks.any { it is Block.Heading && it.level == 1 })
        assertTrue(blocks.filterIsInstance<Block.Heading>().any { it.level == 2 })
        assertTrue(blocks.any { it is Block.Bullet })
        assertTrue(MiniMarkdown.plain(md).isNotBlank())
    }

    /** 截图夹在要点行中间时（模型整理经常这么写），要渲染成真的图片，不能把内容当文本吐出来。 */
    @Test
    fun inlineShotInsideBulletBecomesImageTag() {
        val html = MiniMarkdown.toHtml("- `[00:27]` ![图示](shots/a.jpg)", "t")
        assertTrue(html, html.contains("<img src=\"shots/a.jpg\""))
        assertFalse("行内图片不该还留着 Markdown 记号", html.contains("![图示]"))
        assertTrue("要点本身要留着", html.contains("[00:27]"))
    }

    /** 模型爱把图夹在要点行中间：提行之后图才不会被解析层丢掉，占位词也要擦干净。 */
    @Test
    fun hoistImagesPullsInlineShotOntoItsOwnLine() {
        val md = "- **图示**：![课堂截图 00:39](shots/x.jpg) [图示] - 终端窗口显示 Error。"
        val out = MiniMarkdown.hoistImages(md)
        val lines = out.trim().split('\n').map { it.trim() }
        assertTrue(out, lines.any { it == "![课堂截图 00:39](shots/x.jpg)" })
        assertFalse(out, out.contains("**图示**") || out.contains("[图示]"))
        assertTrue(out, out.contains("终端窗口显示 Error。"))
        assertTrue("代码块里的记号不动", MiniMarkdown.hoistImages("```\n![x](y)\n```").contains("![x](y)"))
        assertTrue("已经是单独一行的图不用重复搬", MiniMarkdown.hoistImages("![x](y)").trim() == "![x](y)")
    }

    @Test
    fun inlineShotWithDataUrlIsStillAnImage() {
        val url = ImageUtil.DATA_HEAD + "AAAA"
        val html = MiniMarkdown.toHtml("- `[00:12]` ![图示]($url)", "t")
        assertTrue(html, html.contains("<img src=\"" + url + "\""))
    }

    /** 纯文本兜底（复制 / 分享）：要点和图片说明都要留住，不能吐成裸链接或一坨 base64。 */
    @Test
    fun plainTextKeepsBulletAndShotCaption() {
        val md = "- `[00:27]` ![课堂截图 00:27](shots/a.jpg)"
        val plain = MiniMarkdown.plain(md)
        assertTrue(plain, plain.contains("[00:27]"))
        assertTrue(plain, plain.contains("[图片]") && plain.contains("课堂截图 00:27"))
        assertFalse(plain, plain.contains("base64") || plain.contains("!["))
        // 行内片段那一路（表格单元格等）仍然保留 Markdown 记号
        assertTrue(MiniMarkdown.inlinePlain("![图示](shots/a.jpg)").contains("![图示](shots/a.jpg)"))
    }

    /** AI 常把代码块整个塞进列表项里，照原样渲染就是一串带项目符号的反引号残渣。 */
    @Test
    fun codeFenceInsideBulletBecomesCodeBlock() {
        val md = listOf(
            "- 在终端里可以这样跑：",
            "  - ```bash",
            "    python main.py",
            "    ```",
            "- 结束"
        ).joinToString("\n")
        val blocks = MiniMarkdown.parse(md)
        val code = blocks.filterIsInstance<Block.Code>().single()
        assertEquals(listOf("python main.py"), code.lines)
        assertTrue(
            "围栏不该留在要点里",
            blocks.none { it is Block.Bullet && it.text.contains("```") }
        )
    }

    /** 视觉模型写的 `- > 要点：xxx` 是引用，不是列表项 —— 照列表渲染会把「>」原样显示出来。 */
    @Test
    fun visionMarkerBulletBecomesQuote() {
        val quote = MiniMarkdown.parse("- > 要点：一定要先看文档")
            .filterIsInstance<Block.Quote>().single()
        assertEquals("要点：一定要先看文档", quote.text)
        assertEquals(Callout.WARN, quote.kind)
    }

    /** 只有标记没有内容的那种（`- "> 要点："`）直接丢掉，内容在下面几行里。 */
    @Test
    fun emptyVisionMarkerBulletIsDropped() {
        val md = listOf(
            "- 图里是一张流程图",
            "- \"> 要点：\"",
            "- 做作业记得画图"
        ).joinToString("\n")
        val bullets = MiniMarkdown.parse(md).filterIsInstance<Block.Bullet>()
        assertEquals(2, bullets.size)
        assertTrue(bullets.none { it.text.contains("要点") })
    }

    /**
     * 截图下面那条孤零零的「>」（视觉模型的引用块被套进列表项、内容又空着）。
     * 照列表项渲染出来，成品里就是一个多余的空引用行和一个「• >」。
     */
    @Test
    fun loneQuoteMarkerBulletIsDropped() {
        val md = listOf(
            "- 视频效果展示60fps录像",
            "  - 这是视频效果",
            "  - >",
            "  - > "
        ).joinToString("\n")
        val blocks = MiniMarkdown.parse(md)
        assertEquals("只该剩两条要点：$blocks", 2, blocks.filterIsInstance<Block.Bullet>().size)
        assertTrue("不该留下空的引用块：$blocks", blocks.none { it is Block.Quote })
    }

    /** 只写了个「>」的引用块（没有内容）不渲染成空方框。 */
    @Test
    fun emptyQuoteBlockIsDropped() {
        val blocks = MiniMarkdown.parse("- 要点一\n\n>\n\n- 要点二\n")
        assertTrue("空引用不该占一个方框：$blocks", blocks.none { it is Block.Quote })
        assertEquals(2, blocks.filterIsInstance<Block.Bullet>().size)
    }
}
