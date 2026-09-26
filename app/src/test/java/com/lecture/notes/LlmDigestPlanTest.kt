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

    /** 模型漏放记号的图，也要按时间戳落回「当时那句话」下面，而不是堆到文末。 */
    @Test
    fun missingShotLandsUnderTheLineItFollows() {
        val md = LlmDigest.embedImages(
            "## 一、二叉树\n\n- `[00:10]` 讲定义\n- `[01:20]` 讲遍历\n- `[02:00]` 讲性质\n",
            shotNote(),
            appendMissing = true
        )
        val lines = md.lines()
        val bullet = lines.indexOfFirst { it.contains("[00:10]") }
        val image = lines.indexOfFirst { it.contains("![课堂截图") }
        assertTrue("截图应该被插回正文：$md", image > bullet)
        assertTrue("截图应该紧跟那句话，而不是堆到文末：$md", image - bullet <= 2)
        assertTrue("时间戳够用的时候不该再退回文末汇总：$md", !md.contains("本课图示"))
    }

    @Test
    fun screenshotMarkerRidesWithTheChunk() {
        val chunk = LlmDigest.plan(shotNote()).chunks[0]
        assertTrue("截图行要带 [[IMG1]] 记号，模型才知道这里该插图：$chunk", chunk.contains("[[IMG1]]"))
        assertTrue("说明文字也要照旧带上", chunk.contains("课件：二叉树定义"))
    }

    @Test
    fun imageMarkerTurnsIntoARealImage() {
        val md = LlmDigest.embedImages("## 一、二叉树\n\n[[IMG1]]\n\n- 定义\n", shotNote(), appendMissing = true)
        assertTrue("记号要换成真图片：$md", md.contains("![课堂截图 01:05](shots/a.jpg)"))
        assertFalse("记号不该留在正文里", md.contains("[[IMG"))
        assertFalse("已经放回正文的图不用再补到末尾", md.contains("本课图示"))
    }

    @Test
    fun backtickedMarkerStillBecomesAnImage() {
        val md = LlmDigest.embedImages("## 一、二叉树\n\n- 定义\n\n`[[IMG1]]`\n", shotNote(), appendMissing = true)
        assertTrue("模型给记号套了反引号也要认出来：$md", md.contains("![课堂截图 01:05](shots/a.jpg)"))
    }

    @Test
    fun droppedMarkerIsReplacedByTheImageAtTheEnd() {
        // 模型整段忘了抄记号也不能丢图，统一补到末尾的「本课图示」
        val md = LlmDigest.embedImages("## 一、二叉树\n\n- 定义\n", shotNote(), appendMissing = true)
        assertTrue("漏掉的图要补到末尾：$md", md.contains("本课图示"))
        assertTrue(md.contains("![课堂截图 01:05](shots/a.jpg)"))
    }

    @Test
    fun streamPreviewDoesNotAppendLeftoverImages() {
        // 流式预览每次都要过一遍还原，但补图只能补一次，否则预览里会越滚越长
        val md = LlmDigest.embedImages("## 一、二叉树\n", shotNote(), appendMissing = false)
        assertFalse(md.contains("本课图示"))
    }

    @Test
    fun inventedMarkerIsDroppedQuietly() {
        val md = LlmDigest.embedImages("## 一、二叉树\n\n[[IMG7]]\n", shotNote(), appendMissing = true)
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
