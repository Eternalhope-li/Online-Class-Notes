package com.lecture.notes.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 通知栏那条「AI 正在整理笔记」上的「取消生成」。
 *
 * 两种情况都要能收拾：
 *  - 真的在跑：立刻停掉（`DigestJob.cancel()` 顺手把常驻通知撤掉）；
 *  - 进程已经被系统在后台杀掉、只剩一条假通知：这条广播会把进程拉起来，
 *    发现队列是空的，就把那条通知消灭掉。
 */
class DigestCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION) DigestJob.cancel()
    }

    companion object {
        const val ACTION = "com.lecture.notes.action.CANCEL_DIGEST"
    }
}
