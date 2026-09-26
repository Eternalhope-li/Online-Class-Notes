package com.lecture.notes

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.lecture.notes.data.Entry
import com.lecture.notes.data.Note
import com.lecture.notes.data.NoteStore
import com.lecture.notes.util.ImageUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 导出链路的落盘测试。
 *
 * 这里固定在 Android 9（SDK 28）跑，走的是写真实文件那条兼容分支，
 * 所以能直接检查目录结构；Android 10+ 那支走 MediaStore，靠真机验证。
 *
 * 盯住两件事：
 * 1. md 和 shots/ 必须同级，md 里 `](shots/x.jpg)` 的相对路径才连得上图；
 * 2. exportAll 要一次给出两份产物：Markdown + 排好版的 HTML。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExportTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        NoteStore.init(ctx)
    }

    private fun bmp(): Bitmap =
        Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF445566.toInt()) }

    /** 一份「转写一句话 + 一张带说明的截图 + 整理稿」的笔记。 */
    private fun noteWithShot(): Pair<Note, String> {
        val note = NoteStore.create("mic")
        NoteStore.appendEntry(note.id, Entry(1000L, "二分查找的前提是数组有序"))
        val rel = NoteStore.saveShot(note.id, bmp())!!
        NoteStore.appendImage(note.id, 2000L, rel, "- 图上是区间收缩的过程")
        val loaded = NoteStore.load(note.id)!!
        NoteStore.saveDigest(note.id, "# 二分查找\n\n" + NoteStore.shotsSection(loaded))
        return NoteStore.load(note.id)!! to rel
    }

    @Test
    fun markdownAndShotsSitInSameFolder() {
        val (note, rel) = noteWithShot()
        val mdPath = NoteStore.exportMarkdown(ctx, note)
        val mdFile = File(mdPath)
        assertTrue("md 没落盘：$mdPath", mdFile.exists())
        assertEquals("md", mdFile.extension)

        val md = mdFile.readText(Charsets.UTF_8)
        assertTrue("md 里应该用相对路径引用截图", md.contains("](shots/"))
        assertTrue("md 里应该有整理稿", md.contains("# 二分查找"))
        assertTrue("整理稿后面要附原始转写", md.contains("原始转写"))

        // 关键：相对路径是相对 md 自己所在目录算的，图片必须就在它的同级 shots/ 下
        val shot = File(mdFile.parentFile, rel)
        assertTrue("图片要和 md 同级：${shot.absolutePath}", shot.exists())
        assertTrue("图片内容不该是空的", shot.length() > 0L)
    }

    @Test
    fun exportAllGivesMarkdownAndHtml() {
        val (note, _) = noteWithShot()
        val (mdPath, htmlPath) = NoteStore.exportAll(ctx, note, null)
        assertTrue("Markdown 后缀不对：$mdPath", mdPath.endsWith(".md"))
        assertTrue("HTML 后缀不对：$htmlPath", htmlPath.endsWith(".html"))
        assertTrue(File(mdPath).exists())
        assertTrue(File(htmlPath).exists())

        // 两份文件（和 shots/）要在同一个文件夹里：打开「下载」一眼就能看出它们是一套
        assertEquals("md 和 html 应该放在同一个文件夹", File(mdPath).parentFile, File(htmlPath).parentFile)

        val html = File(htmlPath).readText(Charsets.UTF_8)
        assertTrue("HTML 应该内嵌 base64 图片", html.contains(ImageUtil.DATA_HEAD))
        assertFalse("HTML 是单文件，不该再有相对路径", html.contains("](shots/"))
        assertTrue("HTML 里应该有正文", html.contains("二分查找"))
        assertTrue("截图要渲染成真的图片标签", html.contains("<figure class=\"shot\"><img src=\""))
        assertFalse("图片的 Markdown 记号不该漏进正文", html.contains("![图示]"))
    }

    /** 没整理稿时导的是原始转写：截图夹在要点行中间，也必须渲染成图片，不能把 base64 当文本吐出来。 */
    @Test
    fun htmlOfRawTranscriptRendersInlineShot() {
        val note = NoteStore.create("mic")
        NoteStore.appendEntry(note.id, Entry(1000L, "二分查找的前提是数组有序"))
        val rel = NoteStore.saveShot(note.id, bmp())!!
        NoteStore.appendImage(note.id, 2000L, rel, "- 图上是区间收缩的过程")
        val n = NoteStore.load(note.id)!!

        val html = File(NoteStore.exportHtml(ctx, n, null)).readText(Charsets.UTF_8)
        assertTrue("行内截图要渲染成 <img>", html.contains("<img src=\"" + ImageUtil.DATA_HEAD))
        assertFalse("Markdown 图片记号不该漏进正文", html.contains("!["))
    }

    @Test
    fun exportHtmlPrefersTheMarkdownGiven() {
        val (note, _) = noteWithShot()
        val htmlPath = NoteStore.exportHtml(ctx, note, "# 页面上刚改过的整理稿")
        val html = File(htmlPath).readText(Charsets.UTF_8)
        assertTrue("传进来的整理稿应该盖过磁盘上那份", html.contains("页面上刚改过的整理稿"))
    }

    @Test
    fun htmlAndMarkdownDoNotCollide() {
        val (note, _) = noteWithShot()
        val (mdPath, htmlPath) = NoteStore.exportAll(ctx, note, null)
        assertFalse("两份产物不能是同一个文件", File(mdPath).canonicalPath == File(htmlPath).canonicalPath)
    }
}
