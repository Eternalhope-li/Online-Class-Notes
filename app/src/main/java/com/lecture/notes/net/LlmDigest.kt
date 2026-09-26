package com.lecture.notes.net

import com.lecture.notes.data.Note
import com.lecture.notes.util.Formats
import com.lecture.notes.util.ImageUtil
import com.lecture.notes.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * 可选的「AI 体系化整理」：把转写交给 OpenAI 兼容的接口，要回一份成体系的整理稿。
 *
 * 默认完全用不到 —— 只有用户在「设置 → 在线整理」里填了 API Key 才会走这里；
 * 没配置时 App 仍然 100% 离线可用（走 NoteDigest 的本地整理）。
 *
 * 这一层刻意做得「皮实」，因为各个中转站 / 服务商的脾气差别很大：
 *  - 接口地址写错层级（少了 /v1，或者直接把 /chat/completions 填进来）会自动补全、404 自动换一种拼法；
 *  - 服务商不认 temperature / max_tokens 这类可选字段时，去掉字段重试一次；
 *  - 网关把请求当成「带工具调用的会话」处理、返回 tool_calls 相关报错时，
 *    换成最小请求体（只留 model + messages）再试一次，而不是直接把错误甩给用户；
 *  - 429 / 5xx / 网络抖动自动退避重试。
 */
object LlmDigest {

    class LlmException(message: String) : Exception(message)

    private const val CHUNK_CHARS = 2600
    private const val MERGE_CHARS = 11000
    private const val MAX_TOKENS = 3200
    /** 视觉模型（GLM-4V-Flash 只有 4K 上下文）输出要短一点，不然输入加输出就爆了。 */
    private const val VISION_MAX_TOKENS = 1200
    private const val MAX_HEAL = 3

    // ------------------------------------------------------------ 对外

    fun isReady(): Boolean = Prefs.llmKey.isNotBlank()

    /**
     * 整理一篇笔记。[onProgress] 的参数是（第几段, 共几段, 正在做什么）。
     *
     * 短课一次到位；长课先逐段整理成素材卡，再把素材卡合成一份体系化终稿，
     * 这样几千句的长讲座也不会超出模型的上下文，而且层级和术语表是全局统一的。
     */
    suspend fun digest(note: Note, onProgress: (Int, Int, String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val key = Prefs.llmKey.trim()
            if (key.isEmpty()) throw LlmException("还没有填写 API Key")
            val chunks = chunk(note)
            if (chunks.isEmpty()) throw LlmException("这篇笔记还没有内容")

            if (chunks.size == 1) {
                onProgress(1, 1, "体系化整理")
                return@withContext clean(call(key, FINALIZE, chunks[0]))
            }

            val cards = ArrayList<String>(chunks.size)
            for ((i, c) in chunks.withIndex()) {
                onProgress(i + 1, chunks.size, "第 ${i + 1}/${chunks.size} 段")
                cards.add(clean(call(key, MATERIAL, c)))
            }
            onProgress(chunks.size, chunks.size, "体系化合并")
            finalize(key, cards.joinToString("\n\n"))
        }

    /** 「测试连接」用：发一句最短的话，确认地址 / Key / 模型名都对。 */
    suspend fun ping(): String = withContext(Dispatchers.IO) {
        val key = Prefs.llmKey.trim()
        if (key.isEmpty()) throw LlmException("还没有填写 API Key")
        call(key, "你是一个测试助手，只回复用户要求的内容，不要多说一个字。", "请只回复两个字：正常")
            .take(40)
    }

    /**
     * 看懂一张课堂截图。
     *
     * [jpegBase64] 是不带 data URL 前缀的 base64，调用前已经压到长边 1280 以内，
     * 否则 GLM-4V-Flash 的 4K 上下文装不下一张手机全屏截图。
     * 返回一段可以直接塞进笔记的 Markdown。
     */
    suspend fun analyzeImage(jpegBase64: String, hint: String = ""): String = withContext(Dispatchers.IO) {
        val key = Prefs.llmKey.trim()
        if (key.isEmpty()) throw LlmException("还没有填写 API Key")
        if (jpegBase64.isBlank()) throw LlmException("图片是空的")
        val text = buildString {
            append(VISION_PROMPT)
            if (hint.isNotBlank()) {
                append("\n\n（补充：这张截图来自一门课，标题叫《")
                append(hint.trim().take(60))
                append("》，请按这个上下文理解图上的内容）")
            }
        }
        clean(call(key, VISION_SYS, text, image = ImageUtil.dataUrl(jpegBase64), vision = true, maxTokens = VISION_MAX_TOKENS))
    }

    // ------------------------------------------------------------ 流程

    /** 太长的素材先分组各出一版终稿，再把这些终稿合成一版，保证体系不乱。 */
    private fun finalize(key: String, material: String): String {
        if (material.length <= MERGE_CHARS) return clean(call(key, FINALIZE, material))
        val parts = ArrayList<String>()
        for (g in splitText(material, MERGE_CHARS)) parts.add(clean(call(key, FINALIZE, g)))
        if (parts.size == 1) return parts[0]
        return clean(call(key, MERGE, parts.joinToString("\n\n")))
    }

    private fun chunk(note: Note): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (e in note.entries) {
            // 截图条目用它自己的说明文字顶上去，AI 才能把图里的要点也整理进小节
            val body = e.digestText
            if (body.isBlank()) continue
            val line = "[" + Formats.mmss(e.atMs) + "] " + if (e.star) "★ " + body else body
            if (sb.isNotEmpty() && sb.length + line.length > CHUNK_CHARS) {
                out.add(sb.toString())
                sb.setLength(0)
            }
            sb.append(line).append('\n')
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private fun splitText(text: String, limit: Int): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (line in text.split('\n')) {
            if (sb.isNotEmpty() && sb.length + line.length > limit) {
                out.add(sb.toString())
                sb.setLength(0)
            }
            sb.append(line).append('\n')
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** 模型有时会自作主张加 "# 大标题"，标题由 App 自己加，这里统一去掉。 */
    private fun clean(md: String): String = md.lines()
        .filterNot { it.trimStart().startsWith("# ") }
        .joinToString("\n")
        .trim()

    // ------------------------------------------------------------ HTTP

    private class Opts(
        val temperature: Boolean = true,
        val tokens: Boolean = true,
        val minimal: Boolean = false
    ) {
        val isMinimal: Boolean get() = minimal
        fun withoutTemp() = Opts(false, tokens, minimal)
        fun withoutTokens() = Opts(temperature, false, minimal)
    }

    private val MINIMAL = Opts(temperature = false, tokens = false, minimal = true)

    /**
     * 发一次请求，失败时按「能自愈就自愈」的顺序退让：
     * 换路径 → 去掉不认的字段 → 换成最小请求体。
     */
    private fun call(
        key: String,
        sys: String,
        user: String,
        depth: Int = 0,
        opts: Opts = Opts(),
        image: String? = null,
        vision: Boolean = false,
        maxTokens: Int = MAX_TOKENS
    ): String {
        val urls = endpoints()
        var last = "请求失败"
        for ((idx, url) in urls.withIndex()) {
            val pair = try {
                http(url, key, buildBody(sys, user, opts, image, vision, maxTokens), 2)
            } catch (t: Throwable) {
                last = netMessage(t)
                if (idx == 0 && urls.size > 1) continue
                throw LlmException(last)
            }
            val code = pair.first
            val text = pair.second
            if (code in 200..299) {
                val err = errorOf(text)
                if (err != null) throw LlmException(err)
                return contentOf(text)
            }
            val detail = (errorOf(text) ?: text).take(200).replace('\n', ' ')

            if (depth < MAX_HEAL && code in 400..422) {
                val slim = slimDown(opts, detail)
                if (slim != null) return call(key, sys, user, depth + 1, slim, image, vision, maxTokens)
                if (!opts.isMinimal && looksLikeToolError(detail)) {
                    return call(key, sys, user, depth + 1, MINIMAL, image, vision, maxTokens)
                }
            }
            if (code == 404 && idx == 0 && urls.size > 1) {
                last = "接口地址不对（404）：$url"
                continue
            }
            throw LlmException(friendly(code, detail, url, image != null))
        }
        throw LlmException(last)
    }

    /** 允许用户填：`https://x/v1`、`https://x/v1/chat/completions`、`https://x`、`https://x/api/paas/v4`。 */
    private fun endpoints(): List<String> {
        val base = Prefs.llmBase.trim().trimEnd('/')
        if (base.isEmpty()) throw LlmException("接口地址是空的")
        if (base.endsWith("/chat/completions")) return listOf(base)
        if (Regex("/v\\d+(\\.\\d+)?$").containsMatchIn(base)) return listOf("$base/chat/completions")
        return listOf("$base/v1/chat/completions", "$base/chat/completions")
    }

    /** 单测入口：请求体的构造逻辑（尤其是多模态那个 content 数组）不用真的发请求也能验。 */
    internal fun debugBody(sys: String, user: String, image: String?): String =
        buildBody(
            sys, user, Opts(), image, image != null,
            if (image != null) VISION_MAX_TOKENS else MAX_TOKENS
        )

    private fun buildBody(
        sys: String,
        user: String,
        opts: Opts,
        image: String? = null,
        vision: Boolean = false,
        maxTokens: Int = MAX_TOKENS
    ): String {
        val model = if (vision) {
            Prefs.visionModel.trim().ifEmpty { Prefs.DEFAULT_VISION_MODEL }
        } else {
            Prefs.llmModel.trim().ifEmpty { "deepseek-chat" }
        }
        val j = JSONObject()
        j.put("model", model)
        if (!opts.isMinimal) {
            if (opts.temperature) j.put("temperature", 0.3)
            if (opts.tokens) j.put("max_tokens", maxTokens)
            j.put("stream", false)
        }
        val messages = JSONArray()
        if (sys.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", sys))
        val u = JSONObject().put("role", "user")
        if (image.isNullOrBlank()) {
            u.put("content", user)
        } else {
            // OpenAI 兼容的多模态写法：content 是一个数组，文字和图片各占一项
            val parts = JSONArray()
            parts.put(JSONObject().put("type", "text").put("text", user))
            parts.put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", image))
            )
            u.put("content", parts)
        }
        messages.put(u)
        j.put("messages", messages)
        return j.toString()
    }

    private fun http(url: String, key: String, body: String, attempts: Int): Pair<Int, String> {
        var last: Throwable? = null
        for (a in 0 until attempts) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 240000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Authorization", "Bearer $key")
                }
                try {
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                    if ((code == 429 || code >= 500) && a < attempts - 1) {
                        last = IOException("HTTP $code")
                        sleep(900L * (a + 1))
                        continue
                    }
                    return code to text
                } finally {
                    try {
                        conn.disconnect()
                    } catch (_: Throwable) {
                    }
                }
            } catch (t: Throwable) {
                last = t
                if (a < attempts - 1) sleep(700L * (a + 1))
            }
        }
        throw (last ?: IOException("网络请求失败"))
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** 服务商不认某个可选字段时，按报错点名把它去掉，重试一次。 */
    private fun slimDown(opts: Opts, detail: String): Opts? {
        val d = detail.lowercase()
        if (opts.temperature && d.contains("temperature")) return opts.withoutTemp()
        if (opts.tokens && (d.contains("max_tokens") || d.contains("max_completion_tokens"))) {
            return opts.withoutTokens()
        }
        return null
    }

    private fun looksLikeToolError(detail: String): Boolean {
        val d = detail.lowercase()
        return d.contains("tool_calls") || d.contains("tool call") || d.contains("role 'tool'") ||
                d.contains("role \"tool\"") || d.contains("function calling") ||
                (d.contains("messages") && d.contains("tool"))
    }

    private fun friendly(code: Int, detail: String, url: String, hasImage: Boolean = false): String = when {
        code == 401 || code == 403 -> "API Key 不对或没有权限（$code）：$detail"
        code == 404 -> "接口地址不对（404）：$url"
        code == 429 -> "请求太频繁或额度用尽（429）：$detail"
        code in 500..599 -> "服务商暂时不可用（$code）：$detail"
        hasImage && code in 400..422 ->
            "这个模型可能看不了图（$code）。把「视觉模型」换成 glm-4v-flash 再试。" +
                    "原始报错：$detail"
        looksLikeToolError(detail) ->
            "这个接口/模型看起来不是普通对话模型（报错提到 tool_calls）。" +
                    "把「模型名」换成 deepseek-chat、glm-4-flash 这类对话模型试试；" +
                    "如果接口地址填的是中转站，确认它支持 /v1/chat/completions。原始报错：$detail"
        else -> "接口返回 $code：$detail"
    }

    private fun netMessage(t: Throwable): String = when (t) {
        is SocketTimeoutException -> "等待超时了，网课内容较长时请保持网络稳定"
        is IOException -> "网络不通：${t.message ?: t.javaClass.simpleName}"
        else -> t.message ?: t.javaClass.simpleName
    }

    // ------------------------------------------------------------ 解析

    private fun errorOf(text: String): String? {
        if (text.isBlank()) return null
        return try {
            val j = JSONObject(text)
            val e = if (j.has("error")) j.opt("error") else if (j.has("error_msg")) j.opt("error_msg") else null
            when {
                e == null || e == JSONObject.NULL -> null
                e is String -> e.ifBlank { null }
                e is JSONObject -> e.optString("message").ifBlank { e.toString() }
                else -> e.toString()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun contentOf(text: String): String {
        val root = try {
            JSONObject(text)
        } catch (_: Throwable) {
            throw LlmException("看不懂接口返回的内容")
        }
        val choices = root.optJSONArray("choices")
        val msg = choices?.optJSONObject(0)?.optJSONObject("message")
        val raw = msg?.opt("content")
        val out = when (raw) {
            is String -> raw
            is JSONArray -> buildString {
                for (i in 0 until raw.length()) {
                    val part = raw.optJSONObject(i) ?: continue
                    append(part.optString("text"))
                }
            }
            else -> ""
        }.trim()
        if (out.isEmpty()) {
            val reason = choices?.optJSONObject(0)?.optString("finish_reason").orEmpty()
            throw LlmException(
                if (reason == "length") "输出被长度上限截断了，换个模型或把笔记整理短一点"
                else "接口没有返回内容（模型名可能不对：${Prefs.llmModel}）"
            )
        }
        return out
    }

    // ------------------------------------------------------------ 提示词

    /** 长课分段的「素材卡」：只做提取和清洗，体系化留给下一步。 */
    private val MATERIAL = """
        你是网课笔记整理助手。下面是课堂录音的语音转写，每句带一个 [mm:ss] 时间戳，
        里面可能有同音字识别错误、口语废话和重复语句。

        请把这一段整理成「素材卡」，直接输出 Markdown，不要任何开场白、解释或结尾语：
        1. 按内容分成 1-3 个小节，每节写成 "## 小节标题"，标题不超过 14 个字；不要写 "#" 大标题；
        2. 每节下面用 "- " 列出真正的知识点：定义、结论、公式、步骤、分类、对比、易错点、考点；
        3. 每个要点开头保留对应时间戳，写成反引号包起来的形式，例如 `[03:12]`；
        4. 删掉寒暄、重复和口头禅；老师强调「要记住 / 必考 / 作业」的内容必须保留；
        5. 按上下文改正明显的同音字错误，但不要编造原文没有的内容。
    """.trimIndent()

    /** 体系化终稿的骨架与要求，短课一次生成、长课合并时都用它。 */
    private val SKELETON = """
        你是课程笔记的体系化编辑。下面是一堂网课的内容（语音转写或分段整理出的素材），
        每句带 [mm:ss] 时间戳。请把它编辑成一份**成体系**的课堂笔记：结构清楚、层次分明、
        能一眼看出这节课的框架，复习时看这一份就够了。

        直接输出 Markdown，不要任何寒暄、说明或结尾语。严格按下面的骨架和顺序输出：

        第一行是一条引用块导语（以 "> " 开头）：一句话说明这节课讲什么、学完能做什么。

        ## 本课知识框架
        用缩进列表画出这一课的体系：主题 → 分支 → 具体知识点，3-8 行，让人一眼看到全貌。

        ## 一、大节标题
        ### 1.1 子知识点
        - 要点：定义、结论、公式、步骤、分类、对比，一条一句话说清
        ### 1.2 子知识点
        - ……

        ## 二、大节标题
        （同上；大节数量按内容定，一般 2-6 个，序号用中文数字）

        ## 术语与公式
        | 术语 / 公式 | 含义 |
        | --- | --- |
        | …… | …… |

        ## 易错点与考点
        - ……

        ## 一句话总结
        > ……

        ## 作业与下节预告
        - ……

        要求：
        1. 层级最多三层（## / ### / -），不要用 ####，也不要自己写 "#" 大标题（标题由 App 添加）；
        2. 保留原文的时间戳，写成反引号包起来的形式，例如 `[03:12]`，放在对应要点开头或句末；
        3. 术语表 2-6 行，只收这节课真正出现过的术语和公式；
        4. 合并重复内容、删掉口水话，但老师强调的重点、作业、考试范围不能删；
        5. 修正明显的同音字错误、把不通顺的句子补顺；不要编造原文没有的知识点；
        6. 全篇 400-1200 字，内容少就写短一点；某项确实没有内容就整节不写，但顺序不要变。
    """.trimIndent()

    /** 单段（或素材卡）直接出终稿，用的就是骨架本身。 */
    private val FINALIZE = SKELETON

    private val MERGE = """
        下面是同一堂课分几段整理出的几版笔记，内容可能有重复、小节可能对不上。
        请把它们合并成**一份**不重复、层级连贯的体系化笔记：把同一主题的要点归到同一个大节下，
        重复的小节和要点只留一条，时间戳照旧保留。

    """.trimIndent() + SKELETON

    /** 视觉模型的提示词。要短、要具体，因为免费视觉模型上下文都不大。 */
    private val VISION_SYS = """
        你是网课笔记助手，专门看懂课堂截图：课件 PPT、老师板书、图表、公式、代码、题目。
        只输出 Markdown 内容本身，不要开场白，不要说明你在做什么。
    """.trimIndent()

    private val VISION_PROMPT = """
        这是我在网课上截的一张图。请输出一段可以直接放进笔记的 Markdown 说明：
        1. 第一行说清这张图在讲什么，不超过 30 字，以 "- " 开头；
        2. 图上的标题、定义、结论、公式、代码，逐条抄下来，写成 "- " 开头的列表；短公式用反引号包起来，例如 `a²+b²=c²`；
        3. 如果是表格，用 Markdown 表格还原；如果是流程图、结构图、箭头关系，写成 "- A → B → C" 说明层次；
        4. 最后一行以 "> 要点：" 开头，写这张图最需要记住的 1-2 点。
        只写图上真实存在的内容；看不清的地方写「（图中字迹模糊）」，绝对不要编造。
    """.trimIndent()
}
