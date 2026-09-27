package com.lecture.notes

import com.lecture.notes.core.FrameSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * 「画面不动就没有新帧」这条真机规矩。
 *
 * 真机上碰到的是：视频一暂停（或者课件页一直停着），系统就不往镜子上推帧了，
 * 于是截屏一路等到超时 -> 气泡显示「截图失败」，可屏幕明明亮着、内容明明就在那儿。
 * 所以这里每条都对着一个真实会发生的坏结果。
 */
class FrameSlotTest {

    private class Frame(val id: Int)

    /** 记下哪些帧被收走了，方便验证「旧帧不会漏掉不关」。 */
    private val disposed = mutableListOf<Frame>()
    private fun slot() = FrameSlot<Frame>(dispose = { disposed += it })

    @Test
    fun useReturnsNothingWhenThereIsNoFrameAndNobodyWaits() {
        assertNull(slot().use(freshMs = 0L, firstMs = 0L) { it })
    }

    @Test
    fun waitsForTheFirstFrameInsteadOfFailing() {
        val s = slot()
        thread { Thread.sleep(60); s.push(Frame(1)) }

        // 以前的老写法就是这里直接返回 null —— 用户看到「截图失败」
        val got = s.use(freshMs = 0L, firstMs = 2_000L) { it.id }
        assertEquals(1, got)
    }

    @Test
    fun givesUpAfterTheFirstFrameTimeout() {
        val s = slot()
        val began = System.nanoTime()
        assertNull(s.use(freshMs = 0L, firstMs = 120L) { it })
        val cost = (System.nanoTime() - began) / 1_000_000L
        assertTrue("应该等满超时再放弃，实际 $cost ms", cost >= 100L)
    }

    @Test
    fun keepsOnlyTheLatestFrameAndDisposesTheRest() {
        val s = slot()
        val a = Frame(1)
        val b = Frame(2)
        val c = Frame(3)
        s.push(a)
        s.push(b)
        s.push(c)

        assertEquals(listOf(a, b), disposed)
        assertSame(c, s.use { it })
    }

    @Test
    fun stampCountsEveryFrame() {
        val s = slot()
        assertEquals(0L, s.stamp())
        s.push(Frame(1))
        s.push(Frame(2))
        assertEquals(2L, s.stamp())
    }

    @Test
    fun freshWaitPrefersTheFrameThatJustArrived() {
        val s = slot()
        val stale = Frame(1)
        val fresh = Frame(2)
        s.push(stale)
        thread { Thread.sleep(80); s.push(fresh) }

        // 圆钮刚藏起来：要的是藏好之后的新帧，不能把圆钮自己截进去
        val got = s.use(freshMs = 2_000L) { it }
        assertSame(fresh, got)
    }

    @Test
    fun freshWaitFallsBackToTheHeldFrameWhenTheScreenIsStanding() {
        val s = slot()
        val frozen = Frame(1)
        s.push(frozen)

        val began = System.nanoTime()
        val got = s.use(freshMs = 150L) { it }
        val cost = (System.nanoTime() - began) / 1_000_000L
        assertSame(frozen, got)
        assertTrue("等不到新帧得等到超时，实际 $cost ms", cost >= 120L)
    }

    @Test
    fun clearDisposesTheHeldFrameAndEmptiesTheSlot() {
        val s = slot()
        val a = Frame(1)
        s.push(a)
        s.clear()

        assertEquals(listOf(a), disposed)
        assertNull(s.use(freshMs = 0L, firstMs = 0L) { it })
    }

    /** 取帧的活儿里抛了异常，锁也得放开 —— 否则后面每一次截图都卡死。 */
    @Test(timeout = 3_000L)
    fun aFailedGrabStillReleasesTheSlot() {
        val s = slot()
        val a = Frame(1)
        s.push(a)
        try {
            s.use { throw IllegalStateException("boom") }
        } catch (_: IllegalStateException) {
        }

        val b = Frame(2)
        s.push(b)
        assertSame(b, s.use { it })
        assertEquals(listOf(a), disposed)
    }
}