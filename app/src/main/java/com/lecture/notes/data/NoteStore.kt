package com.lecture.notes.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.lecture.notes.R
import com.lecture.notes.util.Formats
import com.lecture.notes.util.MiniMarkdown
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Calendar
import java.util.UUID

/**
 * 笔记的落地存储。一个笔记 = 一个目录：
 *   notes/<id>/meta.json    标题、时间、时长、来源
 *   notes/<id>/lines.jsonl  每句一行，追加写入
 *
 * 追加写入的好处：记录过程中随时断电 / 强杀，已经识别出来的内容都不会丢。
 */
object NoteStore {

    /** 没在录音时截的图，都落到当天的「课堂截图」这篇里。 */
    const val SOURCE_SHOT = "shot"

    private lateinit var root: File

    /** 「手动标记」那几个字。取标题时要跳过这种纯标记行，不然名字会变成「手动标记」。 */
    private var markerText: String = ""

    /**
     * 所有对 lines.jsonl 的「读 → 改 → 写」都串行化。
     * 录音线程在一句一句追加，AI 分析完又要回写某张图的说明，
     * 没有这把锁就可能把对方刚写进去的一行盖掉。
     */
    private val lock = Any()

    fun init(ctx: Context) {
        root = File(ctx.filesDir, "notes")
        markerText = ctx.getString(R.string.star_marker)
        if (!root.exists()) root.mkdirs()
    }

    private fun dir(id: String) = File(root, id)
    private fun metaFile(id: String) = File(dir(id), "meta.json")
    private fun linesFile(id: String) = File(dir(id), "lines.jsonl")

    fun create(source: String): Note {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString().replace("-", "").substring(0, 12)
        val note = Note(
            id = id,
            title = "网课笔记 " + Formats.shortStamp(now),
            createdAt = now,
            updatedAt = now,
            durationMs = 0L,
            source = source
        )
        dir(id).mkdirs()
        saveMeta(note)
        linesFile(id).writeText("")
        return note
    }

    fun saveMeta(note: Note) {
        val j = JSONObject()
        j.put("id", note.id)
        j.put("title", note.title)
        j.put("createdAt", note.createdAt)
        j.put("updatedAt", note.updatedAt)
        j.put("durationMs", note.durationMs)
        j.put("source", note.source)
        j.put("count", note.entries.size)
        j.put("chars", note.charCount)
        j.put("stars", note.starCount)
        j.put("imgs", note.imageCount)
        j.put("preview", note.preview)
        metaFile(note.id).writeText(j.toString())
    }

    fun appendEntry(id: String, e: Entry) {
        synchronized(lock) {
            linesFile(id).appendText(entryJson(e).toString() + "\n")
        }
    }

    fun rewriteEntries(id: String, entries: List<Entry>) {
        synchronized(lock) {
            val sb = StringBuilder()
            for (e in entries) {
                sb.append(entryJson(e).toString()).append('\n')
            }
            linesFile(id).writeText(sb.toString())
        }
    }

    private fun entryJson(e: Entry): JSONObject {
        val j = JSONObject()
        j.put("t", e.atMs)
        j.put("x", e.text)
        j.put("s", e.star)
        if (e.isImage) j.put("img", e.image)
        if (e.caption.isNotEmpty()) j.put("cap", e.caption)
        if (e.analyzed) j.put("an", true)
        return j
    }

    // ------------------------------------------------------------ 截图

    /** 一篇笔记的截图都放在自己的 shots 子目录里，导出和删除都不用单独处理。 */
    fun shotsDir(id: String): File = File(dir(id), "shots")

    /** 笔记目录本身。整理稿里 `![alt](shots/a.jpg)` 这类相对路径，基准就是它。 */
    fun noteDir(id: String): File = dir(id)

    fun shotFile(id: String, rel: String): File = File(dir(id), rel)

    /** 存一张截图，返回相对路径（写进 [Entry.image] 的就是它）。 */
    fun saveShot(id: String, bitmap: android.graphics.Bitmap, stamp: Long = System.currentTimeMillis()): String? {
        val name = "shot-" + stamp + "-" + (100..999).random() + ".jpg"
        val f = File(shotsDir(id), name)
        if (!com.lecture.notes.util.ImageUtil.writeJpeg(f, bitmap)) return null
        return "shots/" + name
    }

    /**
     * 把一张截图追加进笔记，返回更新后的笔记。
     *
     * 只追加一行、不整篇重写：正在录音时 [Recorder] 也在往同一个文件追加，
     * 整篇重写会把它刚写进去的句子盖掉。
     */
    fun appendImage(id: String, atMs: Long, rel: String, caption: String = ""): Note? {
        synchronized(lock) {
            appendEntry(id, Entry(atMs = atMs, text = "", image = rel, caption = caption))
            val note = load(id) ?: return null
            note.updatedAt = System.currentTimeMillis()
            saveMeta(note)
            return note
        }
    }

    /** AI 分析完图片之后回写说明文字。 */
    fun setImageCaption(id: String, atMs: Long, rel: String, caption: String, analyzed: Boolean): Note? {
        synchronized(lock) {
            val note = load(id) ?: return null
            val i = note.entries.indexOfFirst { it.image == rel && it.atMs == atMs }
            if (i < 0) return note
            note.entries[i] = note.entries[i].copy(caption = caption, analyzed = analyzed)
            note.updatedAt = System.currentTimeMillis()
            rewriteEntries(id, note.entries)
            saveMeta(note)
            return note
        }
    }

    fun deleteShot(id: String, rel: String) {
        try {
            shotFile(id, rel).delete()
        } catch (_: Exception) {
        }
    }

    /** 把某张截图整条从笔记里去掉（同时删掉磁盘上的图）。 */
    fun removeImage(id: String, atMs: Long, rel: String): Note? {
        synchronized(lock) {
            val note = load(id) ?: return null
            val before = note.entries.size
            note.entries.removeAll { it.image == rel && it.atMs == atMs }
            if (note.entries.size == before) return note
            note.updatedAt = System.currentTimeMillis()
            rewriteEntries(id, note.entries)
            saveMeta(note)
            deleteShot(id, rel)
            return note
        }
    }

    /** 当天的「课堂截图」笔记，没有就建一篇。 */
    fun todayShots(now: Long = System.currentTimeMillis()): Note {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val hit = listMeta().firstOrNull { it.source == SOURCE_SHOT && it.createdAt >= start }
        if (hit != null) load(hit.id)?.let { return it }
        val note = create(SOURCE_SHOT)
        note.title = "课堂截图 " + Formats.shortStamp(now)
        saveMeta(note)
        return note
    }

    fun rename(id: String, title: String) {
        val j = readMeta(id) ?: return
        j.put("title", title)
        j.put("updatedAt", System.currentTimeMillis())
        metaFile(id).writeText(j.toString())
    }

    /**
     * 默认标题：从这次记录的开头几句里取字，比「网课笔记 11:15」这种看得出讲了什么。
     * 只读最前面几行，凑够字数就停，所以很快。实在没内容才退回时间戳。
     */
    fun suggestTitle(id: String, maxChars: Int = 14): String {
        val sb = StringBuilder()
        try {
            linesFile(id).useLines { seq ->
                for (raw in seq.take(12)) {
                    if (sb.length >= maxChars) break
                    val o = try {
                        JSONObject(raw)
                    } catch (_: Exception) {
                        continue
                    }
                    if (o.optString("img").isNotBlank()) continue
                    val t = o.optString("x").replace(Regex("\\s|\\u3000|\\u00a0"), "")
                    if (t.isEmpty() || t == markerText) continue
                    sb.append(t)
                }
            }
        } catch (_: Exception) {
        }
        val head = sb.toString().trimStart('★', '，', '。', '、', '：', '；', ',', '.', ':', ';')
        if (head.isBlank()) {
            val at = readMeta(id)?.optLong("createdAt", 0L) ?: 0L
            return "网课笔记 " + Formats.shortStamp(if (at > 0L) at else System.currentTimeMillis())
        }
        return if (head.length <= maxChars) head else head.substring(0, maxChars)
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    private fun readMeta(id: String): JSONObject? = try {
        val f = metaFile(id)
        if (f.exists()) JSONObject(f.readText()) else null
    } catch (e: Exception) {
        null
    }

    fun load(id: String): Note? {
        val j = readMeta(id) ?: return null
        val note = Note(
            id = j.optString("id", id),
            title = j.optString("title", "网课笔记"),
            createdAt = j.optLong("createdAt"),
            updatedAt = j.optLong("updatedAt"),
            durationMs = j.optLong("durationMs"),
            source = j.optString("source", "mic")
        )
        val f = linesFile(id)
        if (f.exists()) {
            f.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                try {
                    val o = JSONObject(line)
                    note.entries.add(
                        Entry(
                            atMs = o.optLong("t"),
                            text = o.optString("x"),
                            star = o.optBoolean("s", false),
                            image = o.optString("img").ifBlank { null },
                            caption = o.optString("cap"),
                            analyzed = o.optBoolean("an", false)
                        )
                    )
                } catch (_: Exception) {
                }
            }
        }
        // 截图是事后补进来的，时间戳可能比文件里最后一行还早，所以读的时候统一按时间排一次
        note.entries.sortBy { it.atMs }
        return note
    }

    /** 列表页用的轻量信息，不读全文。 */
    class Meta(
        val id: String,
        val title: String,
        val createdAt: Long,
        val durationMs: Long,
        val source: String,
        val count: Int,
        val chars: Int,
        val stars: Int,
        val preview: String,
        val images: Int = 0,
        /** 这篇是否已经有整理稿。首页卡片上直接标出来，省得每篇都点进去找。 */
        val hasDigest: Boolean = false
    )

    /** 有没有整理稿（只看文件在不在，够快，主线程也能用）。 */
    fun hasDigest(id: String): Boolean {
        val f = digestFile(id)
        return f.exists() && f.length() > 0L
    }

    fun listMeta(): List<Meta> {
        val dirs = root.listFiles() ?: return emptyList()
        val out = ArrayList<Meta>()
        for (d in dirs) {
            if (!d.isDirectory) continue
            val j = readMeta(d.name) ?: continue
            out.add(
                Meta(
                    id = j.optString("id", d.name),
                    title = j.optString("title", "网课笔记"),
                    createdAt = j.optLong("createdAt"),
                    durationMs = j.optLong("durationMs"),
                    source = j.optString("source", "mic"),
                    count = j.optInt("count"),
                    chars = j.optInt("chars"),
                    stars = j.optInt("stars"),
                    preview = j.optString("preview"),
                    images = j.optInt("imgs"),
                    hasDigest = hasDigest(j.optString("id", d.name))
                )
            )
        }
        out.sortByDescending { it.createdAt }
        return out
    }

    /** 纯文本形态，用于复制 / 分享 / 全文编辑。 */
    fun plainText(note: Note, withStar: Boolean = true): String {
        val sb = StringBuilder()
        for (e in note.entries) {
            if (e.isImage) continue
            sb.append('[').append(Formats.mmss(e.atMs)).append("] ")
            if (withStar && e.star) sb.append("★ ")
            sb.append(e.text).append('\n')
        }
        return sb.toString()
    }

    /**
     * 编辑框里保存回来的文字条目，和原来的图片条目合并。
     * 图片不进编辑框，所以保存时按时间戳把它插回原来的位置。
     */
    fun mergeEdit(original: List<Entry>, edited: List<Entry>): List<Entry> {
        val out = ArrayList<Entry>(edited.size + original.size)
        for (e in edited) {
            if (e.isImage) continue
            out.add(e)
        }
        out.addAll(original.filter { it.isImage })
        return out.sortedBy { it.atMs }
    }

    fun markdown(note: Note): String {
        val sb = StringBuilder()
        sb.append("# ").append(note.title).append("\n\n")
        sb.append("> 记录时间：").append(Formats.dateTime(note.createdAt))
            .append("　时长：").append(Formats.hms(note.durationMs))
            .append("　").append(note.entries.size).append(" 句")
            .append("　重点 ").append(note.starCount).append(" 处\n\n")
        sb.append("## 笔记\n\n")
        for (e in note.entries) {
            sb.append("- `[").append(Formats.mmss(e.atMs)).append("]` ")
            if (e.star) sb.append("**★ 重点** ")
            if (e.isImage) {
                sb.append("![图示](").append(e.image).append(")\n")
                appendCaption(sb, e)
            } else {
                sb.append(e.text).append('\n')
            }
        }
        return sb.toString()
    }

    private fun appendCaption(sb: StringBuilder, e: Entry) {
        if (e.caption.isBlank()) return
        for (line in e.caption.trim().split('\n')) {
            sb.append("  ").append(line.trimEnd()).append('\n')
        }
    }

    /**
     * 整理稿末尾的「本课图示」小节。
     *
     * AI 分析出来的说明本身就带 `- ` 列表，这里统一洗成缩进的小要点，
     * 并把每张图的时间戳放回第一条上，排版层就能渲染成时间胶囊 + 大图。
     */
    fun shotsSection(note: Note, heading: String = "本课图示", onlyRels: Set<String>? = null): String {
        val shots = note.entries.filter { it.isImage && (onlyRels == null || it.image in onlyRels) }
        if (shots.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("\n## ").append(heading).append("\n\n")
        for (e in shots) sb.append(shotBlock(e))
        return sb.toString()
    }

    /** 整理稿里的单张截图：图片 + 它的 AI 说明（没跑过分析就留一句占位）。 */
    fun shotBlock(e: Entry): String {
        val sb = StringBuilder()
        sb.append("![课堂截图 ").append(Formats.mmss(e.atMs)).append("](").append(e.image).append(")\n\n")
        val lines = e.caption.trim().split('\n')
            .map { it.trim().removePrefix("- ").removePrefix("* ").trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            sb.append("- `[").append(Formats.mmss(e.atMs)).append("]` 截图（还没有说明）\n\n")
            return sb.toString()
        }
        lines.forEachIndexed { i, l ->
            if (i == 0) {
                sb.append("- `[").append(Formats.mmss(e.atMs)).append("]` ").append(l).append('\n')
            } else {
                sb.append("  - ").append(l).append('\n')
            }
        }
        sb.append('\n')
        return sb.toString()
    }

    /** 把「[01:23] 正文」格式的全文解析回条目，用于编辑后保存。 */
    fun parsePlainText(text: String): List<Entry> {
        val re = Regex("^\\[(\\d{1,3}):(\\d{2})\\]\\s*★?\\s*(.*)$")
        val out = ArrayList<Entry>()
        var lastMs = 0L
        for (raw in text.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val m = re.find(line)
            if (m != null) {
                val ms = (m.groupValues[1].toLongOrNull() ?: 0L) * 60000L +
                        (m.groupValues[2].toLongOrNull() ?: 0L) * 1000L
                lastMs = ms
                out.add(Entry(ms, m.groupValues[3].trim(), line.contains("★")))
            } else {
                if (out.isEmpty()) {
                    out.add(Entry(lastMs, line, line.contains("★")))
                } else {
                    val prev = out.removeAt(out.size - 1)
                    out.add(Entry(prev.atMs, prev.text + " " + line, prev.star))
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------ 知识点整理稿

    private fun digestFile(id: String) = File(dir(id), "digest.md")

    fun saveDigest(id: String, text: String) {
        try {
            digestFile(id).writeText(text, Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    /**
     * 整理稿是不是已经跟不上笔记了。
     *
     * 典型场景：先看了一眼整理稿，接着又截了几张课件图 —— 转写文件比整理稿新，
     * 这时再打开整理稿看到的还是旧的（图当然「没有」）。用到的时间戳留 1 秒余量，
     * 免得文件系统的秒级精度把它误判成「刚更新过」。
     */
    fun digestStale(id: String): Boolean {
        val d = digestFile(id)
        if (!d.exists()) return false
        val lines = linesFile(id)
        return lines.exists() && lines.lastModified() > d.lastModified() + 1000
    }

    fun readDigest(id: String): String? = try {
        val f = digestFile(id)
        if (f.exists() && f.length() > 0L) f.readText(Charsets.UTF_8) else null
    } catch (_: Exception) {
        null
    }

    fun clearDigest(id: String) {
        try {
            digestFile(id).delete()
        } catch (_: Exception) {
        }
    }

    /**
     * 导出 / 分享用的完整版：有整理稿就先放整理稿，再附上原始转写。
     * 没整理过就退化成纯转写。
     */
    fun fullMarkdown(note: Note): String {
        val digest = readDigest(note.id)
        if (digest.isNullOrBlank()) return markdown(note)
        val sb = StringBuilder()
        sb.append(digest.trim()).append("\n\n---\n\n## 原始转写\n\n")
        for (e in note.entries) {
            // 截图已经在整理稿的「本课图示」里出现过了，这里只留文字，免得导出的文档里图重复两遍
            if (e.isImage) continue
            sb.append("- `[").append(Formats.mmss(e.atMs)).append("]` ")
            if (e.star) sb.append("**★ 重点** ")
            sb.append(e.text).append('\n')
        }
        return sb.toString()
    }
    /**
     * 导出 Markdown 到「下载」目录（Android 10+ 走 MediaStore，无需存储权限）。
     * 有截图时把图一起放到同名的子目录里，md 里的相对路径就能正常显示。
     */
    fun exportMarkdown(ctx: Context, note: Note): String {
        val name = fileName(note, "md")
        val folder = exportFolder(note)
        // md 和 shots/ 放进同一个子目录，md 里的相对路径 ![图示](shots/x.jpg) 才能正常显示。
        // MIME 必须是 text/markdown：MediaStore 会按 MIME 补后缀，写 text/plain 会被存成 .md.txt。
        val where = writeDownload(ctx, name, "text/markdown", fullMarkdown(note), folder)
        copyShots(ctx, folder, note)
        return where
    }

    /**
     * 一次导出两份：Markdown（整理稿 + 原始转写，图片放在同级 shots/）和排好版的 HTML（图片内嵌，单文件）。
     *
     * 两份和 `shots/` 都落在**同一个**「下载/<笔记名-时间>/」里 —— 以前 HTML 散在下载根目录、
     * markdown 在子目录里，打开「下载」看到的是一堆文件，谁能想到它们是一套。
     */
    fun exportAll(ctx: Context, note: Note, markdown: String?): Pair<String, String> {
        val mdPath = exportMarkdown(ctx, note)
        val htmlPath = exportHtml(ctx, note, markdown)
        return mdPath to htmlPath
    }

    private fun copyShots(ctx: Context, folder: String, note: Note) {
        val shots = note.entries.mapNotNull { it.image }.distinct()
        if (shots.isEmpty()) return
        for (rel in shots) {
            val f = shotFile(note.id, rel)
            if (!f.exists()) continue
            try {
                val bytes = f.readBytes()
                val target = rel.substringAfterLast('/')
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, target)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/" + folder + "/shots"
                        )
                    }
                    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: continue
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                } else {
                    val d = File(File(ctx.getExternalFilesDir(null), "导出"), "$folder/shots")
                    d.mkdirs()
                    File(d, target).writeBytes(bytes)
                }
            } catch (_: Exception) {
            }
        }
    }

    /** 把 `](shots/xxx.jpg)` 换成内嵌的 base64 图片，导出的 HTML 就是单文件、可打印的。 */
    fun inlineImages(note: Note, md: String): String {
        if (note.imageCount == 0) return md
        var out = md
        for (e in note.entries) {
            val rel = e.image ?: continue
            val token = "($rel)"
            if (!out.contains(token)) continue
            val b64 = com.lecture.notes.util.ImageUtil.fileToBase64(shotFile(note.id, rel)) ?: continue
            out = out.replace(token, "(" + com.lecture.notes.util.ImageUtil.DATA_HEAD + b64 + ")")
        }
        return out
    }

    /**
     * 导出**排好版**的 HTML：自带样式，双击就能在浏览器里看，也能直接打印成 PDF。
     * [markdown] 传当前页面上那一版整理稿，传空就用磁盘上存的。
     */
    fun exportHtml(ctx: Context, note: Note, markdown: String?): String {
        val md = if (!markdown.isNullOrBlank()) markdown else (readDigest(note.id) ?: markdown(note))
        return writeDownload(
            ctx, fileName(note, "html"), "text/html",
            MiniMarkdown.toHtml(inlineImages(note, md), note.title), exportFolder(note)
        )
    }

    /** 一次导出用的文件夹名：`.md`、`.html`、`shots/` 都放进去，打开一个文件夹就是全套。 */
    private fun exportFolder(note: Note): String = fileName(note, "md").removeSuffix(".md")

    /** 排好版的 HTML 正文（分享时一并带上，接收方能保留排版）。 */
    fun digestHtml(note: Note, markdown: String?): String {
        val md = if (!markdown.isNullOrBlank()) markdown else (readDigest(note.id) ?: markdown(note))
        return MiniMarkdown.toHtml(md, note.title)
    }

    private fun fileName(note: Note, ext: String): String {
        val safe = note.title.replace(Regex("[/\\\\:*?\"<>|]"), "_").take(40)
        return (if (safe.isBlank()) "网课笔记" else safe) + "-" + Formats.stamp(note.createdAt) + "." + ext
    }

    private fun writeDownload(
        ctx: Context, fileName: String, mime: String, body: String, subDir: String? = null
    ): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rel = if (subDir.isNullOrBlank()) {
                Environment.DIRECTORY_DOWNLOADS
            } else {
                Environment.DIRECTORY_DOWNLOADS + "/" + subDir
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("系统拒绝创建文件")
            resolver.openOutputStream(uri)?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                ?: throw IOException("无法写入文件")
            return "下载/" + (if (subDir.isNullOrBlank()) "" else subDir + "/") + fileName
        }
        val root = File(ctx.getExternalFilesDir(null), "导出")
        val dir = if (subDir.isNullOrBlank()) root else File(root, subDir)
        dir.mkdirs()
        val f = File(dir, fileName)
        f.writeText(body, Charsets.UTF_8)
        return f.absolutePath
    }
    /** 全文搜索：先匹配标题和开头，再逐行扫文件，速度够快也够准。 */
    fun search(query: String): List<Meta> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return listMeta()
        val hit = ArrayList<Meta>()
        val miss = ArrayList<Meta>()
        for (m in listMeta()) {
            if (m.title.lowercase().contains(q) || m.preview.lowercase().contains(q)) hit.add(m) else miss.add(m)
        }
        for (m in miss) {
            val f = linesFile(m.id)
            if (!f.exists()) continue
            var found = false
            try {
                f.bufferedReader().useLines { seq ->
                    for (line in seq) {
                        if (line.lowercase().contains(q)) {
                            found = true
                            break
                        }
                    }
                }
            } catch (_: Exception) {
            }
            if (found) hit.add(m)
        }
        hit.sortByDescending { it.createdAt }
        return hit
    }
}
