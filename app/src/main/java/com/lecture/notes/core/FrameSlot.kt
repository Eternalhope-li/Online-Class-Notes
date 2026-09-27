package com.lecture.notes.core

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 手里永远攥着「最新那一帧」的槽位。
 *
 * 屏幕镜子（VirtualDisplay + ImageReader）一个授权只能建一次，所以图像得一直挂在上面等。
 * 但系统只在画面**有变化**的时候才推新帧：视频一暂停、课件页一停住，阅读器队列就是空的。
 * 这时候要是只管跟系统要「一帧新的」，就只能一直等到超时 —— 用户看到的是「截图失败」，
 * 其实屏幕好端端的。
 *
 * 所以这里把最近收到的那帧留着不放：来新的换掉旧的（旧的交给 [dispose] 收尾），
 * 谁要用帧谁就来拿，拿不到新的就用手里这张兜底。
 *
 * [now] 是给单测用的时钟，注入毫秒。
 */
internal class FrameSlot<T : Any>(
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
    private val dispose: (T) -> Unit = {}
) {

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    private var item: T? = null
    private var stamp = 0L

    /** 收到一帧；被换下来的旧帧交给 [dispose]。 */
    fun push(next: T) {
        val old = lock.withLock {
            val prev = item
            item = next
            stamp += 1
            changed.signalAll()
            prev
        }
        old?.let(dispose)
    }

    /** 手里这帧的编号；0 表示一帧都还没来过。 */
    fun stamp(): Long = lock.withLock { stamp }

    /**
     * 拿着最新一帧干活，[block] 跑完才放手 —— 期间这帧不会被别人换掉或者回收。
     *
     * [firstMs]：一帧都还没有时，最多等这么久。
     * [freshMs]：> 0 表示还想要一帧比手里这张更新的（刚把悬浮球藏起来时用：得等系统重新合成一次，
     * 否则会把圆钮自己也截进去）。画面完全静止、等不到新帧时，就用手里那张，不报失败。
     *
     * 返回 null 只有一种情况：一帧都没等到。
     */
    fun <R> use(freshMs: Long = 0L, firstMs: Long = 0L, block: (T) -> R): R? = lock.withLock {
        if (item == null) await(1L, firstMs)
        if (freshMs > 0L) await(stamp + 1L, freshMs)
        val cur = item ?: return@withLock null
        block(cur)
    }

    /** 清空（换授权、换幕布时用）；手里那帧交给 [dispose]。 */
    fun clear() {
        val old = lock.withLock {
            val prev = item
            item = null
            prev
        }
        old?.let(dispose)
    }

    private fun await(target: Long, ms: Long) {
        if (ms <= 0L) return
        val end = now() + ms
        while (stamp < target) {
            val left = end - now()
            if (left <= 0L) return
            try {
                changed.await(left, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }
}