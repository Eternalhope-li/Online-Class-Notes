package com.lecture.notes

import com.lecture.notes.core.MirrorSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 「一个录屏授权只能建一次镜子」这条规矩。
 *
 * 真机上的表现是：在同一个 MediaProjection 上第二次 createVirtualDisplay() 抛 SecurityException，
 * 系统顺手把整个授权停掉 —— 内录用的就是同一个授权，于是连点两下截图，正在录的课一起没了。
 * 所以下面每条都对着一个真实会发生的坏结果。
 */
class MirrorSlotTest {

    /** 假装是那块 VirtualDisplay。 */
    private class Screen(val name: String)

    @Test
    fun buildsOnlyOnceForTheSameProjection() {
        var builds = 0
        val slot = MirrorSlot<Any, Screen> { builds++; Screen("s") }
        val projection = Any()

        val first = slot.of(projection)
        assertSame(first, slot.of(projection))
        assertSame(first, slot.of(projection))
        assertEquals(1, builds)
    }

    @Test
    fun buildsAgainForANewProjection() {
        var builds = 0
        val slot = MirrorSlot<Any, Screen> { builds++; Screen("s") }
        val a = Any()
        val b = Any()
        slot.of(a)

        val forB = slot.of(b)
        assertSame(forB, slot.of(b))
        assertEquals(2, builds)
        // 换了新授权：手上留的是新的那块，老的那块已经交出去释放了
        assertSame(forB, slot.peek())
    }

    @Test
    fun failedBuildIsNeverRetried() {
        var builds = 0
        val slot = MirrorSlot<Any, Screen> { builds++; null }
        val projection = Any()

        assertNull(slot.of(projection))
        assertNull(slot.of(projection))
        assertNull(slot.of(projection))
        assertEquals(1, builds)
    }

    @Test
    fun clearLetsTheNextGrabStartOver() {
        var builds = 0
        val slot = MirrorSlot<Any, Screen> { builds++; Screen("s") }
        val projection = Any()

        val first = slot.of(projection)
        assertSame(first, slot.clear())
        assertNull(slot.peek())

        val again = slot.of(projection)
        assertSame(again, slot.of(projection))
        assertEquals(2, builds)
    }

    @Test
    fun clearOnEmptySlotIsHarmless() {
        val slot = MirrorSlot<Any, Screen> { Screen("s") }
        assertNull(slot.peek())
        assertNull(slot.clear())
    }

    @Test
    fun clearAlsoForgetsAFailure() {
        var builds = 0
        val slot = MirrorSlot<Any, Screen> { builds++; if (builds == 1) null else Screen("s") }
        val projection = Any()

        assertNull(slot.of(projection))
        slot.clear()
        // 授权作废之后又登记了一个新的（服务重启、用户重开记录）：允许重新建，不会一直卡在失败上
        val again = slot.of(projection)
        assertSame(again, slot.of(projection))
        assertEquals(2, builds)
    }
}