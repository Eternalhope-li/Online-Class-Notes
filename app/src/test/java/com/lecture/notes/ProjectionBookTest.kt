package com.lecture.notes

import com.lecture.notes.core.ProjectionBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 共享授权账本：内录和悬浮截图共用同一个 MediaProjection，
 * 谁先退出都不能把对方掐掉，必须等最后一个走了才停。
 */
class ProjectionBookTest {

    private val record = "record"
    private val shot = "shot"

    @Test
    fun keepsProjectionWhileAnotherOwnerIsUsingIt() {
        val book = ProjectionBook<String>()
        book.put("p1", record)
        book.put("p1", shot)

        // 录音结束不能让还在截图的那边断掉
        assertNull(book.drop(record))
        assertTrue(book.isReady())
        assertEquals("p1", book.get())
        assertTrue(book.has(shot))
        assertFalse(book.has(record))
    }

    @Test
    fun stopsOnlyWhenLastOwnerLeaves() {
        val book = ProjectionBook<String>()
        book.put("p1", record)
        book.put("p1", shot)

        assertNull(book.drop(shot))
        assertEquals("p1", book.drop(record))
        assertFalse(book.isReady())
        assertNull(book.get())
    }

    @Test
    fun singleOwnerStopsRightAway() {
        val book = ProjectionBook<String>()
        book.put("p1", shot)
        assertEquals("p1", book.drop(shot))
        assertNull(book.get())
    }

    @Test
    fun droppingUnknownOwnerChangesNothing() {
        val book = ProjectionBook<String>()
        book.put("p1", record)
        assertNull(book.drop(shot))
        assertEquals("p1", book.get())
        assertTrue(book.isReady())
    }

    @Test
    fun publishingTwiceFromSameOwnerDoesNotLeak() {
        val book = ProjectionBook<String>()
        book.put("p1", record)
        book.put("p2", record)
        assertEquals("p2", book.get())
        // 同一个使用方登记两次也只算一票，退出一次就够
        assertEquals("p2", book.drop(record))
        assertNull(book.get())
    }
}
