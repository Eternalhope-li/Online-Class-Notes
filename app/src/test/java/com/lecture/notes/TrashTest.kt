package com.lecture.notes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lecture.notes.data.Entry
import com.lecture.notes.data.NoteStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 删除 = 挪进回收站，不是真删。
 *
 * 手一滑删掉一整周的课、还找不回来，是这个 App 里最难受的一种事故：删除必须能后悔，
 * 首页和详情页那条「撤销」才立得住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrashTest {

    private lateinit var ctx: Context
    private lateinit var root: File

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        NoteStore.init(ctx)
        // 每个用例都从空目录开始，别串味
        File(ctx.filesDir, "notes").deleteRecursively()
        NoteStore.init(ctx)
        root = File(ctx.filesDir, "notes")
    }

    @Test
    fun deleteMovesNoteToTrashAndRestoreBringsItBack() {
        val note = NoteStore.create("mic")
        NoteStore.appendEntry(note.id, Entry(1000L, "三次握手是这样建立的"))
        val id = note.id

        NoteStore.delete(id)
        assertTrue("删掉的笔记不该还在列表里", NoteStore.listMeta().none { it.id == id })
        assertNull(NoteStore.load(id))

        assertTrue(NoteStore.restore(id))
        assertEquals(1, NoteStore.listMeta().single { it.id == id }.count)
        assertEquals("三次握手是这样建立的", NoteStore.load(id)?.entries?.first()?.text)
    }

    @Test
    fun restoreIsHarmlessWhenCalledTwice() {
        val note = NoteStore.create("mic")
        NoteStore.appendEntry(note.id, Entry(1000L, "HTTP 的报文结构"))
        NoteStore.delete(note.id)

        assertTrue(NoteStore.restore(note.id))
        assertFalse("已经捞回来了，再点一次撤销不该出问题", NoteStore.restore(note.id))
        assertEquals(1, NoteStore.listMeta().count { it.id == note.id })
    }

    @Test
    fun purgeDropsOldTrashButKeepsFresh() {
        val fresh = NoteStore.create("mic")
        val old = NoteStore.create("mic")
        NoteStore.delete(fresh.id)
        NoteStore.delete(old.id)

        // 假装 old 是八天前删的
        val stamp = File(File(root, ".trash"), old.id + "/.deleted")
        stamp.writeText((System.currentTimeMillis() - 8L * 24 * 3600_000).toString())

        NoteStore.purgeTrash(7)

        assertTrue("刚删的还在回收站里", NoteStore.restore(fresh.id))
        assertFalse("放了八天的该清掉了", NoteStore.restore(old.id))
    }

    @Test
    fun trashedCardShowsTitleAndCounts() {
        val note = NoteStore.create("mic")
        NoteStore.appendEntry(note.id, Entry(1000L, "三次握手是这样建立的"))
        NoteStore.appendEntry(note.id, Entry(2000L, "第二次握手里 SYN 和 ACK 一起发"))
        // 卡片上的标题 / 句数是从 meta.json 读的，先把它写下去（录音时也是这么写的）
        val loaded = NoteStore.load(note.id)!!
        loaded.title = "三次握手"
        NoteStore.saveMeta(loaded)

        NoteStore.delete(note.id)
        val t = NoteStore.trashedNotes().single()
        assertEquals(note.id, t.id)
        assertEquals("三次握手", t.title)
        assertEquals(2, t.count)
        assertTrue("删除时间得记下来，卡片上要写「几分钟前删的」", t.deletedAt > 0)
    }

    @Test
    fun emptyTrashDropsEverything() {
        val a = NoteStore.create("mic")
        val b = NoteStore.create("mic")
        NoteStore.delete(a.id)
        NoteStore.delete(b.id)
        assertEquals(2, NoteStore.trashedNotes().size)

        NoteStore.emptyTrash()
        assertEquals(0, NoteStore.trashedNotes().size)
    }

    @Test
    fun trashIsNotCountedAsNote() {
        val note = NoteStore.create("mic")
        NoteStore.delete(note.id)
        assertEquals(0, NoteStore.listMeta().size)
        assertEquals(0, NoteStore.search("三次握手").size)
    }
}
