package com.lecture.notes.util

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val SOURCE_MIC = "mic"
    const val SOURCE_INTERNAL = "internal"

    const val DEFAULT_LLM_BASE = "https://open.bigmodel.cn/api/paas/v4"
    const val DEFAULT_LLM_MODEL = "glm-4-flash"

    /** 视觉模型默认用智谱的免费模型，一张截图差不多一两千 token，不花钱。 */
    const val DEFAULT_VISION_MODEL = "glm-4v-flash"

    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        sp = c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    var audioSource: String
        get() = sp.getString("audio_source", SOURCE_MIC) ?: SOURCE_MIC
        set(v) = sp.edit().putString("audio_source", v).apply()

    var numThreads: Int
        get() = sp.getInt("threads", 2).coerceIn(1, 4)
        set(v) = sp.edit().putInt("threads", v.coerceIn(1, 4)).apply()

    var autoHighlight: Boolean
        get() = sp.getBoolean("auto_highlight", true)
        set(v) = sp.edit().putBoolean("auto_highlight", v).apply()

    var keepScreenOn: Boolean
        get() = sp.getBoolean("keep_screen", true)
        set(v) = sp.edit().putBoolean("keep_screen", v).apply()

    var fontScale: Float
        get() = sp.getFloat("font_scale", 1.0f)
        set(v) = sp.edit().putFloat("font_scale", v).apply()

    /** 整理稿默认用排版视图打开（关掉就是 Markdown 源码）。 */
    var digestRich: Boolean
        get() = sp.getBoolean("digest_rich", true)
        set(v) = sp.edit().putBoolean("digest_rich", v).apply()

    /** 整理稿字号倍率，菜单里几档循环。 */
    var digestFontScale: Float
        get() = sp.getFloat("digest_font_scale", 1.0f)
        set(v) = sp.edit().putFloat("digest_font_scale", v).apply()

    /** 在线整理用的 OpenAI 兼容接口，留空则完全离线。 */
    var llmBase: String
        get() = sp.getString("llm_base", DEFAULT_LLM_BASE) ?: DEFAULT_LLM_BASE
        set(v) = sp.edit().putString("llm_base", v.trim()).apply()

    var llmKey: String
        get() = sp.getString("llm_key", "") ?: ""
        set(v) = sp.edit().putString("llm_key", v.trim()).apply()

    var llmModel: String
        get() = sp.getString("llm_model", DEFAULT_LLM_MODEL) ?: DEFAULT_LLM_MODEL
        set(v) = sp.edit().putString("llm_model", v.trim()).apply()

    // ------------------------------------------------------------ 悬浮截图

    /** 悬浮截图按钮是不是真的开着（服务被系统停掉后要重新授权，所以它不保证一直为真）。 */
    var shotFloat: Boolean
        get() = sp.getBoolean("shot_float", false)
        set(v) = sp.edit().putBoolean("shot_float", v).apply()

    /** 开始记录时自动把悬浮截图圆钮挂出来（不用再单独去设置里开一次）。 */
    var autoShot: Boolean
        get() = sp.getBoolean("auto_shot", true)
        set(v) = sp.edit().putBoolean("auto_shot", v).apply()

    /**
     * 这次圆钮是「跟着记录顺手挂出来」的。
     *
     * 记录一结束就把它收回去 —— 否则它会一直飘在界面上（笔记页左上角正好压住标题）。
     * 用户在设置里手动开的那个不受影响，会一直留着。
     */
    var shotBySession: Boolean
        get() = sp.getBoolean("shot_by_session", false)
        set(v) = sp.edit().putBoolean("shot_by_session", v).apply()

    /** 截完自动丢给视觉模型分析。 */
    var shotAutoAnalyze: Boolean
        get() = sp.getBoolean("shot_auto_analyze", true)
        set(v) = sp.edit().putBoolean("shot_auto_analyze", v).apply()

    /** 截图后落到哪篇：true = 正在记录的那篇，false = 当天的截图笔记。 */
    var shotToRecording: Boolean
        get() = sp.getBoolean("shot_to_recording", true)
        set(v) = sp.edit().putBoolean("shot_to_recording", v).apply()

    /** 悬浮按钮的位置，-1 表示还没拖过。 */
    var shotX: Float
        get() = sp.getFloat("shot_x", -1f)
        set(v) = sp.edit().putFloat("shot_x", v).apply()

    var shotY: Float
        get() = sp.getFloat("shot_y", -1f)
        set(v) = sp.edit().putFloat("shot_y", v).apply()

    /** 图片分析用的视觉模型，可以和整理用的文本模型不一样。 */
    var visionModel: String
        get() = sp.getString("vision_model", DEFAULT_VISION_MODEL) ?: DEFAULT_VISION_MODEL
        set(v) = sp.edit().putString("vision_model", v.trim()).apply()
}
