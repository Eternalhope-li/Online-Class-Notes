package com.lecture.notes.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import com.lecture.notes.R

/**
 * 「显示在其他应用上层」权限（悬浮窗）。
 *
 * 悬浮截图圆钮离不了它，可 Android 没有「弹一次框就给我」的说法：只能跳到系统的开关页让用户自己打开，
 * 回来之后我们再检查一次。这个类把这套动作收在一处，省得每个页面各写一遍：
 *
 * - [ensure]：没权限就解释一句、跳过去，并记住「回来之后要接着做的那件事」；有权限就直接做。
 * - [resume]：页面在自己的 onResume 里调一次 —— 用户从系统设置回来，权限到手就把那件事接着做完。
 *
 * 一次只挂一件事，而且用户点了「这次不用」就把它清掉，不会反复弹。
 */
object OverlayPerm {

    /** 权限到手之后要接着做的事；null 表示没有在等。 */
    private var pending: (() -> Unit)? = null

    fun granted(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /** 有权限就直接 [then]，没有就先问问。 */
    fun ensure(act: Activity, then: () -> Unit) {
        if (granted(act)) {
            then()
            return
        }
        if (pending != null) return
        pending = then
        try {
            AlertDialog.Builder(act)
                .setTitle(R.string.overlay_ask_title)
                .setMessage(R.string.overlay_ask_msg)
                .setPositiveButton(R.string.overlay_ask_go) { _, _ -> jump(act) }
                .setNegativeButton(R.string.overlay_ask_later) { _, _ -> pending = null }
                .show()
        } catch (t: Throwable) {
            // 页面正好在收尾（比如记录刚停）就画不出对话框，别把它带崩
            pending = null
        }
    }

    /** 页面 onResume 里调：从系统设置回来时，把没做完的事接上。 */
    fun resume(act: Activity) {
        val then = pending ?: return
        pending = null
        if (granted(act)) then()
    }

    /** 去系统的开关页；各家 ROM 的入口名不一样，给几个兜底。 */
    private fun jump(act: Activity) {
        val uri = Uri.parse("package:${act.packageName}")
        val tries = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
        )
        for (intent in tries) {
            try {
                act.startActivity(intent)
                return
            } catch (_: Throwable) {
            }
        }
    }
}