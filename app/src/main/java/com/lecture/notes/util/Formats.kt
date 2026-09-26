package com.lecture.notes.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Formats {

    private fun fmt(pattern: String) = SimpleDateFormat(pattern, Locale.CHINA)

    fun clock(ts: Long): String = fmt("HH:mm:ss").format(Date(ts))

    fun dateTime(ts: Long): String = fmt("yyyy-MM-dd HH:mm").format(Date(ts))

    fun stamp(ts: Long): String = fmt("yyyyMMdd-HHmmss").format(Date(ts))

    fun mmss(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(total / 60, total % 60)
    }

    fun hms(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /** 今天的笔记显示成「今天 20:31」，其它显示「09-24 20:31」。 */
    fun friendly(ts: Long): String {
        val c = Calendar.getInstance()
        val today = c.get(Calendar.DAY_OF_YEAR)
        c.timeInMillis = ts
        return if (c.get(Calendar.DAY_OF_YEAR) == today) "今天 " + fmt("HH:mm").format(Date(ts))
        else fmt("MM-dd HH:mm").format(Date(ts))
    }
    /** 回收站里那条「3 天前删的」。 */
    fun ago(ts: Long, now: Long = System.currentTimeMillis()): String {
        val min = ((now - ts).coerceAtLeast(0)) / 60000
        return when {
            min < 1 -> "刚刚"
            min < 60 -> "$min 分钟前"
            min < 60 * 24 -> "${min / 60} 小时前"
            else -> "${min / 60 / 24} 天前"
        }
    }

    /** 首页那行「累计 3 小时 20 分」用的时长：只说分钟和小时，秒级没意义。 */
    fun duration(ms: Long): String {
        val min = ms / 60000
        return if (min < 60) "$min 分钟" else "${min / 60} 小时 ${min % 60} 分"
    }

    /** 笔记默认标题里用的短时间：09-25 20:31 */
    fun shortStamp(ts: Long): String = fmt("MM-dd HH:mm").format(Date(ts))
}
