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

    /** 截图按时间戳落回「当时那句话」下面，而不是堆到文末。 */
    @Test
    fun shotLandsUnderTheLineItFollows() {
        val md = LlmDigest.embedImages(
            "## 一、二叉树\n\n- `[00:10]` 讲定义\n- `[01:20]` 讲遍历\n- `[02:00]` 讲性质\n",
            shotNote()
        )
        val lines = md.lines()
        val bullet = lines.indexOfFirst { it.contains("[00:10]") }
        val image = lines.indexOfFirst { it.contains("![课堂截图") }
        assertTrue("截图应该被插回正文：$md", image > bullet)
        assertTrue("截图应该紧跟那句话，而不是堆到文末：$md", image - bullet <= 2)
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

    /** 图旁边要带着它自己的看图说明，不能只剩一张孤零零的图。 */
    @Test
    fun shotKeepsItsCaptionNextToIt() {
        val md = LlmDigest.embedImages("## 一、二叉树\n\n- `[00:10]` 讲定义\n", shotNote())
        assertTrue("说明要跟图在一起：$md", md.contains("课件：二叉树定义"))
        assertTrue("说明要带自己的时间戳：$md", md.contains("`[01:05]`"))
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
}
