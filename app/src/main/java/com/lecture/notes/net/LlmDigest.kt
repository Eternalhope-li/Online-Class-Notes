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

    /** 一次整理的结果：正文 + 模型给的这节课的题目（没给就是空串）。 */
    class Draft(val topic: String, val body: String)

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
    /** 长课第一步的「提纲」：只有骨架和要点短语，输出收窄。 */
    private const val OUTLINE_MAX_TOKENS = 1600
    /** 提纲一轮发得完的素材字数，超了先分组出提纲、再把提纲并成一份。 */
    private const val OUTLINE_CHARS = 12000
    /** 逐节成文时，一节最多带多少素材（按时间戳从素材卡里切出来）。 */
    private const val SECTION_CHARS = 7000
    /** 逐节成文时，一节写多少字：一节一版的输出预算。 */
    private const val SECTION_MAX_TOKENS = 1400
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
     * 整理一篇笔记，返回正文和这节课的题目（见 [Draft]）。[onProgress] 的参数是（已完成段数, 总段数, 正在做什么）。
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
    ): Draft = withContext(Dispatchers.IO) {
        cancelled = false
        val key = Prefs.llmKey.trim()
        if (key.isEmpty()) throw LlmException("还没有填写 API Key")
        val plan = plan(note)
        if (plan.chunks.isEmpty()) throw LlmException("这篇笔记还没有内容")

        // 流式回调也过一遍图片还原和题目行剥离：边生成边看到的就是最终排版
        val live: ((String) -> Unit)? = onPartial?.let { cb ->
            { md: String ->
                cb(
                    splitTopic(
                        stripTimestamps(
                            embedImages(MiniMarkdown.demoteFences(unwrapFence(md)), note, appendFallback = false)
                        )
                    ).second
                )
            }
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
        // 顺序有讲究：先拆掉模型加的围栏（整篇的、包半截的，见 MiniMarkdown.demoteFences），
        // 再去重（这时图还没插进去，删重复不会带走图片），
        // 然后插图和图示兜底，最后收掉「没布置作业」的空壳小节
        val clean = tidyDigest(MiniMarkdown.demoteFences(unwrapFence(body)))
        val placed = dropEmptyHomework(unwrapFence(MiniMarkdown.hoistImages(embedImages(clean, note))))
        // 第一行是模型写的「题目：xxx」，它只用来给笔记命名，正文里不留这一行
        // 时间戳的使命到这儿就结束了：图已经按它插好了，成品里不再留坐标
        val (topic, text) = splitTopic(stripTimestamps(placed))
        Draft(topic.ifEmpty { topicFromLead(text) }, text)
    }

    /**
     * 长课走这条路：素材卡（分段并行）→ 一份全局提纲 → 按提纲逐节成文（并行）。
     *
     * 为什么不是「把所有素材卡合成一版终稿」：那样整堂课只有一个输出预算，两小时的课会被压成
     * 一千多字的骨架，越长的课丢得越多 —— 而合并那一步读到的已经是压缩过的文字，再压一遍就只剩
     * 标题了。改成「先定体系、再按节写」之后，每一节都有自己那段素材、自己的输出预算，课再长也
     * 不会被摊薄；各节并行发出，总等待时间不比自己写一遍长。
     *
     * 提纲没能分出大节（模型不听话、素材太碎）时退回老路：把素材卡合成一版终稿。
     */
    private suspend fun segmented(
        key: String,
        plan: Plan,
        onProgress: (Int, Int, String) -> Unit,
        live: ((String) -> Unit)?
    ): String {
        val cards = material(key, plan.chunks, onProgress)
        onProgress(plan.chunks.size, plan.chunks.size, "先理提纲")
        val outline = outlineOf(key, cards)
        val o = sectionsOf(outline)
        val write = o.sections.filter { it.body.isNotBlank() }
        if (o.sections.isEmpty() || write.isEmpty()) {
            onProgress(plan.chunks.size, plan.chunks.size, "体系化合并")
            return finalize(key, cards.joinToString("\n\n"), live)
        }
        onProgress(0, o.sections.size, "按提纲成文")
        val done = AtomicInteger(0)
        val texts = inParallel(o.sections) { _, sec ->
            val n = done.incrementAndGet()
            onProgress(n, o.sections.size, "第 $n/${o.sections.size} 节")
            if (sec.body.isBlank()) return@inParallel sec.head
            // 需要成文的节才有素材需求；知识框架、一句话总结这类栏目直接用提纲里的写法
            if (sec.keep) return@inParallel (sec.head + "\n" + sec.body).trim()
            val from = sectionMaterial(cards, sec)
            try {
                val ask = buildString {
                    append("请只写下面这一节的笔记，不要写题目、导语和别的小节。\n\n")
                    append("【这节课的全局提纲】（照着它的体系写，不要改结构）\n")
                    append(outline.trim()).append("\n\n")
                    append("【要写的小节】\n").append(sec.head).append("\n")
                    append(sec.body.trim()).append("\n\n")
                    append("【这一节的课堂素材】\n").append(from)
                }
                val got = clean(call(key, SECTION, ask, maxTokens = SECTION_MAX_TOKENS)).trim()
                if (got.startsWith("##")) got else sec.head + "\n" + got
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                sec.head + "\n" + sec.body   // 这一节写砸了就用提纲里的写法顶上，别整篇失败
            }
        }
        return (listOf(o.preamble) + texts).filter { it.isNotBlank() }.joinToString("\n\n").trim()
    }

    /** 全局提纲：素材卡不太长时一轮出；太长就分组出提纲再并成一份。 */
    private suspend fun outlineOf(key: String, cards: List<String>): String {
        val all = cards.joinToString("\n\n")
        if (all.length <= OUTLINE_CHARS) {
            return clean(call(key, OUTLINE, all, maxTokens = OUTLINE_MAX_TOKENS))
        }
        val parts = inParallel(splitText(all, OUTLINE_CHARS)) { _, g ->
            clean(call(key, OUTLINE, g, maxTokens = OUTLINE_MAX_TOKENS))
        }
        if (parts.size == 1) return parts[0]
        return clean(call(key, OUTLINE_MERGE, parts.joinToString("\n\n"), maxTokens = OUTLINE_MAX_TOKENS))
    }

    /**
     * 这一节对应的素材：素材卡里时间戳落在这一节范围内（两边各放宽 20 秒）的那些。
     *
     * 一节一条时间戳都没有（模型忘了写）时不能只按时间挑 —— 那样会挑空，这一节就没素材了；
     * 这时候退回「把素材卡都给它」（由 [SECTION_CHARS] 截断），宁可多喂一点也别漏内容。
     */
    private fun sectionMaterial(cards: List<String>, sec: Sec): String {
        val slice = if (sec.from < 0) {
            cards.joinToString("\n\n")
        } else {
            val lo = sec.from - 20
            val hi = sec.to + 20
            val hit = cards.filter { c -> tsRange(c).first >= 0 && tsRange(c).second >= lo && tsRange(c).first <= hi }
            (if (hit.isEmpty()) cards else hit).joinToString("\n\n")
        }
        return if (slice.length <= SECTION_CHARS) slice else slice.substring(0, SECTION_CHARS)
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
    private suspend fun <T, R> inParallel(items: List<T>, block: suspend (Int, T) -> R): List<R> =
        coroutineScope {
            val gate = Semaphore(PARALLEL)
            items.mapIndexed { i, s -> async { gate.withPermit { block(i, s) } } }.awaitAll()
        }

    /** 提纲里拆出来的一个大节。 */
    internal class Sec(val head: String, val body: String) {
        /** 这一节的时间范围（秒）：按正文里的时间戳算；一条都没有就是 -1。 */
        val from: Int = tsRange(body).first
        val to: Int = tsRange(body).second
        /** 知识框架、术语表、一句话总结这类栏目直接用提纲里的写法，不再逐节扩写。 */
        val keep: Boolean get() = KEEP_SECTIONS.containsMatchIn(head)
    }

    /** 提纲拆出来的结果：大节之前的部分（题目行、导语、知识框架…）+ 各个大节。 */
    internal class Outline(val preamble: String, val sections: List<Sec>)

    /** 「知识框架 / 术语表 / 一句话总结」这类栏目由提纲直接给，不必再逐节扩写。 */
    private val KEEP_SECTIONS = Regex("框架|术语|公式表|易错|考点|总结|作业|下节|预告")

    /** 一段文字里的时间戳范围（秒）；一个都没有返回 -1 / -1。 */
    private fun tsRange(text: String): Pair<Int, Int> {
        val ts = TS_RE.findAll(text).map {
            (it.groupValues[1].toIntOrNull() ?: 0) * 60 + (it.groupValues[2].toIntOrNull() ?: 0)
        }.toList()
        return if (ts.isEmpty()) -1 to -1 else ts.min() to ts.max()
    }

    /**
     * 把提纲拆成「前言 + 大节」。只认 "## " 那一级大节，大节下面的 "### " 和要点都算它的正文。
     * 提纲整体不听话（没有任何大节）时返回空的 sections，调用方据此退回「合并一版终稿」。
     */
    internal fun sectionsOf(outline: String): Outline {
        val lines = outline.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val pre = ArrayList<String>()
        val secs = ArrayList<Sec>()
        var head: String? = null
        var body = ArrayList<String>()
        fun flush() {
            val h = head ?: return
            secs.add(Sec(h.trim(), body.joinToString("\n").trim()))
        }
        for (raw in lines) {
            val t = raw.trim()
            if (t.startsWith("## ") || t == "##") {
                flush()
                head = t
                body = ArrayList()
            } else if (head == null) {
                if (t.isNotEmpty()) pre.add(raw.trimEnd())
            } else {
                body.add(raw.trimEnd())
            }
        }
        flush()
        return Outline(pre.joinToString("\n").trim(), secs)
    }

    /**
     * 把整篇笔记切成能一次发完的几段。
     *
     * 语音转写一句一行；截图条目不止一行 —— 它后面跟着视觉模型认出的每一条图上内容
     * （见 [linesOf]）。这里曾经把说明压成一行，模型就只吃掉了开头半句，
     * 图上的表格、地址、公式全没进笔记。
     */
    private fun chunk(note: Note): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (e in note.entries) {
            val lines = linesOf(e)
            if (lines.isEmpty()) continue
            val width = lines.sumOf { it.length + 1 }
            if (sb.isNotEmpty() && sb.length + width > CHUNK_CHARS) {
                out.add(sb.toString())
                sb.setLength(0)
            }
            for (line in lines) sb.append(line).append('\n')
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /**
     * 一条记录在素材里占的行。
     *
     * 语音转写就是一行；截图条目是「一行头部 + 缩进列出的图上内容」：视觉模型认出来的每条文字、
     * 数字、表头、代码都单占一行，模型才会把它们当素材逐条整理 —— 压成一行就只会被吃掉第一句。
     * 图片本身插在哪由 App 按时间戳算（见 [embedImages]），这里只负责把「图上讲了什么」喂过去。
     */
    private fun linesOf(e: Entry): List<String> {
        val head = "[" + Formats.mmss(e.atMs) + "] "
        if (!e.isImage) {
            if (e.text.isBlank()) return emptyList()
            return listOf(head + if (e.star) "★ " + e.text else e.text)
        }
        val items = e.caption.replace("\r\n", "\n").replace('\r', '\n')
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val mark = if (e.star) "★ " else ""
        if (items.isEmpty()) {
            return listOf(head + mark + "[图示] 老师当时展示的课件截图（没有识别出文字）")
        }
        val out = ArrayList<String>(items.size + 1)
        out.add(head + mark + "[图示] 老师当时展示的课件截图，下面是视觉模型认出的图上内容：")
        for (raw in items) {
            // 视觉模型爱把整段内容包进代码块：围栏本身不是内容，喂过去只会让模型跟着抄围栏
            if (raw.startsWith("```")) continue
            // 视觉模型爱用 "## 定义："" > 要点：" 这类栏目名开头，原样喂过去，模型就会把它照抄成
            // 笔记的小标题；这里先把记号洗掉，只把内容本身交给整理那一步
            if (raw[0] == '|' || raw[0] == '`') {
                out.add("    " + raw)
                continue
            }
            val t = raw.trim('-', '*', '+', '>', '#', '·', '•', ' ')
            if (t.isEmpty()) continue
            out.add("    - " + t)
        }
        return out
    }

    /** 整篇里所有的截图，按截图时间排好。 */
    internal fun shots(note: Note): List<Entry> =
        note.entries.filter { it.isImage && !it.image.isNullOrBlank() }.sortedBy { it.atMs }

    /** 老提示词让模型抄回来的 `[[IMG1]]` 记号：现在还认得出来，统一擦掉。 */
    private val IMG_TOKEN = Regex("`?\\[\\[\\s*IMG\\s*(\\d+)\\s*]]`?")

    /** 行内残留的「[图示]」占位词。 */
    private val SHOT_TAG = Regex("`?\\[\\s*图\\s*示\\s*]`?")

    /** 行首的「[图示]」「图示：」「**图示**：」——连同冒号一起擦掉，前面的列表符号留着。 */
    private val SHOT_TAG_LEAD = Regex(
        "^(\\s*(?:[-*+]\\s+)?)(?:\\*\\*)?(?:\\[\\s*图\\s*示\\s*]\\s*[:：]?|图\\s*示\\s*[:：])(?:\\*\\*)?\\s*"
    )

    /** 正文里的 `[mm:ss]`，用来判断某张截图该落在哪句话下面。 */
    private val TS_RE = Regex("\\[(\\d{1,2}):(\\d{2})]")

    /**
     * 把每一张截图插回正文里「当时正在讲它的那句话」下面。
     *
     * 位置是 App 自己算的，不问模型：模型只要一偷懒，整篇的图就会全堆到文末 ——
     * 而「这张图是什么时候截的」App 本来就知道，整理稿里又留着 `[mm:ss]`，
     * 于是「插到时间上最贴近的那条要点下面」就能让图回到它该在的那一小节里。
     *
     * 模型偶尔还会自己把 `[[IMG1]]`、`[图示]` 抄进正文，先统一擦干净再插，
     * 免得成品里留一串看不懂的记号。插进去的只有图片本身：图上讲了什么，模型已经按上面的
     * 要求整理进周围的要点里了，不再把视觉模型的原文照抄一遍。
     *
     * [appendFallback] 为真时，正文一行时间戳都没有（模型把时间戳全删了）才退回
     * 文末的「本课图示」小节 —— 位置差一点也比丢图好。流式预览传 false：
     * 正文还没吐出来的时候不该先在文末滚一排图。
     */
    internal fun embedImages(md: String, note: Note, appendFallback: Boolean = true): String {
        val all = shots(note)
        if (all.isEmpty()) return md
        val body = stripMarks(md)
        val placed = placeByTime(body, all)
        if (placed != null) return placed
        if (!appendFallback) return body
        return (body.trimEnd() + "\n\n" + NoteStore.shotsSection(note)).trim()
    }

    /** 擦掉模型抄回来的图片记号与占位词，顺手收掉被擦空的行。 */
    private fun stripMarks(md: String): String {
        val out = StringBuilder(md.length)
        for (raw in md.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            var line = raw
            if (line.contains("[[")) line = IMG_TOKEN.replace(line, "")
            if (line.contains("图")) {
                line = SHOT_TAG.replace(line, "")
                val m = SHOT_TAG_LEAD.find(line)
                if (m != null) line = m.groupValues[1] + line.substring(m.range.last + 1)
            }
            line = line.trimEnd()
            val bare = line.trimStart()
            if (bare.isEmpty() || bare == "-" || bare == "*" || bare == "+") {
                out.append('\n')
                continue
            }
            out.append(line).append('\n')
        }
        return out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /**
     * 每张图插到时间上最贴近的那条要点下面。
     *
     * 落点只认「带时间戳的正文行」：标题行不算（插在标题和正文之间很别扭），
     * 表格行、代码块也不算（插进去会把表格拦腰截断）。哪一条的 `[mm:ss]` 离截图时间最近
     * 就算哪一条 —— 比截图早的和晚的都算，一样近时优先前面那条。整篇一个时间戳都没有
     * 就返回 null，交给调用方退回文末的汇总小节。
     */
    private fun placeByTime(body: String, images: List<Entry>): String? {
        val lines = body.split('\n')
        val anchors = anchorsOf(lines)
        if (anchors.isEmpty()) return null
        val buckets = HashMap<Int, MutableList<Entry>>()
        for (e in images) {
            val want = (e.atMs / 1000L).toInt()
            var at = anchors.first()
            var best = Int.MAX_VALUE
            for (i in anchors) {
                val t = tsOf(lines[i])
                val gap = if (t > want) t - want else want - t
                if (gap < best) {
                    best = gap
                    at = i
                }
            }
            buckets.getOrPut(at) { ArrayList() }.add(e)
        }
        val out = ArrayList<String>(lines.size + images.size * 3)
        for ((i, line) in lines.withIndex()) {
            out.add(line)
            val group = buckets[i] ?: continue
            for (e in group) {
                out.add("")
                out.add(figure(e))
            }
        }
        return out.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /**
     * 整理稿里的图片本身：`![课堂截图 mm:ss](shots/x.jpg)`。
     *
     * 只放图 —— 图上讲了什么已经由模型整理进周围的要点里了，不再把视觉模型的原文照抄在图下面
     * （那样一篇笔记里同样的内容会出现两遍，而且是一段没整理过的话）。
     * 只有整篇一个时间戳都没有、模型没机会整理时才退回 [NoteStore.shotsSection]，
     * 那时才连说明一起列出来 —— 那种情况下把图丢了比位置差更糟。
     */
    private fun figure(e: Entry): String =
        "![课堂截图 " + Formats.mmss(e.atMs) + "](" + e.image + ")"

    /** 正文最前面那行「题目：xxx」——模型用它给这节课起名，整理稿里不留这一行。 */
    private val TOPIC_RE = Regex("^[\\s#*>\\-]*(?:\\*\\*)?题\\s*目(?:\\*\\*)?[\\s:：]+(.+?)\\s*$")

    /**
     * 把模型写的第一行题目摘出来，返回（题目, 去掉题目行的正文）。
     *
     * 题目必须是正文最前面那行内容（前面最多允许两行空行），找不到就返回空题目 ——
     * 名字交给 App 用，所以先做一遍清洗：去掉强调记号、书名号，长度也收一收，免得侧边栏被撑爆。
     */
    internal fun splitTopic(md: String): Pair<String, String> {
        val lines = md.replace("\r\n", "\n").replace('\r', '\n').split('\n').toMutableList()
        // 题目必须是最前面那行非空内容：正文里正常出现的「题目：」不该被摘走
        val head = lines.indexOfFirst { it.isNotBlank() }
        if (head < 0 || head > 2) return "" to md
        val m = TOPIC_RE.find(lines[head]) ?: return "" to md
        val name = m.groupValues[1].trim()
            .trim('*', '`', '#', '「', '」', '《', '》', '。', '，', '：', ':').trim()
        if (name.isEmpty()) return "" to md
        lines.removeAt(head)
        return name.take(24) to lines.joinToString("\n").trim()
    }

    /**
     * 模型偶尔把整篇笔记包在 ``` 代码块里（glm-4-flash 会这么干）。
     *
     * 不拆的话，排版视图会把整篇当成一个代码块，用户看到的就是一大片带 `##`、`- ` 的 markdown
     * 源码（这是被反馈过的「整理之后全是源码」）；而且 App 认不出里面的时间戳，截图也就插不回去。
     *
     * 所以只要「第一行是围栏开头 + 最后一行是围栏结尾」就拆 —— 不去数里层的围栏是否成对：
     * 里层剩下的围栏（模型从课件说明里抄来的、或者真的代码块）交给解析层正常处理，
     * 而「整篇包一层」在任何情况下都不是我们想要的排版。
     */
    internal fun unwrapFence(md: String): String {
        var cur = md
        // 偶尔会包两层，拆到不是为止（最多三轮，防呆）
        repeat(3) {
            val lines = cur.replace("\r\n", "\n").replace('\r', '\n').trim().lines()
            if (lines.size < 3) return cur
            if (!isFenceOpen(lines.first().trim().lowercase())) return cur
            if (lines.last().trim() != "```") return cur
            cur = lines.subList(1, lines.size - 1).joinToString("\n").trim()
        }
        return cur
    }

    /** 整篇围栏的开头行：``` 或 ```markdown / ```md / ```text。 */
    private fun isFenceOpen(line: String): Boolean =
        line == "```" || line.startsWith("```markdown") || line.startsWith("```md") ||
            line.startsWith("```text")

    /** 导语里那些「承上启下」的开头词，做笔记名的时候都没有意义：主打信息量。 */
    private val LEAD_VERBS = listOf(
        "本节课", "这节课", "本课", "本讲", "今天", "我们", "主要", "讲解", "介绍", "学习", "聊聊",
        "将", "要", "会"
    )

    /**
     * 模型忘了写「题目：」时的退路：从导语那一行里抠一个名字出来。
     *
     * 「本节课将介绍 Socket 的概念、作用以及…」→ 「Socket 的概念」：先取第一个分句，再把开头那些
     * 没有信息量的动词一层层剥掉。剥到没剩下什么（短于两个字）就返回空串 —— 名字还是留着原来的，
     * 总比换成「将」这种半截话好。
     */
    internal fun topicFromLead(md: String): String {
        val lead = md.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            .firstOrNull { it.trimStart().startsWith(">") } ?: return ""
        var t = lead.trimStart().trimStart('>').trim()
        val cut = t.indexOfFirst { it in "，。；：、,." }
        if (cut > 0) t = t.substring(0, cut)
        while (true) {
            val v = LEAD_VERBS.firstOrNull { t.startsWith(it) && t.length - it.length >= if (it.length == 1) 4 else 2 }
                ?: break
            t = t.removePrefix(v).trim()
        }
        // 还剩着开头的空话（比如卡在「本节课将」这种半截话上），说明这个导语抠不出名字，索性不要
        if (LEAD_VERBS.any { t.startsWith(it) }) return ""
        t = t.trim(' ', '。', '，', '：', ':', '、', '；')
        return if (t.length < 2) "" else t.take(20)
    }

    /** 要点里的时间戳记号：`[03:12]` / [03:12] 都认。 */
    private val TS_MARK = Regex("`?\\[\\d{1,3}:\\d{2}]`?")

    /**
     * 擦掉成品里的时间戳。
     *
     * 时间戳只在整理过程中有用：App 靠它把课件截图插回「当时正在讲的那句话」下面（见 [embedImages]）。
     * 插完就没用了 —— 用户要的是知识点，不是一串回到录播的坐标。图片题注（`![课堂截图 00:39]`）
     * 里的时刻不带方括号，不会被误伤。
     */
    internal fun stripTimestamps(md: String): String {
        if (!md.contains('[')) return md
        val out = ArrayList<String>()
        for (raw in md.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if (!TS_MARK.containsMatchIn(raw) || raw.trimStart().startsWith("![")) {
                out.add(raw)
                continue
            }
            // 擦掉记号，再把列表符号后面多出来的空格收掉（缩进要留着，子要点靠它）
            var line = TS_MARK.replace(raw, "")
            line = line.replace(Regex("^(\\s*[-*+]\\s+)\\s+"), "$1").trimEnd()
            val bare = line.trimStart()
            if (bare.isEmpty() || bare == "-" || bare == "*" || bare == "+") continue
            out.add(line)
        }
        return out.joinToString("\n").replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    /**
     * 收尾的两件确定性清理（模型反复犯、提示词按不住的毛病）：
     *
     *  1. 同一件事在两个大节里各写一遍（时间戳和句子都一样）→ 只留第一次出现的那条；
     *  2. 某一节被去重掏空之后只剩一个标题 → 连标题一起删掉（空壳小节比缺一节更糟）。
     */
    internal fun tidyDigest(md: String): String {
        val seen = HashSet<String>()
        val kept = ArrayList<String>()
        for (line in md.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val m = BULLET_ONE.find(line)
            if (m != null) {
                val key = dupKey(m.groupValues[1])
                // 太短的句子不参与去重，免得把「注意」「第一步」这种正常短要点误删
                if (key.length >= 8 && !seen.add(key)) continue
            }
            kept.add(line)
        }
        val out = dropEmptySections(kept).joinToString("\n")
        return out.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private val BULLET_ONE = Regex("^\\s*[-*+]\\s+(.*)$")

    /** 去重指纹：时间和标点都不算，只看内容本身。 */
    private fun dupKey(text: String): String = text
        .replace(TS_RE, "")
        .replace(Regex("[\\s*`>#]"), "")
        .replace(Regex("[。，、；：,.;:!？?！\"'（）()【】\\[\\]]"), "")

    /** 只剩一个标题、下面一条内容都没有的小节（去重之后产生）——连标题一起删掉。 */
    private fun dropEmptySections(lines: List<String>): List<String> {
        val out = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val m = HEAD_ONE.find(line)
            if (m == null) {
                out.add(line)
                i++
                continue
            }
            val level = m.groupValues[2].length
            var end = i + 1
            var content = false
            while (end < lines.size) {
                val h = HEAD_ONE.find(lines[end])
                if (h != null && h.groupValues[2].length <= level) break
                if (lines[end].isNotBlank()) content = true
                end++
            }
            // 有内容（或下面还有更深的小标题）就留着，什么都没有就整节丢掉
            if (content) for (k in i until end) out.add(lines[k])
            i = end
        }
        return out
    }

    private val HEAD_ONE = Regex("^(\\s*)(#{1,6})\\s+\\S")

    /** 「作业 / 课后 / 下节」这类小节的开头。 */
    private val HOMEWORK_HEAD = Regex("^#{1,3}\\s*(作业|课后|下节)")

    /** 「本节课没有布置作业」这类占位话。 */
    private val NO_HOMEWORK = Regex("(没有|未|无)[^。；]{0,6}(布置)?\\s*(课后)?作业")

    /**
     * 老师没布置作业、也没预告下节课时，模型偏偏要留一节「作业与下节预告」，
     * 里面写「本节课没有布置作业」占位，还顺手编一句下节课讲什么 —— 整节删掉。
     *
     * 这是提示词反复按住、glm-4-flash 还是会犯的毛病，所以在结果上再兜一道：
     * 只要这一节里出现「没（有）布置作业」这种话，就认定它是空壳，连标题一起收掉。
     * 真布置了作业的小节里不会这么写，不会被误伤。
     */
    internal fun dropEmptyHomework(md: String): String {
        val lines = md.replace("\r\n", "\n").replace('\r', '\n').split('\n').toMutableList()
        var i = 0
        while (i < lines.size) {
            if (!HOMEWORK_HEAD.containsMatchIn(lines[i].trimStart())) {
                i++
                continue
            }
            var end = i + 1
            while (end < lines.size && !lines[end].trimStart().startsWith("#")) end++
            val body = lines.subList(i + 1, end).joinToString(" ")
            if (!NO_HOMEWORK.containsMatchIn(body)) {
                i = end
                continue
            }
            var from = i
            while (from > 0 && lines[from - 1].isBlank()) from--
            var to = end
            while (to < lines.size && lines[to].isBlank()) to++
            lines.subList(from, to).clear()
            i = from
        }
        return lines.joinToString("\n").trim()
    }
    /** 能当截图落点的行：带时间戳的正文行（标题、表格、代码块都不算）。 */
    private fun anchorsOf(lines: List<String>): List<Int> {
        val out = ArrayList<Int>()
        var fence = false
        for ((i, line) in lines.withIndex()) {
            val t = line.trimStart()
            if (t.startsWith("```")) {
                fence = !fence
                continue
            }
            if (fence || tsOf(line) < 0) continue
            if (t.startsWith("#") || t.startsWith("|") || t.startsWith(">")) continue
            out.add(i)
        }
        return out
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
        5. 「[图示]」行是老师当时展示的课件截图，紧跟其后缩进列出的每一条，都是视觉模型从这张图上
           读出的真实内容（文字、表格、公式、代码、数字）；它是正式素材：**每一条都要**整理成这一
           小节的笔记要点，一条都不能漏；图上的数字、地址、表头、公式、代码、专有名词要**原样
           保留**，不要概括成一句话、不要意译；把这一行的时间戳留在对应要点上，也别写「[图示]」
           这几个字 —— App 会按时间戳把图片本身插到对应位置；
        6. 删掉寒暄、重复和口头禅；老师强调「要记住 / 必考 / 作业」的内容必须保留；
        7. 按上下文改正明显的同音字错误，把没说完的话补完整，但不要编造；
        8. 直接输出内容本身，不要用 ``` 把整张卡包起来（真的代码才用代码块）。
    """.trimIndent()

    /**
     * 长课第一步：先理一份全局提纲（只有骨架和要点短语，不写成段的讲解）。
     *
     * 这一步的存在就是为了「长课不被压扁」：提纲很小，所以一节课的体系能一次看全；接下来每一节
     * 再拿着自己那段素材单独写，输出预算按节走，课再长也不会越写越薄。
     */
    private val OUTLINE = """
        你是课程笔记的结构编辑。下面是一整堂课分段的「素材卡」，每句带 [mm:ss] 时间戳。

        先在心里想好这节课的体系，然后只输出一份**提纲**（不要写成段的讲解，全篇 600 字以内）：
        第一行：「题目：xxx」—— xxx 是这节课的主题，12-20 个字，不要编号、不要书名号、不要句号；
        第二行：一条引用块导语（以 "> " 开头），一句话说明这节课讲什么、学完能做什么；
        然后按内容分 3-8 个「## 大节」，标题写成这节课真实讲的东西（「两个容易混的公式」这种），
        不要每篇都用同一批标题；内容多的大节下面可以用 "### 小节" 再分一层；
        每个大节下面用 "- `[mm:ss]` 一句话" 列出这一节要讲的要点（每条不超过 25 字，只写要点本身）；
        素材里「[图示]」后面列出的图上内容，是课件截图里的真实信息，把关键内容写进对应位置的要点里，
        并保留它原来的时间戳。
        没讲的东西不要写：没讲公式就不要术语表，没布置作业就不要作业小节。
        只输出 Markdown 提纲，不要任何说明文字，也不要用 ``` 把整篇包起来。
    """.trimIndent()

    /** 素材太长、提纲需要分组出的时候，把几份提纲并成一份。 */
    private val OUTLINE_MERGE = """
        下面是同一堂课分几段理出的几份提纲，内容有重复、大节可能对不上。
        请把它们并成**一份**连贯的提纲：同一个主题的大节合并成一个，重复的要点只留一条，
        顺序按课程原本的先后走，时间戳照旧保留（别把图上的要点和它的时间戳弄丢）。
        格式不变：第一行「题目：xxx」，第二行 "> " 导语，然后是 3-8 个「## 大节」+ 要点。
        只输出提纲，不要说明文字，也不要用 ``` 包起来。
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
        每句带 [mm:ss] 时间戳，中间可能有老师展示课件时的「[图示]」说明。

        请把它编辑成一份**成体系**的课堂笔记。判断标准只有一条：
        **同学不看录播、只听这份笔记，也能把这节课学会。**
        所以每个知识点都要说清「是什么、为什么、怎么用、什么情况下会错」，
        而不是把老师说过的话压缩一遍。

        怎么写完全由这节课本身决定 —— 不要套模板，也不要把每一课都整理成一个样子：
        - 先自己判断这是什么课，再决定怎么组织：以例题、讲解为主的课，按「题目 → 思路 → 步骤 →
          结论」组织；以概念、原理为主的课，按「定义 → 为什么 → 适用条件和边界 → 易错点」组织；
          以操作、流程为主的课，按「准备 → 步骤 → 常见坑」组织；只讲了一件小事的短课，
          三两段讲透就行，不必分大节，也不必凑出「知识框架」这种栏目。
        - 小标题写这节课真实讲的东西（「两个容易混的公式」「为什么要先归一化」），
          不要每篇都用同一批小标题。
        - 栏目是「有内容才写」，不是「照着填」：没讲公式就不要术语表，没提考点就不要考点小节，
          没布置作业就不要作业小节 —— 空壳小节对复习没有任何价值。

        直接输出 Markdown，不要任何寒暄、说明或结尾语。下面只是一份写法示意，按这节课的内容增删，
        不需要的栏目整段都不要写：

        第一行写这节课的题目，格式固定成「题目：xxx」—— xxx 就是这节课讲的主题，12-20 个字，
        不要编号、不要书名号、不要句号；这一行用来给笔记命名，App 会把它从正文里去掉；
        第二行是一条引用块导语（以 "> " 开头）：一句话说明这节课讲什么、学完能做什么。

        ## 本课知识框架        ← 只在内容真的成体系、有多个分支时才写；短课不要这一节
        用缩进列表画出这一课的体系：主题 → 分支 → 具体知识点，3-8 行，让人一眼看到全貌。

        ## 一、大节标题
        ### 1.1 子知识点
        - 先给结论 / 定义 / 公式，再补为什么、成立条件和适用场景
        - 例子、推导步骤、和相邻概念的区别，一条一件事，写成完整的话
        - 易错点单独成条：容易错在哪、正确做法是什么
        ### 1.2 子知识点
        - ……

        ## 二、大节标题
        （同上；大节数量按内容定，一般 2-6 个，序号用中文数字；内容少就只写一个大节，
         或干脆不分节、直接列要点）

        ## 术语与公式        ← 只在真的出现了术语 / 公式时写
        | 术语 / 公式 | 含义 |
        | --- | --- |
        | …… | …… |

        ## 易错点与考点      ← 只在这节课确实讲到时写

        ## 一句话总结

        ## 作业与下节预告    ← 只在老师布置了作业或预告了下节课时写；没有就**整节删掉**，
                              别用「本节课没有布置作业」这种话占位，也别猜下节课讲什么

        要求：
        1. **每条要点开头都要带它自己的时间戳**，写成反引号包起来的形式，例如 `[03:12]`（放要点开头或
           句末都行，但不能没有）—— App 靠时间戳把课件截图插回「当时正在讲的那句话」下面，整篇少了
           时间戳，图就只能全堆到文末，笔记就散了；这些时间戳只是给 App 定位用的记号，**成品里不会显示**，
           所以别在正文里另写「见第几分钟」这类话；
        2. 层级最多三层（## / ### / -），不要用 ####，也不要自己写 "#" 大标题（标题由 App 添加，
           你只要在最前面留一行「题目：xxx」）；
        3. **不要堆标题**：一个小节下 3-6 条要点就够，把零碎的话归并成完整的句子，别一句话一个标题；
        4. 素材里带「[图示]」的段落是老师展示的课件截图，紧跟其后缩进列出的每一条，都是视觉模型
           从图上读出的真实内容：它们是正式素材，**必须逐条**整理进所在知识点的要点（定义、公式、
           表格、流程、结论各成一条，写成通顺完整的话，一条都不能漏）；内容多的时候（表格、代码、
           一串参数或步骤），就在这条要点下面用缩进子要点（两格缩进 + "- "）逐条列出，别为了压短
           丢掉它们；图上的数字、地址、表头、公式、代码、专有名词**原样保留**，不要概括、不要
           意译；不要把图上的栏目名（「标题」「定义」「要点」这类）原样抄成要点，要写成完整的一句话；
           也不要照抄「这张图片展示了…」这类描述，正文里别写「[图示]」几个字 —— App 会按时间戳把
           图片本身插到对应位置；
        5. 合并重复内容、删掉口水话，但老师强调的重点、作业、考试范围不能删；
        6. 修正明显的同音字错误、把不通顺的句子补顺；不要编造原文没有的知识点；
        7. 笔记里只写知识本身，不要出现「这段转写」「视频里」「录音中」这类话；
        8. **长度由内容定，既别压扁也别灌水**：一节课的内容写 800-1500 字，内容少的课写短一点就行。
           每个要点都写成完整的一句话（结论 + 关键条件或原因），不要只留一个名词、也不要复述老师
           原话；素材里有课件截图的，图上的细节不计入这个长度 —— 该留的表格、代码、数字要留住；
        9. **同一件事只写一遍**：同一个知识点不要在两个大节里各写一遍（「MAC 地址表记录端口」写在
           第一节，就别在第二节再来一条一模一样的）；一节写完就结束，不要加「本节介绍了……」这种
           过场话，也不要把同一句话换个说法又说一遍；
        10. 整篇**不要用 ``` 包起来** —— 代码块只用在真的代码上，笔记本身不要塞进代码块。
    """.trimIndent()

    /** 单段（或素材卡）直接出终稿，用的就是骨架本身。 */
    private val FINALIZE = SKELETON

    private val MERGE = """
        下面是同一堂课分几段整理出的几版笔记，内容可能有重复、小节可能对不上。
        请把它们合并成**一份**不重复、层级连贯的体系化笔记：把同一主题的要点归到同一个大节下，
        重复的小节和要点只留一条，时间戳照旧保留。
        合并只做归并和去重：不要发明原文没有的新内容，也不要把截图处的时间戳、和图上识别出的
        内容弄丢（图上的数字、地址、公式要原样保留）。

    """.trimIndent() + SKELETON

    /**
     * 长课第三步：按提纲把一个「大节」写成正式的笔记。
     *
     * 每一节单独一次请求，所以输出预算按节走（[SECTION_MAX_TOKENS]）—— 这就是长课不被压扁的原因；
     * 顺带各节可以并行发，两小时的课也不会比半小时的课慢多少。
     */
    private val SECTION = """
        你在整理一堂网课的笔记。下面会给你这份笔记的**全局提纲**（了解这节课的整体体系）、
        要写的那一节标题与要点、以及这一节对应的课堂素材（语音转写和课件截图说明，
        每句带 [mm:ss] 时间戳，可能有同音字识别错误、口水话和重复）。

        请只写这一个小节，直接输出 Markdown：
        1. 第一行是这一节的标题，用「## 」开头，和提纲里的标题保持一致（可以小幅润色）；
        2. 接着按提纲里的要点顺序把这几点讲清楚，用 "- " 列表；每条写成完整的一句话
           （结论 + 关键条件或原因），不要只留一个名词，也不要复述老师的口水话；
        3. **每条要点开头保留它自己的时间戳**，写成反引号包起来的形式，例如 `[03:12]` ——
           App 靠时间戳把课件截图插回对应位置，丢了时间戳图就只能堆到文末；这些记号只是给 App 定位
           用的，**成品里不会显示**；
        4. 素材里「[图示]」后面缩进列出的每一条，都是视觉模型从课件截图上读出的真实内容：
           必须逐条整理进对应的要点里，数字、地址、表头、公式、代码、专有名词原样保留；
           内容多时用缩进子要点列出；不要把「标题」「定义」「要点」这类栏目名抄成小标题，
           也不要写「这张图展示了……」这类话；
        5. 老师强调的重点、考点、作业必须保留；明显的同音字错误按上下文改正，只改错、不加戏；
        6. 只写这一节：不要写这节课的题目、导语、总结，也不要替别的小节写内容；
        7. 这一节写 3-8 条要点就够了（内容多可以用 "### " 再分一层）；同一件事只说一遍，
           不要为了写长而重复；整节**不要用 ``` 包起来**。
    """.trimIndent()

    /** 视觉模型的提示词。要短、要具体，因为免费视觉模型上下文都不大。 */
    private val VISION_SYS = """
        你是网课笔记助手，专门看懂课堂截图：课件 PPT、老师板书、图表、公式、代码、题目。
        只输出 Markdown 内容本身，不要开场白，不要说明你在做什么。
    """.trimIndent()

    private val VISION_PROMPT = """
        这是我在网课上截的一张图。请把图上的内容整理成能直接放进笔记的 Markdown：
        1. 第一行以 "- " 开头，一句话说清这张图在讲什么；直接写内容本身，不要用
           「这张图 / 该图 / 图片中」开头，不要「展示了」「主要元素包括」这类套话，不超过 25 字；
        2. 图上真实出现的标题、定义、结论、公式、代码，逐条抄下来，每条以 "- " 开头，
           一条只说一件事，每条不超过 30 字；短公式用反引号包起来，例如 `a²+b²=c²`；
        3. 是表格就用 Markdown 表格还原；是流程图、结构图、箭头关系，就写成 "- A → B → C" 说明层次；
        4. 最后一行以 "> 要点：" 开头，写这张图最需要记住的 1-2 点。
        一共不超过 8 条；只写图上真实存在的内容，看不清就写「（图中字迹模糊）」，绝对不要编造。
    """.trimIndent()
}
