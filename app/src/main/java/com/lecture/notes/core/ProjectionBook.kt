package com.lecture.notes.core

/**
 * 共享授权的小账本。
 *
 * 系统规定一个 App 同时只能有一个 MediaProjection，多申请一次就会把上一个掐掉，
 * 所以内录音频和悬浮截图必须共用同一个：谁在用要记账，最后一个走了才真正停。
 * 单独抽出来是为了能单测——这段写错会让录音被悄悄掐断，很难查。
 */
internal class ProjectionBook<T : Any> {

    private val lock = Any()
    private var item: T? = null
    private val owners = HashSet<String>()

    /** 登记一个使用方。 */
    fun put(value: T, from: String) {
        synchronized(lock) {
            item = value
            owners.add(from)
        }
    }

    /** 现成能用的授权；null 表示得去弹授权框。 */
    fun get(): T? = synchronized(lock) { item }

    /** 使用方退出；只有没人用了才返回要真正停掉的授权，否则返回 null。 */
    fun drop(from: String): T? = synchronized(lock) {
        owners.remove(from)
        if (owners.isNotEmpty()) return null
        val dead = item
        item = null
        dead
    }

    fun isReady(): Boolean = synchronized(lock) { item != null }

    fun has(from: String): Boolean = synchronized(lock) { owners.contains(from) }
}
