package com.lecture.notes

import com.lecture.notes.util.MiniMarkdown
import com.lecture.notes.util.MiniMarkdown.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 整理稿里的截图语法：`![说明](shots/x.jpg)` 要变成独立的图片块，HTML 里是 figure。 */
class ShotMarkdownTest {

    @Test
    fun parsesStandaloneImage() {
        val blocks = MiniMarkdown.parse("![课堂截图 01:05](shots/a.jpg)")
        assertEquals(1, blocks.size)
        val img = blocks[0] as Block.Image
        assertEquals("shots/a.jpg", img.src)
        assertEquals("课堂截图 01:05", img.alt)
    }

    @Test
    fun parsesImageWrittenAsBullet() {
        val blocks = MiniMarkdown.parse("- ![图示](shots/b.jpg)")
        val img = blocks.single() as Block.Image
        assertEquals("shots/b.jpg", img.src)
    }

    @Test
    fun imageBetweenTextKeepsOtherBlocks() {
        val md = "## 本课图示\n\n![图示](shots/c.jpg)\n\n- `[00:10]` 一句话"
        val blocks = MiniMarkdown.parse(md)
        assertTrue(blocks[0] is Block.Heading)
        assertTrue(blocks[1] is Block.Image)
        assertTrue(blocks[2] is Block.Bullet)
    }

    @Test
    fun htmlRendersFigure() {
        val html = MiniMarkdown.toHtml("![课堂截图](shots/a.jpg)", "标题")
        assertTrue(html.contains("<figure class=\"shot\">"))
        assertTrue(html.contains("src=\"shots/a.jpg\""))
        assertTrue(html.contains("<figcaption>课堂截图</figcaption>"))
    }

    @Test
    fun plainTextMentionsImage() {
        assertTrue(MiniMarkdown.plain("![课堂截图](shots/a.jpg)").contains("[图片]"))
    }

    @Test
    fun demoNoteStillParses() {
        assertTrue(MiniMarkdown.parse(com.lecture.notes.util.DemoNote.markdown()).isNotEmpty())
    }
}
