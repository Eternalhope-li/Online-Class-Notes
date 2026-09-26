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

    /** 截图夹在要点行中间时（转写导出就是这种形状），要渲染成真的图片，不能把内容当文本吐出来。 */
    @Test
    fun inlineShotInsideBulletBecomesImageTag() {
        val html = MiniMarkdown.toHtml("- `[00:27]` ![图示](shots/a.jpg)", "t")
        assertTrue(html.contains("<img class=\"shot-inline\" src=\"shots/a.jpg\""))
        assertFalse("行内图片不该还留着 Markdown 记号", html.contains("![图示]"))
    }

    @Test
    fun inlineShotWithDataUrlIsStillAnImage() {
        val url = ImageUtil.DATA_HEAD + "AAAA"
        val html = MiniMarkdown.toHtml("- `[00:12]` ![图示]($url)", "t")
        assertTrue(html.contains("<img class=\"shot-inline\""))
    }

    /** 纯文本兜底（复制 / 分享）要保持原来的 Markdown 记号，不能退化成裸链接或 base64。 */
    @Test
    fun inlineShotKeepsMarkdownInPlainText() {
        val md = "- `[00:27]` ![图示](shots/a.jpg)"
        assertTrue(MiniMarkdown.plain(md).contains("![图示](shots/a.jpg)"))
        assertTrue(MiniMarkdown.inlinePlain("![图示](shots/a.jpg)").contains("![图示](shots/a.jpg)"))
    }
}
