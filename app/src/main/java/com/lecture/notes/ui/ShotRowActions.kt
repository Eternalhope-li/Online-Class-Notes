package com.lecture.notes.ui

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lecture.notes.R
import com.lecture.notes.data.NoteStore
import com.lecture.notes.net.LlmDigest
import com.lecture.notes.util.ImageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 截图行上的两个动作：点开看大图、让 AI 补一段说明。
 *
 * 记录页和笔记详情页都要，抽出来只写一遍 —— 两边的「分析这张图」行为完全一样，
 * 各写一份迟早会走偏。
 */
object ShotRowActions {

    /** 点图看大图。 */
    fun open(act: Activity, noteId: String, row: Row) {
        val rel = row.image ?: return
        act.startActivity(
            Intent(act, ShotActivity::class.java)
                .putExtra(ShotActivity.EXTRA_ID, noteId)
                .putExtra(ShotActivity.EXTRA_REL, rel)
                .putExtra(ShotActivity.EXTRA_AT, row.atMs)
        )
    }

    /**
     * 「AI 分析这张图」：看懂之后把说明写回笔记（[NoteStore.shotsTick] 会让页面自己刷新）。
     * 成功、失败都在页面上说一句；不管跑没跑成，最后都会回调 [onDone] 让调用方收尾。
     */
    fun analyze(
        act: AppCompatActivity,
        noteId: String,
        title: String,
        row: Row,
        onDone: () -> Unit
    ) {
        val rel = row.image
        if (rel == null) {
            onDone()
            return
        }
        if (!LlmDigest.visionReady()) {
            Toast.makeText(act, R.string.shot_need_vision_key, Toast.LENGTH_SHORT).show()
            onDone()
            return
        }
        act.lifecycleScope.launch {
            var err: String? = null
            withContext(Dispatchers.IO) {
                try {
                    val b64 = ImageUtil.visionBase64(NoteStore.shotFile(noteId, rel))
                        ?: throw IllegalStateException(act.getString(R.string.shot_missing))
                    val caption = LlmDigest.analyzeImage(b64, title)
                    if (caption.isBlank()) throw IllegalStateException("模型没有返回内容")
                    NoteStore.setImageCaption(noteId, row.atMs, rel, caption, true)
                } catch (t: Throwable) {
                    err = t.message ?: t.javaClass.simpleName
                    NoteStore.setImageCaption(noteId, row.atMs, rel, row.caption, row.analyzed)
                }
            }
            onDone()
            val msg = if (err == null) {
                act.getString(R.string.shot_analyzed)
            } else {
                act.getString(R.string.shot_analyze_failed, err)
            }
            Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
