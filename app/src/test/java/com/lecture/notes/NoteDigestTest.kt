package com.lecture.notes

import com.lecture.notes.data.Entry
import com.lecture.notes.data.Note
import com.lecture.notes.util.NoteDigest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离线整理算法的回归测试：喂一段「像真课堂」的转写，检查整理稿是不是人话。
 * 直接跑：gradlew testReleaseUnitTest
 */
class NoteDigestTest {

    private fun note(vararg lines: Pair<Long, String>): Note {
        val n = Note(
            id = "test",
            title = "数据结构 第 3 讲",
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L,
            durationMs = 40 * 60 * 1000L,
            source = "internal"
        )
        for ((ms, text) in lines) n.entries.add(Entry(ms, text))
        return n
    }

    private val transcript = arrayOf(
        0L to "嗯，好，那我们今天接着上次的内容讲。",
        3_000L to "今天主要讲二叉树，包括它的定义、性质还有遍历方式。",
        9_000L to "首先说定义，二叉树是指每个节点最多有两个子节点的树结构。",
        16_000L to "这两个子节点分别叫做左子树和右子树。",
        23_000L to "注意，左子树和右子树的顺序是不能颠倒的，这是二叉树和有根无序树的区别。",
        31_000L to "那么第二个知识点是二叉树的性质。",
        37_000L to "性质一，在二叉树的第 i 层上最多有 2 的 i 减 1 次方个节点。",
        46_000L to "然后性质二，深度为 k 的二叉树最多有 2 的 k 次方减 1 个节点。",
        55_000L to "这两个公式考试经常考，要记住。",
        61_000L to "好，我们接着说遍历，二叉树的遍历分为四种。",
        68_000L to "分别是前序遍历、中序遍历、后序遍历和层序遍历。",
        76_000L to "前序遍历的顺序是根节点、左子树、右子树。",
        84_000L to "中序遍历的顺序是左子树、根节点、右子树。",
        92_000L to "后序遍历的顺序是左子树、右子树、根节点。",
        100_000L to "嗯，这里注意，中序遍历二叉搜索树得到的是一个有序序列，这点很重要。",
        110_000L to "也就是说，如果给你一个二叉搜索树，你中序遍历一下就能排好序。",
        119_000L to "时间复杂度是 O 的 n。",
        124_000L to "接下来我们讲最后一个部分，二叉树的存储结构。",
        131_000L to "存储结构有两种，一种是顺序存储，用数组存；另一种是链式存储，用指针。",
        141_000L to "完全二叉树适合用顺序存储，因为它不会浪费太多空间。",
        150_000L to "一般二叉树用链式存储更省空间，每个节点存一个左指针和右指针。",
        160_000L to "最后总结一下，今天讲了二叉树的定义、五个性质、四种遍历和两种存储结构。",
        170_000L to "作业是课后习题 3-1 到 3-8，下节课讲二叉搜索树。",
        178_000L to "好，今天就到这里，下课。"
    )

    @Test
    fun digestIsStructuredAndShort() {
        val n = note(*transcript)
        val (md, r) = NoteDigest.build(n)

        println("================ 整理稿 ================")
        println(md)
        println("========================================")

        assertTrue("应该分出多节", r.sections.size >= 3)
        assertTrue("要点要明显少于原句", r.keptCount < transcript.size)
        assertTrue("要点不能是空的", r.keptCount >= 6)

        val blob = r.sections.joinToString(" ") { it.title }
        assertTrue("术语里应该有二叉树，实际：$blob", blob.contains("二叉树") || md.contains("二叉树"))

        val kw = r.keywords.joinToString(",")
        assertTrue("关键词应该抽到二叉树，实际：$kw", r.keywords.any { it.contains("二叉树") })

        assertTrue("不应该把「嗯，好，那我们今天接着上次的内容讲」当成知识点",
            r.sections.none { s -> s.bullets.any { it.text.startsWith("嗯，好") } })
        assertTrue("作业那句应该被保留", md.contains("作业"))
    }

    @Test
    fun termsHaveNoHalfWordFragments() {
        val n = note(
            0L to "今天主要讲二叉树。",
            3000L to "下节课讲二叉搜索树。",
            6000L to "二叉树很重要，二叉树的性质也要记。",
            9000L to "我们看二叉树的遍历方式。"
        )
        val t = NoteDigest.terms(n.entries, 20)
        println("terms = $t")
        assertTrue("不应该出现半个词 讲二叉，实际：$t", t.none { it == "讲二叉" })
        assertTrue("应该抽到二叉树，实际：$t", t.any { it == "二叉树" })
    }
    @Test
    fun emptyNoteDoesNotCrash() {
        val n = note()
        val (md, r) = NoteDigest.build(n)
        assertTrue(r.sections.isEmpty())
        assertTrue(md.isNotBlank())
    }

    @Test
    fun fillerOnlyNoteProducesNothing() {
        val n = note(
            0L to "嗯嗯嗯。",
            1000L to "对吧对吧。",
            2000L to "哦哦哦。"
        )
        val (_, r) = NoteDigest.build(n)
        assertTrue("全是废话应该整理不出东西", r.sections.isEmpty())
    }

    /** 截图要插在它当时所在的小节里，不能一股脑堆到文末。 */
    @Test
    fun screenshotLandsInItsOwnSection() {
        val n = note(*transcript)
        n.entries.add(Entry(76_000L, "", image = "shots/a.jpg", caption = "板书：四种遍历的顺序", analyzed = true))
        n.entries.sortBy { it.atMs }
        val md = NoteDigest.build(n).first

        assertTrue("截图要出现在整理稿里：$md", md.contains("![课堂截图 01:16](shots/a.jpg)"))
        assertTrue("说明文字要跟着图一起出现", md.contains("板书：四种遍历的顺序"))
        assertTrue("截图前面应该已经有小节标题", md.substringBefore("![课堂截图 01:16]").contains("## "))
        assertTrue("已经插进小节的图不该再堆到文末", !md.contains("本课图示"))
        // 图要落在「当时正在讲它的那句话」下面，而不是整节的末尾
        val lines = md.lines()
        val at = lines.indexOfFirst { it.contains("![课堂截图 01:16]") }
        assertTrue("图应该落在正文里：$md", at > 0)
        assertTrue(
            "图应该紧跟在上一条要点后面，实际上一行是：" + lines[at - 1],
            lines[at - 1].startsWith("- `[")
        )
    }

    /** 早于第一条要点、或落在两节缝隙里的截图，也要落进正文，不能漏到文末。 */
    @Test
    fun screenshotOutsideEverySectionStillLandsInTheBody() {
        val n = note(*transcript)
        n.entries.add(Entry(1_000L, "", image = "shots/early.jpg", caption = "板书：本讲提纲", analyzed = true))
        n.entries.add(Entry(540_000L, "", image = "shots/late.jpg", caption = "板书：存储结构对比", analyzed = true))
        n.entries.sortBy { it.atMs }
        val md = NoteDigest.build(n).first

        assertTrue("两张图都要在整理稿里：$md", md.contains("shots/early.jpg"))
        assertTrue("两张图都要在整理稿里：$md", md.contains("shots/late.jpg"))
        assertTrue("图片不该再堆到文末的「本课图示」：$md", !md.contains("本课图示"))
        assertTrue("早于第一条要点的图要落在第一节的正文里", md.indexOf("shots/early.jpg") > md.indexOf("## "))
    }
}
