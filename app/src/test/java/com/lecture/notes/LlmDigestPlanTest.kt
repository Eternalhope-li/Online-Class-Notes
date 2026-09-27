package com.lecture.notes

import androidx.test.core.app.ApplicationProvider
import com.lecture.notes.data.Entry
import com.lecture.notes.data.Note
import com.lecture.notes.net.LlmDigest
import com.lecture.notes.util.Prefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AI 整理链路的两个纯逻辑部分：
 *  - 分段计划：短课要能一趟发完（省掉素材卡那两轮调用），长课才退回分段；
 *  - 流式增量：SSE 每一帧都要能正确取出正文，取不出来就等于白接。
 */
// org.json 是 Android 自带的，纯 JVM 单测拿到的是 stub，所以这里跑 Robolectric
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LlmDigestPlanTest {

    private fun note(text: String, parts: Int = 1): Note {
        val n = Note("t", "测试课", 0L, 0L, 0L, "mic")
        val per = text.length / parts
        var at = 0L
        for (i in 0 until parts) {
            val from = i * per
            val to = if (i == parts - 1) text.length else (i + 1) * per
            n.entries.add(Entry(at, text.substring(from, to)))
            at += 5000L
        }
        return n
    }

    @Test
    fun shortNoteGoesInOnePass() {
        val p = LlmDigest.plan(note("二叉树是指每个节点最多有两个子节点的树结构。".repeat(30)))
        assertTrue("短课应该一趟发完", p.singlePass)
        assertEquals(1, p.chunks.size)
    }

    @Test
    fun mediumNoteStillGoesInOnePass() {
        val text = "这是一个知识点的句子，用来占位置凑字数。".repeat(300)
        val p = LlmDigest.plan(note(text, parts = 6))
        val total = p.chunks.sumOf { it.length }
        assertTrue("$total 字还算短课，应该一趟发完（现在是 ${p.chunks.size} 段）", p.singlePass)
    }

    @Test
    fun longLectureFallsBackToMaterialCards() {
        val text = "这是一个知识点的句子，用来占位置凑字数。".repeat(1400)
        val p = LlmDigest.plan(note(text, parts = 10))
        assertFalse("两万字以上的长课必须分段整理", p.singlePass)
        assertTrue("应该被切成多段，实际 ${p.chunks.size} 段", p.chunks.size >= 4)
        assertTrue("每段都要有内容", p.chunks.all { it.isNotBlank() })
    }

    @Test
    fun imageCaptionJoinsTheChunk() {
        val n = Note("t", "测试课", 0L, 0L, 0L, "mic")
        n.entries.add(Entry(1000L, "讲二叉树"))
        n.entries.add(Entry(2000L, "", image = "shots/a.jpg", caption = "课件：二叉树定义", analyzed = true))
        val p = LlmDigest.plan(n)
        assertTrue("截图的说明文字也要进转写，AI 才能把图里的要点整理进去", p.chunks[0].contains("课件：二叉树定义"))
    }

    @Test
    fun streamDeltaIsExtracted() {
        assertNull(
            "只带 role 的帧没有正文",
            LlmDigest.deltaOf("""{"choices":[{"delta":{"role":"assistant"},"index":0}]}""")
        )
        assertEquals(
            "## 一、二叉树",
            LlmDigest.deltaOf("""{"choices":[{"delta":{"content":"## 一、二叉树"},"index":0}]}""")
        )
        assertNull("结束帧不解析", LlmDigest.deltaOf("[DONE]"))
        assertNull("不是 JSON 就跳过", LlmDigest.deltaOf(": keep-alive"))
    }

    @Test
    fun streamDeltaSupportsArrayContent() {
        assertEquals(
            "要点",
            LlmDigest.deltaOf("""{"choices":[{"delta":{"content":[{"type":"text","text":"要点"}]}}]}""")
        )
    }

    // ---------------------------------------------------------- 截图进整理稿

    private fun shotNote(): Note {
        val n = Note("t", "测试课", 0L, 0L, 0L, "mic")
        n.entries.add(Entry(1000L, "讲二叉树"))
        n.entries.add(Entry(65_000L, "", image = "shots/a.jpg", caption = "课件：二叉树定义", analyzed = true))
        return n
    }

    /** 两张图、两个时间点，用来看图是不是各自落在自己那一节里。 */
    private fun twoShotNote(): Note {
        val n = Note("t2", "测试课二", 0L, 0L, 0L, "mic")
        n.entries.add(Entry(10_000L, "讲定义"))
        n.entries.add(Entry(20_000L, "", image = "shots/a.jpg", caption = "图一", analyzed = true))
        n.entries.add(Entry(120_000L, "讲遍历"))
        n.entries.add(Entry(130_000L, "", image = "shots/b.jpg", caption = "图二", analyzed = true))
        return n
    }

    /** 截图落在时间上最贴近的那条要点下面（比它早的、晚的都算），而不是堆到文末。 */
    @Test
    fun shotLandsUnderTheNearestLine() {
        val md = LlmDigest.embedImages(
            "## 一、二叉树\n\n- `[00:10]` 讲定义\n- `[01:20]` 讲遍历\n- `[02:00]` 讲性质\n",
            shotNote()
        )
        val lines = md.lines()
        val near = lines.indexOfFirst { it.contains("[01:20]") }
        val image = lines.indexOfFirst { it.contains("![课堂截图") }
        assertTrue("截图要插回正文：$md", image > 0)
        assertTrue("截图要跟着最贴近的那条要点：$md", image - near <= 2)
        assertTrue("时间戳够用的时候不该再退回文末汇总：$md", !md.contains("本课图示"))
    }

    /** 一节课几张图，各自落到自己那一节，不能全挤到最后或全挤到开头。 */
    @Test
    fun eachShotLandsInItsOwnSection() {
        val md = LlmDigest.embedImages(
            "## 一、定义\n\n- `[00:10]` 定义是什么\n### 1.1 存储\n- `[02:00]` 遍历怎么走\n",
            twoShotNote()
        )
        val bar = md.indexOf("[02:00]")
        val first = md.indexOf("shots/a.jpg")
        val second = md.indexOf("shots/b.jpg")
        assertTrue("两张图都要在正文里：$md", first in 0 until second)
        assertTrue("图一要落在「讲定义」那一节：$md", first < bar)
        assertTrue("图二不能跑到最前面：$md", second > bar)
        assertFalse("都放回正文了就不该再有末尾汇总", md.contains("本课图示"))
    }

    /**
     * 图片进正文时只放图本身：图上讲了什么由 AI 整理进周围的要点里，
     * 不再把视觉模型的原文照抄在图下面（同一件事在笔记里出现两遍，而且那还是一段没整理过的话）。
     */
    @Test
    fun imageGoesInAloneWithoutTheRawCaption() {
        val md = LlmDigest.embedImages("## 一、二叉树\n\n- `[00:10]` 讲定义\n", shotNote())
        assertTrue("图要在正文里：$md", md.contains("![课堂截图 01:05](shots/a.jpg)"))
        assertFalse("视觉模型的原文不该原样贴在图下面：$md", md.contains("课件：二叉树定义"))
        assertFalse("也不该留一段带时间戳的说明块：$md", md.contains("`[01:05]`"))
    }

    /** 老提示词留下的 [[IMGn]] / [图示] 记号要擦干净，别出现在成品里。 */
    @Test
    fun leftoverMarkersAreCleanedUp() {
        val md = LlmDigest.embedImages(
            "## 一、二叉树\n\n- `[00:10]` 讲定义\n\n[图示]\n\n`[[IMG1]]`\n\n- `[01:20]` 遍历\n",
            shotNote()
        )
        assertFalse("记号不该留在正文里：$md", md.contains("[[IMG"))
        assertFalse("占位词也不该留着：$md", md.contains("[图示]"))
        assertTrue(md.contains("![课堂截图 01:05](shots/a.jpg)"))
    }

    /** 模型把时间戳全删了也不能丢图，退回文末的「本课图示」。 */
    @Test
    fun noTimestampFallsBackToTheEndSection() {
        val md = LlmDigest.embedImages("## 一、二叉树\n\n- 定义\n", shotNote())
        assertTrue("没有时间戳只能补到末尾：$md", md.contains("本课图示"))
        assertTrue(md.contains("![课堂截图 01:05](shots/a.jpg)"))
    }

    /** 流式预览传 appendFallback = false：正文还没吐出来的时候别先在文末滚一排图。 */
    @Test
    fun streamPreviewDoesNotAppendFallback() {
        val md = LlmDigest.embedImages("## 一、二叉树\n", shotNote(), appendFallback = false)
        assertFalse(md.contains("本课图示"))
    }

    /** 标题行不能当落点，否则图会插在标题和它的正文之间。 */
    @Test
    fun headingIsNotUsedAsAnchor() {
        val md = LlmDigest.embedImages(
            "## 一、二叉树 `[00:00]`\n\n- `[01:00]` 先讲定义\n",
            shotNote()
        )
        val image = md.indexOf("![课堂截图")
        assertTrue("图要落在正文行后面：$md", image > md.indexOf("先讲定义"))
    }

    /** 截图那一行要带着自己的说明和时间戳进素材，AI 才知道图上讲了什么。 */
    @Test
    fun screenshotCaptionRidesWithTheChunk() {
        val chunk = LlmDigest.plan(shotNote()).chunks[0]
        assertTrue("说明文字要跟着转写一起发出去：$chunk", chunk.contains("课件：二叉树定义"))
        assertTrue("截图行的时间戳不能丢：$chunk", chunk.contains("[01:05]"))
    }

    /** 图上识别出的每一条都要单占一行喂给模型：压成一行，模型只会吃掉第一句。 */
    @Test
    fun captionLinesGoAsTheirOwnBlock() {
        val n = Note("t", "测试课", 0L, 0L, 0L, "mic")
        n.entries.add(Entry(1000L, "讲 ARP"))
        n.entries.add(
            Entry(
                65_000L, "", image = "shots/a.jpg", analyzed = true,
                caption = "- 交换机按 MAC 地址表转发\n- 接口1 MAC BL:AC:K0"
            )
        )
        val chunk = LlmDigest.plan(n).chunks[0]
        assertTrue("第一条要进素材：$chunk", chunk.contains("交换机按 MAC 地址表转发"))
        assertTrue("第二条也要进素材：$chunk", chunk.contains("接口1 MAC BL:AC:K0"))
        assertEquals("说明块只占一行头部：$chunk", 1, chunk.lines().count { it.contains("[图示]") })
        assertTrue("图上内容要缩进成块：$chunk", chunk.lines().any { it == "    - 交换机按 MAC 地址表转发" })
    }

    /**
     * 视觉模型爱用「## 定义：」「> 要点：」开头。这些记号要在进素材前就洗掉，
     * 不然整理那一步会把它们当成笔记的小标题抄一遍，整篇就变成「定义 / 作用 / 要点」的模板。
     */
    @Test
    fun captionHeadingsAreStrippedBeforeSending() {
        val n = Note("t", "测试课", 0L, 0L, 0L, "mic")
        n.entries.add(
            Entry(
                65_000L, "", image = "shots/a.jpg", analyzed = true,
                caption = "## 定义：\n> 要点：ARP 记录表存 IP 和 MAC 的对应关系"
            )
        )
        val chunk = LlmDigest.plan(n).chunks[0]
        assertFalse("栏目名记号不该原样发给模型：$chunk", chunk.contains("## 定义"))
        assertFalse("引用记号也不该留着：$chunk", chunk.contains("> 要点"))
        assertTrue("内容本身要留着：$chunk", chunk.contains("    - 要点：ARP 记录表存 IP 和 MAC 的对应关系"))
    }
    @Test
    fun inventedMarkerIsDroppedQuietly() {
        val md = LlmDigest.embedImages("## 一、二叉树\n\n[[IMG7]]\n", shotNote())
        assertFalse("模型自己编的编号不该以原文形式漏出来", md.contains("[[IMG7]]"))
        assertTrue("真正的图还是要补上", md.contains("shots/a.jpg"))
    }

    /**
     * 取消标记是全局的。它曾经只在 digest() 里清零，于是「取消过一次整理」之后，
     * 之后每一次截图分析都被直接判成「已取消」—— 一张图都分析不出来。这里把坑钉住。
     */
    @Test
    fun cancelledFlagDoesNotLeakIntoTheNextJob() = runBlocking<Unit> {
        Prefs.init(ApplicationProvider.getApplicationContext())
        Prefs.llmKey = ""
        LlmDigest.cancelActive()
        assertTrue("取消之后标记应该是打开的", LlmDigest.isCancelled())

        // 没配 Key 会立刻抛错，但那时清零已经做过了；关键是标记有没有被清掉
        runCatching { LlmDigest.analyzeImage("AAAA") }
        assertFalse("上一次的取消标记不能留到下一次任务", LlmDigest.isCancelled())
    }
    /** 模型写在最前面的「题目：xxx」是用来给笔记命名的，正文里不能留这一行。 */
    @Test
    fun topicLineIsPulledOutOfTheBody() {
        val md = "题目：ARP 协议与 ARP 欺骗攻击\n\n> 这节课讲 ARP 怎么工作。\n\n## 一、ARP 协议\n"
        val (topic, body) = LlmDigest.splitTopic(md)
        assertEquals("ARP 协议与 ARP 欺骗攻击", topic)
        assertFalse("题目行不该留在正文里：$body", body.contains("题目："))
        assertTrue("正文其余部分要原样保留：$body", body.contains("## 一、ARP 协议"))
        assertTrue("导语也要留着：$body", body.contains("> 这节课讲 ARP 怎么工作。"))
    }

    /** 正文里正常写「题目：」的地方（例题、习题）不能被当成标题摘走。 */
    @Test
    fun topicWordDeepInTheBodyIsIgnored() {
        val md = "> 导语\n\n## 一、例题\n- 题目：求二叉树的深度\n"
        val (topic, body) = LlmDigest.splitTopic(md)
        assertEquals("", topic)
        assertEquals(md, body)
    }

    /** 模型没写题目时保持原样：绝不能把笔记名换成空字符串。 */
    @Test
    fun missingTopicKeepsTheOldName() {
        val md = "> 导语\n\n## 一、ARP 协议\n"
        val (topic, body) = LlmDigest.splitTopic(md)
        assertEquals("", topic)
        assertEquals(md, body)
        assertEquals("空的题目行不算题目", "", LlmDigest.splitTopic("题目：\n\n> 导语\n").first)
    }
    /** 模型把整篇包进 ``` 时要拆掉 —— 不然排版视图整篇都是代码块，图也插不回去。 */
    @Test
    fun wholeBodyInAFenceIsUnwrapped() {
        val wrapped = "```markdown\n## 一、ARP 协议\n- `[00:10]` 讲定义\n```"
        assertEquals("## 一、ARP 协议\n- `[00:10]` 讲定义", LlmDigest.unwrapFence(wrapped))
        val plain = "## 一、ARP 协议\n- `[00:10]` 讲定义"
        assertEquals("没被包起来就别动它", plain, LlmDigest.unwrapFence(plain))
    }

    /** 模型忘了写题目时，从导语里抠一个名字出来；抠不出来就返回空串，宁可留着原来的名字。 */
    @Test
    fun leadLineBecomesTheNameWhenTopicIsMissing() {
        val md = "> 本节课将介绍Socket的概念、作用以及与TCP/UDP的关系，并通过实例讲解通信过程。\n\n## 一、Socket\n"
        assertEquals("Socket的概念", LlmDigest.topicFromLead(md))
        assertEquals("没有导语就别硬起名字", "", LlmDigest.topicFromLead("## 一、Socket\n"))
        assertEquals("剥完什么都不剩也算了", "", LlmDigest.topicFromLead("> 本节课将。\n"))
    }
    /** 老师没布置作业，模型却留一节「作业与下节预告」用「本节课没有布置作业」占位 —— 整节删掉。 */
    @Test
    fun placeholderHomeworkSectionIsDropped() {
        val md = "## 一、ARP 协议\n- `[00:10]` 讲定义\n\n## 作业与下节预告\n" +
            "（本节课没有布置作业，下节课将讲解网络层的其他协议。）\n"
        val out = LlmDigest.dropEmptyHomework(md)
        assertFalse("占位作业节要删掉：$out", out.contains("作业"))
        assertTrue("别的节要留着：$out", out.contains("## 一、ARP 协议"))
    }

    /** 真布置了作业的课，那一节必须原样留着。 */
    @Test
    fun realHomeworkSectionStays() {
        val md = "## 一、ARP 协议\n- `[00:10]` 讲定义\n\n## 作业与下节预告\n" +
            "- 作业：完成课本 P52 第 3、5 题\n- 下节课讲子网划分\n"
        val out = LlmDigest.dropEmptyHomework(md)
        assertTrue("真作业不能删：$out", out.contains("课本 P52"))
    }
}