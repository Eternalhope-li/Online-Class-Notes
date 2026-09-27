package com.lecture.notes

import com.lecture.notes.ui.Row
import com.lecture.notes.ui.mergeRecordRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 记录页把截图和转写混在一起显示：顺序错了就会看到「图跑到句子后面」。 */
class RecordRowsTest {

    private fun line(at: Long, text: String) = Row(atMs = at, text = text, star = false)

    private fun shot(at: Long, rel: String) =
        Row(atMs = at, text = "", star = false, image = rel, caption = "")

    @Test
    fun `截图按时间戳插回转写里`() {
        val text = listOf(line(1000, "a"), line(3000, "b"))
        val merged = mergeRecordRows(text, listOf(shot(2000, "s1")))
        assertEquals(listOf(1000L, 2000L, 3000L), merged.map { it.atMs })
        assertEquals("s1", merged[1].image)
    }

    @Test
    fun `同一时刻文字在前图在后`() {
        val merged = mergeRecordRows(listOf(line(5000, "边听边记")), listOf(shot(5000, "s1")))
        assertEquals(null, merged[0].image)
        assertEquals("s1", merged[1].image)
    }

    @Test
    fun `多张截图各回各的位置`() {
        val text = listOf(line(1000, "a"), line(2000, "b"), line(3000, "c"))
        val merged = mergeRecordRows(text, listOf(shot(1500, "s1"), shot(2500, "s2")))
        assertEquals(listOf(1000L, 1500L, 2000L, 2500L, 3000L), merged.map { it.atMs })
    }

    @Test
    fun `没有截图时就是原来的转写`() {
        val text = listOf(line(1000, "a"), line(2000, "b"))
        assertEquals(text, mergeRecordRows(text, emptyList()))
    }

    @Test
    fun `只有截图也排得出来`() {
        val merged = mergeRecordRows(emptyList(), listOf(shot(9, "s1"), shot(3, "s2")))
        assertEquals(listOf(3L, 9L), merged.map { it.atMs })
        assertTrue(merged.all { it.image != null })
    }
}
