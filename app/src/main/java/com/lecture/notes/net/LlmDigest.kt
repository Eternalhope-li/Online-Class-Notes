package com.lecture.notes.net

import com.lecture.notes.data.Entry
import com.lecture.notes.data.Note
import com.lecture.notes.data.NoteStore
import com.lecture.notes.util.Formats
import com.lecture.notes.util.ImageUtil
import com.lecture.notes.util.MiniMarkdown
import com.lecture.notes.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
    /**
     * 终稿一轮的输出上限。
     *
     * 提示词只要求 500-900 字，给到 2400 token 已经很宽裕；卡住上限是为了防跑飞 ——
     * 模型一旦开始长篇复述，输出越长越慢，而用户要的是能复习的提纲，不是课堂实录。
     */
    private const val FINAL_MAX_TOKENS = 2400
    /** 素材卡只是中间产物，输出收窄能让这一轮明显更快。 */
    private const val MATERIAL_MAX_TOKENS = 900
    /**
     * 一次能发完的总字数：在这个范围内直接出终稿，省掉「素材卡 + 合并」两轮调用。
     *
     * 两万字差不多是一节两小时课的转写量，现在的默认模型（glm-4-flash 128K 上下文、
     * deepseek-chat 64K）都装得下。走一趟和走两趟的差别是实打实的：
     * 常见的一节课从「素材卡 + 合并」变成一次调用，等待时间差不多砍一半。
     * 万一模型上下文不够，会报「超长」并自动退回分段路径，不会直接失败。
     */
    private const val SINGLE_PASS_CHARS = 20000
    /** 分段调用的并发数：这些请求互不依赖，并行发出去省掉一大半等待。 */
    private const val PARALLEL = 6
    /** 流式内容推给界面的最小间隔，免得一秒刷新几十次。 */
    private const val STREAM_PUSH_MS = 120L
    /** 视觉模型（GLM-4V-Flash 只有 4K 上下文）输出要短一点，不然输入加输出就爆了。 */
    private const val VISION_MAX_TOKENS = 1200
    private const val MAX_HEAL = 3
    /** 「测试看图」：就当普通看图请求发，只是问题特别简单。 */
    private const val VISION_TEST_SYS = "你是识图助手，只回答用户问的图上的内容，不要客套。"
    private const val VISION_TEST_ASK = "这张图中间写着一串数字，把它原样回复给我，不要解释。"

    // ------------------------------------------------------------ 对外

    fun isReady(): Boolean = Prefs.llmKey.isNotBlank()

    /** 看图用的 Key：单独填过就用单独的，没填就复用整理那套。 */
    internal fun visionKey(): String = Prefs.visionKey.trim().ifEmpty { Prefs.llmKey.trim() }

    /** 截图能不能叫 AI 看图 —— 只要有一把能用的 Key 就行（哪怕只配了看图那把）。 */
    fun visionReady(): Boolean = visionKey().isNotEmpty()

    /** 取消时用的统一说法，界面据此区分「用户取消」和真的失败。 */
    internal const val CANCELLED = "已取消"

    /** 当前正在跑的那趟请求。取消时直接断开连接，正卡在等响应上的读会立刻抛错返回。 */
    private val activeConn = AtomicReference<HttpURLConnection?>(null)

    @Volatile
    private var cancelled = false

    /** 单测用：看是否还留着上一次的取消标记。 */
    internal fun isCancelled(): Boolean = cancelled

    /** 从界面线程掐断正在进行的整理。 */
    fun cancelActive() {
        cancelled = true
        try {
            activeConn.getAndSet(null)?.disconnect()
        } catch (_: Throwable) {
        }
    }

    /**
     * 整理一篇笔记。[onProgress] 的参数是（已完成段数, 总段数, 正在做什么）。
     * [onPartial] 非空时开启流式输出：模型每吐出一小段就回调一次**累积后**的全文，
     * 界面可以边生成边显示，不用对着进度条干等。
     *
     * 提速做了三件事：
     *  - 全篇在 [SINGLE_PASS_CHARS] 以内就直接出终稿，不再走「素材卡 + 合并」那两轮；
     *  - 必须分段时，各段的素材卡并行发出（[PARALLEL] 个一起跑），而不是排队等；
     *  - 素材卡这轮输出收窄到 [MATERIAL_MAX_TOKENS]，中间产物没必要写那么长。
     */
    suspend fun digest(
        note: Note,
        onProgress: (Int, Int, String) -> Unit,
        onPartial: ((String) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        cancelled = false
        val key = Prefs.llmKey.trim()
        if (key.isEmpty()) throw LlmException("还没有填写 API Key")
        val plan = plan(note)
        if (plan.chunks.isEmpty()) throw LlmException("这篇笔记还没有内容")

        // 流式回调也过一遍图片还原：边生成边看到的就是最终排版，不会闪出 [[IMG1]] 这种记号
        val live: ((String) -> Unit)? = onPartial?.let { cb ->
            { md: String -> cb(embedImages(md, note, appendMissing = false)) }
        }

        val body = try {
            if (plan.singlePass) {
                onProgress(1, 1, "体系化整理")
                finalize(key, plan.chunks.joinToString("\n\n"), live)
            } else {
                segmented(key, plan, onProgress, live)
            }
        } catch (t: Throwable) {
            // 模型上下文装不下整篇（小模型 / 中转站截断）时退回分段路径，别直接失败
            if (plan.singlePass && plan.chunks.size > 1 && isTooLong(t)) {
                segmented(key, plan, onProgress, live)
            } else {
                throw t
            }
        }
        // 模型爱把图塞在要点行中间（`- **图示**：![…](shots/x.jpg) 说明`），落盘前先提行：
        // 解析层只认独占一行的图片，不提行整张图就没了；导出的 .md / .html 也因此是干净的
        MiniMarkdown.hoistImages(embedImages(body, note, appendMissing = true))
    }

    /** 长课：各段并行出素材卡，再把这些卡片合成一版终稿。 */
    private suspend fun segmented(
        key: String,
        plan: Plan,
        onProgress: (Int, Int, String) -> Unit,
        live: ((String) -> Unit)?
    ): String {
        val cards = material(key, plan.chunks, onProgress)
        onProgress(plan.chunks.size, plan.chunks.size, "体系化合并")
        return finalize(key, cards.joinToString("\n\n"), live)
    }

    /** 「测试连接」用：发一句最短的话，确认地址 / Key / 模型名都对。 */
    suspend fun ping(): String = withContext(Dispatchers.IO) {
        cancelled = false
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
        // 取消标记是全局的：上一次整理被掐断后必须在这里清零，
        // 否则截图分析会被残留的标记直接判成「已取消」，一张图都分析不出来。
        cancelled = false
        val key = visionKey()
        if (key.isEmpty()) throw LlmException("还没有填写看图的 API Key")
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

    /**
     * 「测试看图」用：发一张固定的小图（白底黑字写着 42），看模型读不读得出来。
     *
     * 截图分析这条链路最容易卡在四件事上：没填 Key、地址不对、服务商根本没有视觉模型
     * （比如 DeepSeek）、模型名写错。这个按钮把它们一次性验掉，不用等上完课才发现图没分析。
     */
    suspend fun pingVision(jpegBase64: String): String = withContext(Dispatchers.IO) {
        cancelled = false
        val key = visionKey()
        if (key.isEmpty()) throw LlmException("还没有填写看图的 API Key")
        clean(
            call(
                key,
                VISION_TEST_SYS,
                VISION_TEST_ASK,
                opts = MINIMAL,
                image = ImageUtil.dataUrl(jpegBase64),
                vision = true,
                maxTokens = 200
            )
        ).take(120)
    }

    /** 单测入口：看图请求会走哪几个地址。 */
    internal fun debugEndpoints(vision: Boolean): List<String> = endpoints(vision)

    // ------------------------------------------------------------ 流程

    /** 分段计划：能一趟发完就一趟发完，超过 [SINGLE_PASS_CHARS] 才走「素材卡 + 合并」。 */
    internal class Plan(val chunks: List<String>, val singlePass: Boolean)

    internal fun plan(note: Note): Plan {
        val chunks = chunk(note)
        val single = chunks.size <= 1 || chunks.sumOf { it.length } <= SINGLE_PASS_CHARS
        return Plan(chunks, single)
    }

    /** 太长的素材先分组各出一版终稿，再把这些终稿合成一版，保证体系不乱；分组同样并行。 */
    private suspend fun finalize(key: String, material: String, onPartial: ((String) -> Unit)?): String {
        if (material.length <= MERGE_CHARS) {
            return clean(call(key, FINALIZE, material, maxTokens = FINAL_MAX_TOKENS, onDelta = onPartial))
        }
        val drafts = inParallel(splitText(material, MERGE_CHARS)) { _, g ->
            clean(call(key, FINALIZE, g, maxTokens = FINAL_MAX_TOKENS))
        }
        if (drafts.size == 1) return drafts[0]
        return clean(
            call(key, MERGE, drafts.joinToString("\n\n"), maxTokens = FINAL_MAX_TOKENS, onDelta = onPartial)
        )
    }

    /**
     * 逐段整理成素材卡。这些请求互不依赖，并行发出（最多 [PARALLEL] 个），
     * 长课的整体等待大约是串行的 1/[PARALLEL]；完成顺序乱了也不影响结果 ——
     * 返回时按下标排好，合并那一步看到的仍然是课程原本的顺序。
     */
    private suspend fun material(
        key: String,
        chunks: List<String>,
        onProgress: (Int, Int, String) -> Unit
    ): List<String> {
        val done = AtomicInteger(0)
        return inParallel(chunks) { _, c ->
            val card = clean(call(key, MATERIAL, c, maxTokens = MATERIAL_MAX_TOKENS))
            val n = done.incrementAndGet()
            onProgress(n, chunks.size, "第 $n/${chunks.size} 段")
            card
        }
    }

    /** 并发跑一批互不依赖的请求，最多 [PARALLEL] 个同时进行，返回顺序与入参一致。 */
    private suspend fun <T> inParallel(items: List<String>, block: suspend (Int, String) -> T): List<T> =
        coroutineScope {
            val gate = Semaphore(PARALLEL)
            items.mapIndexed { i, s -> async { gate.withPermit { block(i, s) } } }.awaitAll()
        }

    private fun chunk(note: Note): List<String> {
        val marks = shotMarks(note)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (e in note.entries) {
            // 截图条目用它自己的说明文字顶上去，AI 才能把图里的要点也整理进小节；
            // 前面再挂一个 [[IMGn]] 记号，模型看到就知道这里该放一张图
            val body = e.digestText
            if (body.isBlank()) continue
            val mark = e.image?.let { rel -> marks[rel]?.let { n -> "[[IMG$n]] " } }.orEmpty()
            val line = "[" + Formats.mmss(e.atMs) + "] " + mark + if (e.star) "★ " + body else body
            if (sb.isNotEmpty() && sb.length + line.length > CHUNK_CHARS) {
                out.add(sb.toString())
                sb.setLength(0)
            }
            sb.append(line).append('\n')
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** 整篇里所有截图的顺序（从 1 开始编号）。分段时各段用同一套编号，合并也不会串。 */
    internal fun shots(note: Note): List<Entry> =
        note.entries.filter { it.isImage && !it.image.isNullOrBlank() }

    private fun shotMarks(note: Note): Map<String, Int> =
        shots(note).withIndex().associate { (i, e) -> e.image!! to (i + 1) }

    /** `[[IMG1]]` 这类记号：模型把它单独放一行，App 再换成真正的图片。 */
    private val IMG_TOKEN = Regex("`?\\[\\[\\s*IMG\\s*(\\d+)\\s*]]`?")

    /** 正文里的 `[mm:ss]`，用来判断某张截图该落在哪句话下面。 */
    private val TS_RE = Regex("\\[(\\d{1,2}):(\\d{2})]")

    /**
     * 把整理稿里的 `[[IMGn]]` 记号换成 `![课堂截图 mm:ss](shots/x.jpg)`。
     *
     * [appendMissing] 为真时，模型漏放回去的截图会统一补进末尾的「本课图示」小节 ——
     * 宁可图都堆在最后，也不能因为模型漏抄一个记号就把截图弄丢。
     * 认不出的记号（模型自己编的编号）直接删掉，不在正文里留一串乱码。
     */
    internal fun embedImages(md: String, note: Note, appendMissing: Boolean): String {
        val all = shots(note)
        if (all.isEmpty()) return md
        val used = HashSet<String>()
        val body = IMG_TOKEN.replace(md) { m ->
            val e = m.groupValues[1].toIntOrNull()?.let { all.getOrNull(it - 1) }
            val rel = e?.image
            if (rel == null) {
                ""
            } else {
                used.add(rel)
                "![课堂截图 " + Formats.mmss(e.atMs) + "](" + rel + ")"
            }
        }
        if (!appendMissing) return body
        val missing = all.filter { it.image != null && it.image !in used }
        if (missing.isEmpty()) return body
        // 模型漏放记号的图，按时间戳插到「当时正在讲它的那句话」下面；
        // 实在找不到时间戳（模型没照要求保留）才退回文末的「本课图示」兜底
        val placed = placeMissing(body, missing)
        if (placed != null) return placed
        return (body.trimEnd() + "\n" +
            NoteStore.shotsSection(note, onlyRels = missing.mapNotNull { it.image }.toSet())).trim()
    }

    /**
     * 把模型漏放记号的截图，插到时间上最贴近它的那一行后面。
     *
     * 整理稿里每个要点都带着 `[mm:ss]`，截图也知道自己是什么时候截的，所以「这张图该放哪」
     * 其实是个能算出来的问题：挑出时间戳不大于截图时间、且最靠后的那一行，图就插在它下面。
     * 比正文第一句还早的，插在第一句带时间戳的话前面。
     * 整篇一行时间戳都没有就返回 null，交给调用方退回文末汇总 —— 图丢了比位置差严重得多。
     */
    private fun placeMissing(body: String, missing: List<Entry>): String? {
        val lines = body.split('\n').toMutableList()
        if (lines.none { tsOf(it) >= 0 }) return null
        for (e in missing.sortedBy { it.atMs }) {
            val want = (e.atMs / 1000L).toInt()
            var at = -1
            for (i in lines.indices) {
                val t = tsOf(lines[i])
                if (t in 0..want) at = i
            }
            if (at < 0) at = lines.indexOfFirst { tsOf(it) >= 0 } - 1
            val block = NoteStore.shotBlock(e).trimEnd().split('\n')
            lines.addAll(at + 1, block)
            lines.add(at + 1 + block.size, "")
        }
        return lines.joinToString("\n").trim()
    }

    /** 一行里 `[mm:ss]` 的时间戳换成秒；这行没有时间戳就返回 -1。 */
    private fun tsOf(line: String): Int {
        val m = TS_RE.find(line) ?: return -1
        return (m.groupValues[1].toIntOrNull() ?: return -1) * 60 +
            (m.groupValues[2].toIntOrNull() ?: return -1)
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

    /**
     * 把模型吐出来的 Markdown 收干净。
     *
     * 除了干掉模型自作主张的 "# 大标题"（标题由 App 加），还顺手做三件排版上的事：
     * 把 "####" 压回三级（层级最多三层）、给标题行前面补空行（不然标题会跟上一段粘在一起
     * 变成正文）、把连续空行并成一个。这些都不改变内容，只让排版结果稳定可控。
     */
    private fun clean(md: String): String {
        val out = StringBuilder()
        var blanks = 0
        for (raw in md.replace("\r\n", "\n").replace('\r', '\n').lines()) {
            val line = raw.trimEnd()
            if (line.isBlank()) {
                blanks++
                continue
            }
            val t = line.trimStart()
            if (t.startsWith("# ")) continue
            val heading = t.startsWith("##")
            if (heading && out.isNotEmpty() && blanks == 0) out.append('\n')
            blanks = 0
            out.append(if (t.startsWith("####")) "### " + t.trimStart('#').trim() else line).append('\n')
        }
        return out.toString().trim()
    }

    // ------------------------------------------------------------ HTTP

    private class Opts(
        val temperature: Boolean = true,
        val tokens: Boolean = true,
        val minimal: Boolean = false,
        val stream: Boolean = true
    ) {
        val isMinimal: Boolean get() = minimal
        fun withoutTemp() = Opts(false, tokens, minimal, stream)
        fun withoutTokens() = Opts(temperature, false, minimal, stream)
        fun withoutStream() = Opts(temperature, tokens, minimal, false)
    }

    private val MINIMAL = Opts(temperature = false, tokens = false, minimal = true)

    /**
     * 发一次请求，失败时按「能自愈就自愈」的顺序退让：
     * 换路径 → 去掉不认的字段 → 关掉网关不支持的 stream → 换成最小请求体。
     *
     * [onDelta] 非空时用流式请求，模型每吐一点就回调一次累积正文；
     * 网关不支持流式会报错，这里会自动关掉流式重发一次。
     */
    private fun call(
        key: String,
        sys: String,
        user: String,
        depth: Int = 0,
        opts: Opts = Opts(),
        image: String? = null,
        vision: Boolean = false,
        maxTokens: Int = MAX_TOKENS,
        onDelta: ((String) -> Unit)? = null
    ): String {
        val urls = endpoints(vision)
        val streaming = onDelta != null && opts.stream
        var last = "请求失败"
        for ((idx, url) in urls.withIndex()) {
            var got = ""
            val sink: ((String) -> Unit)? = if (streaming) {
                { text -> got = text; onDelta?.invoke(text) }
            } else {
                null
            }
            val pair = try {
                http(
                    url, key, buildBody(sys, user, opts, image, vision, maxTokens, streaming), 2, sink
                )
            } catch (t: Throwable) {
                last = netMessage(t)
                if (cancelled) throw LlmException(CANCELLED)
                if (idx == 0 && urls.size > 1) continue
                throw LlmException(last)
            }
            val code = pair.first
            val text = pair.second
            if (code in 200..299) {
                if (got.isNotBlank()) return got.trim()
                val err = errorOf(text)
                if (err != null) throw LlmException(err)
                return contentOf(text)
            }
            val detail = (errorOf(text) ?: text).take(200).replace('\n', ' ')

            if (depth < MAX_HEAL && code in 400..422) {
                if (streaming && looksLikeStreamError(detail)) {
                    return call(key, sys, user, depth + 1, opts.withoutStream(), image, vision, maxTokens, onDelta)
                }
                val slim = slimDown(opts, detail)
                if (slim != null) return call(key, sys, user, depth + 1, slim, image, vision, maxTokens, onDelta)
                if (!opts.isMinimal && looksLikeToolError(detail)) {
                    return call(key, sys, user, depth + 1, MINIMAL, image, vision, maxTokens, onDelta)
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
    private fun endpoints(vision: Boolean = false): List<String> {
        // 视觉模型可以单独配一套地址：整理用 DeepSeek（没有看图能力）、看图用智谱，是很常见的组合
        val raw = if (vision) Prefs.visionBase.trim().ifEmpty { Prefs.llmBase.trim() } else Prefs.llmBase
        val base = raw.trim().trimEnd('/')
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
        maxTokens: Int = MAX_TOKENS,
        stream: Boolean = false
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
            if (stream) j.put("stream", true)
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

    private fun http(
        url: String,
        key: String,
        body: String,
        attempts: Int,
        onDelta: ((String) -> Unit)? = null
    ): Pair<Int, String> {
        var last: Throwable? = null
        for (a in 0 until attempts) {
            if (cancelled) throw IOException(CANCELLED)
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 240000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty(
                        "Accept",
                        if (onDelta != null) "text/event-stream" else "application/json"
                    )
                }
                try {
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    activeConn.set(conn)
                    val code = conn.responseCode
                    if (code in 200..299 && onDelta != null &&
                        conn.contentType.orEmpty().contains("event-stream", ignoreCase = true)
                    ) {
                        val streamed = readStream(conn, onDelta)
                        if (streamed.isNotBlank()) return code to streamed
                    }
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
                        activeConn.compareAndSet(conn, null)
                        conn.disconnect()
                    } catch (_: Throwable) {
                    }
                }
            } catch (t: Throwable) {
                last = t
                if (cancelled) throw IOException(CANCELLED)
                if (a < attempts - 1) sleep(700L * (a + 1))
            }
        }
        throw (last ?: IOException("网络请求失败"))
    }

    /**
     * 读 SSE 流，边读边把累积正文推给界面。
     * 中途断线时，只要已经吐出了内容就把它留下（总比白等一场强），一字未得才抛错走重试。
     */
    private fun readStream(conn: HttpURLConnection, onDelta: (String) -> Unit): String {
        val sb = StringBuilder()
        var pushed = 0L
        try {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (cancelled) throw IOException(CANCELLED)
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload.isEmpty()) continue
                    if (payload == "[DONE]") break
                    val delta = deltaOf(payload) ?: continue
                    if (delta.isEmpty()) continue
                    sb.append(delta)
                    val now = System.currentTimeMillis()
                    if (now - pushed >= STREAM_PUSH_MS) {
                        pushed = now
                        onDelta(sb.toString())
                    }
                }
            }
        } catch (t: Throwable) {
            if (sb.isEmpty() || cancelled) throw t
        }
        if (sb.isNotEmpty()) onDelta(sb.toString())
        return sb.toString()
    }

    /** 从一帧 SSE 里取出正文增量，兼容 delta / message / 纯 text 几种写法。 */
    internal fun deltaOf(payload: String): String? {
        val obj = try {
            JSONObject(payload)
        } catch (_: Throwable) {
            return null
        }
        val choice = obj.optJSONArray("choices")?.optJSONObject(0) ?: return null
        val holder = choice.optJSONObject("delta") ?: choice.optJSONObject("message")
        if (holder == null) {
            val t = choice.optString("text")
            return if (t.isBlank()) null else t
        }
        return when (val c = holder.opt("content")) {
            is String -> c
            is JSONArray -> buildString {
                for (i in 0 until c.length()) {
                    val part = c.optJSONObject(i) ?: continue
                    append(part.optString("text"))
                }
            }
            else -> null
        }
    }

    /** 报错像是「上下文装不下」时，说明这一趟发太长了，退回去分段整理。 */
    private fun isTooLong(t: Throwable): Boolean {
        val m = (t.message ?: "").lowercase()
        return listOf(
            "context", "too long", "too many tokens", "maximum", "length", "token",
            "超出", "过长", "长度", "上限", "太短"
        ).any { m.contains(it) }
    }

    /** 有些网关不支持流式，会明确报 stream。这时关掉流式重试一次。 */
    private fun looksLikeStreamError(detail: String): Boolean {
        val d = detail.lowercase()
        if (!d.contains("stream")) return false
        return listOf(
            "support", "unsupported", "not ", "invalid", "unknown", "parameter", "不支持"
        ).any { d.contains(it) }
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
        is IOException -> if (t.message == CANCELLED) CANCELLED else "网络不通：${t.message ?: t.javaClass.simpleName}"
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
        你是网课笔记整理助手。下面是一段课堂录音的语音转写，每句带一个 [mm:ss] 时间戳，
        里面可能有同音字识别错误、口语废话和重复语句。

        请把这一段整理成「素材卡」，直接输出 Markdown，不要任何开场白、解释或结尾语：
        1. 按内容分成 1-3 个小节，每节写成 "## 小节标题"，标题不超过 14 个字；不要写 "#" 大标题；
        2. 每节下面用 "- " 列出这一段真正讲到的知识点：定义、结论、公式、步骤、分类、对比、
           成立条件、例子、易错点、考点；一条只说一件事，不要写成流水账；
        3. 这一步只做「提取和清洗」，不要重排体系、不要合并小节，也绝不补充原文没有的内容；
        4. 每个要点开头保留对应时间戳，写成反引号包起来的形式，例如 `[03:12]`；
        5. 带 [[IMG1]] 这样标记的行，是老师当时展示的课件截图；请原样保留标记，
           并把标记后面的说明当作图上已有的内容整理进对应小节；
        6. 删掉寒暄、重复和口头禅；老师强调「要记住 / 必考 / 作业」的内容必须保留；
        7. 按上下文改正明显的同音字错误，把没说完的话补完整，但不要编造。
    """.trimIndent()

    /**
     * 体系化终稿的骨架与要求，短课一次生成、长课合并时都用它。
     *
     * 刻意写成「建议结构 + 按内容取舍」，而不是一张必须填满的表格：
     * 一节没布置作业的课，硬凑出「作业与下节预告」只会让笔记看起来像模板，
     * 而复习时最没用的就是这种空壳小节。
     */
    private val SKELETON = """
        你是课程笔记的体系化编辑。下面是一堂网课的内容（语音转写或分段整理出的素材），
        每句带 [mm:ss] 时间戳，中间可能有 [[IMG1]] 这样的截图标记。

        请把它编辑成一份**成体系**的课堂笔记。判断标准只有一条：
        **同学不看录播、只听这份笔记，也能把这节课学会。**
        所以每个知识点都要说清「是什么、为什么、怎么用、什么情况下会错」，
        而不是把老师说过的话压缩一遍。

        结构要跟着这节课的内容走，不要套模板：
        - 以例题、讲解为主的课，就按「题目 → 思路 → 步骤 → 结论」组织；
        - 以概念、原理为主的课，就按「定义 → 为什么 → 适用条件和边界 → 易错点」组织；
        - 以操作、流程为主的课，就按「准备 → 步骤 → 常见坑」组织；
        - 小标题写这节课真实讲的东西（「两个容易混的公式」「为什么要先归一化」），
          不要每篇都用同一批小标题。
        **没有内容的小节一律不要出现**：这节课没布置作业，就不要有「作业」这一节；
        没讲公式，就不要术语表；老师没提考点，就不要考点小节。宁可短，不要空壳。

        直接输出 Markdown，不要任何寒暄、说明或结尾语。可以参照下面这个结构，按内容增删：

        第一行是一条引用块导语（以 "> " 开头）：一句话说明这节课讲什么、学完能做什么。

        ## 本课知识框架
        用缩进列表画出这一课的体系：主题 → 分支 → 具体知识点，3-8 行，让人一眼看到全貌。

        ## 一、大节标题
        ### 1.1 子知识点
        - 先给结论 / 定义 / 公式，再补为什么、成立条件和适用场景
        - 例子、推导步骤、和相邻概念的区别，一条一件事，写成完整的话
        - 易错点单独成条：容易错在哪、正确做法是什么
        ### 1.2 子知识点
        - ……

        ## 二、大节标题
        （同上；大节数量按内容定，一般 2-6 个，序号用中文数字）

        ## 术语与公式        ← 只在真的出现了术语 / 公式时写
        | 术语 / 公式 | 含义 |
        | --- | --- |
        | …… | …… |

        ## 易错点与考点      ← 只在这节课确实讲到时写

        ## 一句话总结

        ## 作业与下节预告    ← 只在老师布置了作业或预告了下节课时写，没有就整节不要

        要求：
        1. 层级最多三层（## / ### / -），不要用 ####，也不要自己写 "#" 大标题（标题由 App 添加）；
        2. **不要堆标题**：一个小节下 3-6 条要点就够，把零碎的话归并成完整的句子，别一句话一个标题；
        3. 保留原文的时间戳，写成反引号包起来的形式，例如 `[03:12]`，放在对应要点开头或句末；
        4. 正文里出现 [[IMG1]] 这类标记时，把标记**单独放一行**插在它**对应时间戳那句话的下面**，
           原样照抄标记（不要加反引号、不要改写、不要翻译），并把图上的说明融进正文；
        5. 合并重复内容、删掉口水话，但老师强调的重点、作业、考试范围不能删；
        6. 修正明显的同音字错误、把不通顺的句子补顺；不要编造原文没有的知识点；
        7. 笔记里只写知识本身，不要出现「这段转写」「视频里」「录音中」这类话；
        8. **全篇 500-900 字**：这是在写复习用的提纲，不是课堂实录。每个要点一句话说完，
           不复述老师原话、不写铺垫、不做名词解释的堆砌；内容少就写更短。
    """.trimIndent()

    /** 单段（或素材卡）直接出终稿，用的就是骨架本身。 */
    private val FINALIZE = SKELETON

    private val MERGE = """
        下面是同一堂课分几段整理出的几版笔记，内容可能有重复、小节可能对不上。
        请把它们合并成**一份**不重复、层级连贯的体系化笔记：把同一主题的要点归到同一个大节下，
        重复的小节和要点只留一条，时间戳照旧保留。
        合并只做归并和去重：不要发明原文没有的新内容，也不要把 [[IMG1]] 这类标记弄丢或改号。

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
