package com.lecture.notes.ui

/**
 * 记录页的列表 = 转写行 + 本课截图，按时间戳混排。
 *
 * 抽成纯函数是因为这里最容易写错：截图要插回它当时所在的位置，而不是一股脑堆到末尾。
 * [sortedBy] 是稳定排序，所以同一时刻文字在前、图在后。
 */
internal fun mergeRecordRows(text: List<Row>, shots: List<Row>): List<Row> =
    (text + shots).sortedBy { it.atMs }
