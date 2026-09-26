package com.lecture.notes

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.lecture.notes.data.Entry
import com.lecture.notes.data.NoteStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 截图进笔记这条链路的存储测试：JSONL 里要多存 img / cap / an 三个字段，
 * 而且不能把文字条目弄丢（录音线程还在往同一个文件追加）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShotStoreTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        NoteStore.init(ctx)
    }

    private fun bmp(): Bitmap =
        Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF112233.toInt()) }

    @Test
    fun imageEntryRoundTrip() {
        val note = NoteStore.create("mic")
        val rel = NoteStore.saveShot(note.id, bmp())
        assertNotNull(rel)
        NoteStore.appendImage(note.id, 3000L, rel!!, "图上的定义是二分查找")

        val loaded = NoteStore.load(note.id)!!
        assertEquals(1, loaded.imageCount)
        val e = loaded.entries.single()
        assertEquals(rel, e.image)
        assertEquals("图上的定义是二分查找", e.caption)
        assertTrue(e.isImage)
        assertTrue(NoteStore.shotFile(note.id, rel).exists())
    }

    @Test
    fun savingCaptionKeepsTextEntries() {
        val note = NoteStore.create("mic")
        NoteStore.appendEntry(note.id, Entry(1000L, "第一句话"))
        val rel = NoteStore.saveShot(note.id, bmp())!!
        NoteStore.appendImage(note.id, 2000L, rel, "")
        NoteStore.setImageCaption(note.id, 2000L, rel, "- 要点 A\n- 要点 B", true)

        val loaded = NoteStore.load(note.id)!!
        assertEquals(2, loaded.entries.size)
        assertEquals("第一句话", loaded.entries[0].text)
        assertEquals("- 要点 A\n- 要点 B", loaded.entries[1].caption)
        assertTrue(loaded.entries[1].analyzed)
    }

    @Test
    fun entriesAreSortedByTime() {
        val note = NoteStore.create("mic")
        NoteStore.appendEntry(note.id, Entry(5000L, "后面的句子"))
        val rel = NoteStore.saveShot(note.id, bmp())!!
        NoteStore.appendImage(note.id, 1000L, rel, "")

        val loaded = NoteStore.load(note.id)!!
        assertEquals(1000L, loaded.entries[0].atMs)
        assertEquals(5000L, loaded.entries[1].atMs)
    }

    @Test
    fun mergeEditKeepsImagesInPlace() {
        val original = listOf(
            Entry(1000L, "旧的第一句"),
            Entry(2000L, "", image = "shots/x.jpg", caption = "说明"),
            Entry(3000L, "旧的第二句")
        )
        val edited = listOf(Entry(1000L, "改过的第一句"), Entry(2500L, "新加的一句"))
        val merged = NoteStore.mergeEdit(original, edited)
        assertEquals(3, merged.size)
        assertEquals(1000L, merged[0].atMs)
        assertEquals(2000L, merged[1].atMs)
        assertEquals("shots/x.jpg", merged[1].image)
        assertEquals(2500L, merged[2].atMs)
    }

    @Test
    fun plainTextSkipsImagesButMarkdownKeepsThem() {
        val note = NoteStore.create("mic")
        NoteStore.appendEntry(note.id, Entry(1000L, "一句话"))
        val rel = NoteStore.saveShot(note.id, bmp())!!
        NoteStore.appendImage(note.id, 2000L, rel, "这是说明")

        val loaded = NoteStore.load(note.id)!!
        assertFalse(NoteStore.plainText(loaded).contains("这是说明"))
        val md = NoteStore.markdown(loaded)
        assertTrue(md.contains("(shots/"))
        assertTrue(md.contains("![图示]("))
    }

    @Test
    fun shotsSectionCarriesCaptionAndLink() {
        val note = NoteStore.create("mic")
        val rel = NoteStore.saveShot(note.id, bmp())!!
        NoteStore.appendImage(note.id, 65000L, rel, "- 定义：栈\n- 结论：后进先出")

        val loaded = NoteStore.load(note.id)!!
        val section = NoteStore.shotsSection(loaded)
        assertTrue(section.contains("## 本课图示"))
        assertTrue(section.contains("(shots/"))
        assertTrue(section.contains("`[01:05]`"))
        assertTrue(section.contains("  - 结论：后进先出"))
    }

    @Test
    fun removeImageDeletesEntryAndFile() {
        val note = NoteStore.create("mic")
        val rel = NoteStore.saveShot(note.id, bmp())!!
        NoteStore.appendImage(note.id, 2000L, rel, "说明")
        NoteStore.removeImage(note.id, 2000L, rel)

        val loaded = NoteStore.load(note.id)!!
        assertEquals(0, loaded.imageCount)
        assertFalse(NoteStore.shotFile(note.id, rel).exists())
    }

    @Test
    fun todayShotsReusesOneNotePerDay() {
        val a = NoteStore.todayShots()
        val b = NoteStore.todayShots()
        assertEquals(a.id, b.id)
        assertEquals(NoteStore.SOURCE_SHOT, a.source)
    }
}
