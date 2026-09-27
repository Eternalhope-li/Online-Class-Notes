package com.lecture.notes

import com.lecture.notes.net.LlmDigest
import com.lecture.notes.util.MiniMarkdown
import com.lecture.notes.util.MiniMarkdown.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整理稿收尾那几刀、以及长课「先提纲、再逐节成文」的拆解逻辑，全是确定性的字符串处理。
 * 这些地方错一次，用户看到的就是「整理之后全是 markdown 源码」「同一段话在笔记里出现两遍」
 * 「某一节只剩一个光标题」这类毛病，所以单独盯住：
 *
 *  - 整篇被 ``` 包住 → 必须拆开（里层围栏成不成对都要拆）；
 *  - 同一条要点在两个小节里各写一遍 → 只留一条，被掏空的小节连标题一起删；
 *  - 提纲要能拆出「前言 + 各大节」，并按时间戳给出每一节的素材范围；
 *  - 解析层遇到「整篇是一个代码块」也要当正文渲染，不能让用户看源码。
 */
class DigestStructureTest {

    private fun lines(vararg xs: String): String = xs.joinToString("\n")

    // ------------------------------------------------------------ 整篇围栏

    @Test
    fun wholeDocumentFenceIsStrippedEvenWithInnerFence() {
        val md = lines(
            "```markdown",
            "题目：Socket 的概念",
            "",
            "## 一、Socket 是什么",
            "- `[00:10]` Socket 是进程间通信的端点。",
            "```text",
            "socket()",
            "```",
            "- `[00:12]` 每个连接由四元组唯一确定。",
            "```"
        )
        val out = LlmDigest.unwrapFence(md)
        assertFalse("整篇的围栏要拆掉，不能因为里层有围栏就留下", out.startsWith("```"))
        assertTrue(out.startsWith("题目：Socket 的概念"))
        assertTrue(out.endsWith("- `[00:12]` 每个连接由四元组唯一确定。"))
    }

    @Test
    fun doubleWrappedDocumentIsFullyUnwrapped() {
        val md = lines("```", "```md", "## 一、正文", "- 一行", "```", "```")
        val out = LlmDigest.unwrapFence(md)
        assertEquals(lines("## 一、正文", "- 一行"), out)
    }

    @Test
    fun normalNoteIsLeftAlone() {
        val md = lines("## 一、正文", "", "- 一行", "", "```", "code()", "```")
        assertEquals("没有把整篇包住就一个字都不动", md, LlmDigest.unwrapFence(md))
    }

    // ------------------------------------------------------------ 去重 / 空壳小节

    @Test
    fun repeatedPointIsKeptOnceAndEmptiedSectionGoesAway() {
        val md = lines(
            "## 一、交换机原理",
            "- `[00:12]` MAC 地址表记录设备的 MAC 地址和端口。",
            "- `[00:15]` 交换机靠学习 MAC 地址维护转发表。",
            "## 二、MAC 地址表的使用",
            "- `[00:12]` MAC 地址表记录设备的 MAC 地址和端口。",
        )
        val out = LlmDigest.tidyDigest(md)
        assertEquals(1, Regex("MAC 地址表记录设备的").findAll(out).count())
        assertFalse("被去空的小节要连标题一起删掉", out.contains("二、MAC 地址表的使用"))
        assertTrue("第一节还在", out.contains("一、交换机原理"))
    }

    @Test
    fun shortBulletsAreNotDeduped() {
        val md = lines("## 一、要点", "- 注意", "- 注意")
        val out = LlmDigest.tidyDigest(md)
        assertEquals("太短的要点不参与去重，免得误删", 2, Regex("- 注意").findAll(out).count())
    }

    @Test
    fun sectionWithOnlyChildHeadingSurvives() {
        val md = lines("## 一、大节", "### 1.1 小节", "- `[00:10]` 一条要点")
        val out = LlmDigest.tidyDigest(md)
        assertTrue("大节下面还有小节，就不算空壳", out.contains("一、大节"))
    }

    @Test
    fun timestampsAreStrippedFromTheFinishedNote() {
        val md = lines(
            "## 一、交换机原理",
            "- `[00:10]` 交换机靠 MAC 地址表转发数据包。",
            "  - `[00:12]` 子要点也要留住缩进",
            "- 这条本来就没有时间戳",
            "",
            "![课堂截图 00:10](shots/a.jpg)"
        )
        val out = LlmDigest.stripTimestamps(md)
        assertFalse("要点前面的时间戳不要再出现", out.contains("[00:10]"))
        assertFalse(out.contains("[00:12]"))
        assertTrue("擦掉记号后要还原成干净的要点", out.contains("- 交换机靠 MAC 地址表转发数据包。"))
        assertTrue("子要点的缩进要留着", out.contains("  - 子要点也要留住缩进"))
        assertTrue("图片题注里的时刻不是时间戳，别动", out.contains("![课堂截图 00:10](shots/a.jpg)"))
        assertTrue(out.contains("- 这条本来就没有时间戳"))
    }

    // ------------------------------------------------------------ 提纲拆解

    @Test
    fun outlineSplitsIntoSectionsWithTimeRange() {
        val outline = lines(
            "题目：交换机怎么转发数据",
            "> 这节课讲交换机靠 MAC 地址表转发数据包，以及 MAC 地址是怎么学到的。",
            "## 本课知识框架",
            "- 交换机 → MAC 地址表",
            "## 一、交换机原理",
            "- `[00:10]` 查表转发",
            "- `[00:40]` 学习 MAC 地址",
            "## 一句话总结",
            "交换机靠 MAC 地址表转发数据包。",
        )
        val o = LlmDigest.sectionsOf(outline)
        assertEquals(3, o.sections.size)
        assertTrue(o.preamble.contains("题目：交换机怎么转发数据"))
        assertTrue("知识框架自己算一节，不放进前言", o.sections[0].head.contains("本课知识框架"))
        assertEquals("## 一、交换机原理", o.sections[1].head)
        assertEquals(10, o.sections[1].from)
        assertEquals(40, o.sections[1].to)
        assertFalse("真正讲知识的大节要逐节扩写", o.sections[1].keep)
        assertTrue("知识框架直接用提纲里的写法", o.sections[0].keep)
        assertTrue("一句话总结不扩写", o.sections[2].keep)
    }

    @Test
    fun outlineWithoutSectionsFallsBack() {
        val o = LlmDigest.sectionsOf(lines("题目：测试", "> 导语", "- 没有大节"))
        assertTrue("拆不出大节时由调用方退回「合并一版终稿」", o.sections.isEmpty())
    }

    // ------------------------------------------------------------ 解析层兜底

    @Test
    fun fencedWholeDocumentRendersAsNotes() {
        val md = lines("```", "## 一、交换机原理", "- `[00:10]` 查表转发", "```")
        val blocks = MiniMarkdown.parse(md)
        assertTrue("整篇包一个代码块要当正文渲染", blocks.any { it is Block.Heading })
        assertFalse("不能让用户看一大片源码", blocks.any { it is Block.Code })
    }

    @Test
    fun halfWrappedBodyIsDemotedToo() {
        val md = listOf(
            "## 本课关键词",
            "`交换机` `MAC`",
            "",
            "```markdown",
            "> 学习交换机怎么转发数据。",
            "## 一、交换机原理",
            "- `[00:10]` 交换机靠 MAC 地址表转发。",
            "```",
        ).joinToString("\n")
        val blocks = MiniMarkdown.parse(md)
        assertTrue("围栏里是正文就要拆开", blocks.any { it is Block.Heading && it.text.contains("交换机原理") })
        assertTrue("要点也要正常解析出来", blocks.any { it is Block.Bullet })
        assertFalse("不能整段当成源码", blocks.any { it is Block.Code })
    }

    @Test
    fun realCodeBlockInsideNoteStaysCode() {
        val md = listOf(
            "## 一、示例代码",
            "- `[00:10]` 老师演示的写法：",
            "```python",
            "def f(x):",
            "    return x + 1",
            "```",
        ).joinToString("\n")
        val blocks = MiniMarkdown.parse(md)
        assertTrue("真代码要留在代码块里", blocks.any { it is Block.Code })
    }

    @Test
    fun realCodeBlockStaysCode() {
        val md = lines("```", "fun main() {", "    println(1)", "}", "```")
        val blocks = MiniMarkdown.parse(md)
        assertTrue("真是代码就还是代码块", blocks.single() is Block.Code)
    }
}
