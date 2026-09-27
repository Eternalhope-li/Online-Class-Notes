package com.lecture.notes.core

/**
 * 「一个录屏授权只能建一次镜子」这条规矩的实现。
 *
 * 系统（Android 14 起）不允许在同一个 MediaProjection 上第二次调用 createVirtualDisplay()：
 * 会抛 SecurityException，而且顺手把整个授权停掉。内录用的正是同一个授权，
 * 所以多建一次镜子就能把正在录的课一起掐断 —— 这个类保证只建一次，并且建失败之后不再重试
 * （那时候授权已经废了，重试只会重复伤害）。
 *
 * 单独抽出来是为了能单测：这段写错会让录音被悄悄掐断，在真机上很难查。
 */
internal class MirrorSlot<T : Any, M : Any>(private val build: (T) -> M?) {

    private val lock = Any()
    private var owner: T? = null
    private var value: M? = null
    private var dead: T? = null

    /** 这个授权对应的镜子；同一个授权只会开工一次。 */
    fun of(projection: T): M? = synchronized(lock) {
        val ready = value
        if (ready != null && owner === projection) return ready
        if (dead === projection) return null
        val made = build(projection)
        value = made
        owner = if (made != null) projection else null
        if (made == null) dead = projection
        made
    }

    /** 手里的镜子（不触发生成）。 */
    fun peek(): M? = synchronized(lock) { value }

    /** 授权没了：镜子作废，下一个授权重新开工。返回被丢掉的那个，交给调用方去释放。 */
    fun clear(): M? = synchronized(lock) {
        val old = value
        value = null
        owner = null
        dead = null
        old
    }
}