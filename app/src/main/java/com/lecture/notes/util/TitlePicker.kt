package com.lecture.notes.util

/**
 * 从转写里挑一个「像课程标题」的短句。
 *
 * 早先的做法是把最前面几句直接拼起来截断，结果常常取到「所以呢这期给大家。好，今天我」这种
 * 口水句 —— 开头的几句经常是寒暄、广告，或者上一段视频的尾巴，真正讲课的内容在它后面。
 * 这里做三件事：
 *  1. 先削掉句首的口头语（嗯 / 所以 / 那么 / 然后…）和首尾标点，剩下的才是「这句在讲什么」；
 *  2. 跳过广告味太重、太短、或者纯语气词的句子；
 *  3. 按字数切的时候尽量切在标点上，切完再把尾巴上的虚词（的 / 了 / 是…）削掉，别断在半截。
 */
object TitlePicker {

    /** 句首口头语。削掉之后「所以呢这期给大家」就只剩下「这期给大家」这种明显没用的短句。 */
    private val LEAD = listOf(
        "所以呢", "所以说", "所以", "那么", "那么说", "好的", "好，", "好,", "嗯", "呃", "额",
        "然后", "接下来", "另外", "其实", "大家", "就是", "这个", "那个", "我们来看"
    )

    /** 广告 / 口水味：这种句子拿来当标题等于没写。 */
    private val NOISE = listOf(
        "点赞", "关注", "三连", "转发", "收藏", "加微信", "公众号", "扫码", "领取", "优惠",
        "下期见", "谢谢大家", "这期给", "免费领", "私信", "点击下方", "课程咨询"
    )

    private const val PUNCT = "，。、：；！？,.:;!?"

    /** 尾巴上的虚词和语气词：切在「…协议的」上看着很难受。 */
    private const val TAIL = "的了是和与及跟同它他她您我在就都也很吧呢啊呀嘛哦"

    /**
     * 挑一句当标题。从头往后看，第一个「够长、不像广告」的句子就用它 —— 课的主题
     * 通常就在开头一两分钟里被点出来。实在挑不出来就退回第一句，再没有就返回空串。
     */
    fun pick(lines: List<String>, maxChars: Int = 14): String {
        val clean = lines.map { tidy(it) }
            .filter { it.length >= 2 && NOISE.none { n -> it.contains(n) } }
        for ((i, t) in clean.withIndex()) {
            if (t.length < 4) continue
            // 够长就直接用；只有四个字（「先看定义」）就跟后面一句接起来，别让标题短得没信息
            if (t.length >= 6) return clamp(t, maxChars)
            val joined = t + clean.getOrNull(i + 1).orEmpty()
            return clamp(if (joined.length >= 6) joined else t, maxChars)
        }
        return ""
    }

    /** 削掉句首口头语和首尾标点；句子里面的标点留着，切的时候要用。 */
    internal fun tidy(raw: String): String {
        var t = raw.replace(Regex("[\\s\\u3000\\u00a0]"), "")
        t = t.trimStart('★', '-', '·', *PUNCT.toCharArray())
        var changed = true
        while (changed) {
            changed = false
            for (lead in LEAD) {
                if (t.length > lead.length && t.startsWith(lead)) {
                    t = t.substring(lead.length).trimStart(*PUNCT.toCharArray())
                    changed = true
                }
            }
        }
        return t.trimEnd(*PUNCT.toCharArray())
    }

    /** 尽量切在标点上，不行就按字数切，最后把尾巴上的虚词削掉。 */
    internal fun clamp(text: String, maxChars: Int): String {
        // 没超长就整句留着：「三次握手是这样建立的」比削掉尾巴的「…建立」读着顺。
        if (text.length <= maxChars) return text
        val cut = text.take(maxChars)
        val at = cut.indexOfLast { it in PUNCT }
        val head = if (at >= 4) cut.substring(0, at) else cut
        val out = trimTail(head)
        return if (out.length >= 4) out else trimTail(cut)
    }

    /** 尾巴上的虚词一路削掉：「…遍历和它的」削成「…遍历」，「…协议的」削成「…协议」。 */
    private fun trimTail(text: String): String {
        var t = text.trimEnd()
        while (t.length > 4 && t.last() in TAIL) t = t.dropLast(1)
        return t
    }
}
