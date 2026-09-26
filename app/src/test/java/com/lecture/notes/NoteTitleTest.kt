package com.lecture.notes

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.lecture.notes.data.Entry
import com.lecture.notes.data.NoteStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 默认标题：结束记录时不该只叫「网课笔记 11:15」，要从这次记录的内容里取字。
 * 没内容才退回时间戳，改过的名字要能落盘。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteTitleTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        NoteStore.init(ctx)
    }

    @Test
    fun usesContentAsTitle() {
        val note = NoteStore.create("mic")
        NoteStore.appendEntry(note.id, Entry(1000L, "今天讲二叉树的遍历"))
        assertEquals("今天讲二叉树的遍历", NoteStore.suggestTitle(note.id))
    }

    @Test
    fun truncatesToMaxChars() {
        val note = NoteStore.create("mic")
        NoteStore.appendEntry(note.id, Entry(1000L, "今天讲二叉树的遍历和它的递归实现"))
        // 切到 12 个字正好落在「和它的」上，读起来是半截话，索性退到「遍历」为止
        assertEquals("今天讲二叉树的遍历", NoteStore.suggestTitle(note.id, 12))
    }

    @Test
    fun skipsFillerOpening() {
        val note = NoteStore.create("mic")
        NoteStore.appendEntry(note.id, Entry(1000L, "所以呢这期给大家。"))
        NoteStore.appendEntry(note.id, Entry(2000L, "好，今天我们来讲一下HTTP协议的基础。"))
        assertEquals("今天我们来讲一下HTTP协议", NoteStore.suggestTitle(note.id))
    }

    @Test
    fun skipsAdLine() {
        val note = NoteStore.create("mic")
        NoteStore.appendEntry(note.id, Entry(1000L, "记得点赞关注三连"))
        NoteStore.appendEntry(note.id, Entry(2000L, "三次握手是这样建立的"))
        assertEquals("三次握手是这样建立的", NoteStore.suggestTitle(note.id))
    }

    @Test
    fun joinsTwoShortOpenings() {
        val note = NoteStore.create("mic")
        NoteStore.appendEntry(note.id, Entry(1000L, "先讲定义"))
        NoteStore.appendEntry(note.id, Entry(2000L, "再讲三个例子"))
        assertEquals("先讲定义再讲三个例子", NoteStore.suggestTitle(note.id))
    }

    @Test
    fun joinsFollowingLinesUntilEnough() {
        val note = NoteStore.create("mic")
        NoteStore.appendEntry(note.id, Entry(1000L, "先看定义"))
        NoteStore.appendEntry(note.id, Entry(2000L, "再看三个例子"))
        assertEquals("先看定义再看三个例子", NoteStore.suggestTitle(note.id))
    }

    @Test
    fun stripsSpacesAndLeadingPunctuation() {
        val note = NoteStore.create("mic")
        NoteStore.appendEntry(note.id, Entry(1000L, "， 这是 开头 "))
        assertEquals("这是开头", NoteStore.suggestTitle(note.id))
    }

    @Test
    fun skipsImageEntries() {
        val note = NoteStore.create("mic")
        val bmp = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        val rel = NoteStore.saveShot(note.id, bmp)!!
        NoteStore.appendImage(note.id, 500L, rel, "图上的公式")
        NoteStore.appendEntry(note.id, Entry(1000L, "板书讲的是极限"))
        assertEquals("板书讲的是极限", NoteStore.suggestTitle(note.id))
    }

    @Test
    fun fallsBackToStampWhenEmpty() {
        val note = NoteStore.create("mic")
        assertTrue(NoteStore.suggestTitle(note.id).startsWith("网课笔记"))
    }

    @Test
    fun skipsManualMarkerLine() {
        val note = NoteStore.create("mic")
        NoteStore.appendEntry(note.id, Entry(0L, ctx.getString(R.string.star_marker), true))
        NoteStore.appendEntry(note.id, Entry(1000L, "极限的定义"))
        assertEquals("极限的定义", NoteStore.suggestTitle(note.id))
    }

    @Test
    fun renamedTitleSurvivesReload() {
        val note = NoteStore.create("mic")
        NoteStore.appendEntry(note.id, Entry(1000L, "随便记了点东西"))
        NoteStore.rename(note.id, "极限的定义")
        assertEquals("极限的定义", NoteStore.load(note.id)!!.title)
    }
}
