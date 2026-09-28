package com.lecture.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.lecture.notes.ui.NoteRenderer
import com.lecture.notes.util.DemoNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 排版渲染的冒烟测试：在 JVM 上真的把 View 树建出来，
 * 保证「打开整理稿就闪退」这种问题在提交前就被抓到，并检查几个关键结构还在不在
 * （时间戳要点、表格单元格、关键词胶囊、章节标题、目录卡片、章节可折叠）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
class NoteRendererTest {

    private fun context(): Context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_LectureNotes)

    private fun collect(v: View, out: MutableList<View>) {
        out.add(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) collect(v.getChildAt(i), out)
        }
    }

    private fun render(md: String, scale: Float = 1f): Pair<LinearLayout, List<View>> {
        val ctx = context()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        NoteRenderer(ctx).apply { this.scale = scale }.render(root, md, null)
        val all = ArrayList<View>()
        collect(root, all)
        return root to all
    }

    private fun texts(views: List<View>): List<String> =
        views.filterIsInstance<TextView>().map { it.text?.toString().orEmpty() }

    /** 大节标题里模型爱写 `00:12-00:31`，反引号不能原样露在标题上。 */
    @Test
    fun sectionTitleSwallowsInlineCodeBackticks() {
        val (_, all) = render("## 一、命令行 · 打开 `00:12-00:31`\n\n- 内容\n")
        val t = texts(all)
        assertTrue("标题里的反引号应该被吃掉：$t", t.none { it.contains('`') })
        assertTrue("标题正文还在：$t", t.any { it.contains("命令行") && it.contains("00:12-00:31") })
    }

    /** 模型把图塞在要点行里时，界面上也得真的画出图来 —— 之前只把它当文字，看起来就是「图示没有」。 */
    @Test
    fun inlineShotInsideBulletRendersImage() {
        val (_, all) = render("## 一、终端\n\n- **图示**：![课堂截图 00:39](shots/x.jpg) [图示] - 窗口显示 Error。\n")
        assertTrue("要点行里的图应该渲染成图片", all.any { it is ImageView })
        val t = texts(all)
        assertTrue("图上的说明要留在正文里：$t", t.any { it.contains("窗口显示 Error") })
        assertTrue("不该剩 Markdown 记号：$t", t.none { it.contains("![") })
    }

    @Test
    fun rendersDemoDigestIntoViews() {
        val (root, all) = render(DemoNote.markdown())
        val t = texts(all)

        assertTrue("应该有内容", root.childCount > 5)
        assertTrue("时间戳要点丢了", t.any { it.contains("[00:09]") })
        assertTrue("章节标题丢了", t.any { it == "二叉树的定义与性质" })
        assertTrue("三级标题的小节号应该单独成一块", t.any { it == "1.1" })
        assertTrue("子知识点正文丢了", t.any { it == "定义" })
        assertTrue("表格单元格丢了", t.any { it == "2^(i-1)" })
        assertTrue("关键词胶囊丢了", t.any { it == "链式存储" })
        assertEquals("表头应该是两格", 2, t.count { it == "术语 / 公式" || it == "含义" })
        assertTrue("引用块正文丢了", t.any { it.contains("每层最多两个分支") })
    }

    @Test
    fun tocSitsOnTop() {
        val (root, all) = render(DemoNote.markdown())
        val first = ArrayList<View>()
        collect(root.getChildAt(0), first)
        assertTrue(
            "目录卡片应该在最前面",
            texts(first).any { it.contains("目录") }
        )
        assertTrue("目录里要有章节名", texts(first).any { it == "四种遍历" })
        assertTrue("不应该画出目录卡片以外的正文", !texts(first).any { it.contains("[00:09]") })
    }

    @Test
    fun sectionHeaderTogglesBody() {
        val (_, all) = render(DemoNote.markdown())
        // 章节头 = 序号徽标 + 标题 + 折叠箭头（三个子 View），目录条目只有两个，先排除掉
        val header = all.filterIsInstance<LinearLayout>().first { row ->
            row.isClickable && row.childCount == 3 &&
                (row.getChildAt(1) as? TextView)?.text?.toString() == "本课知识框架"
        }
        // wrap 里依次是：标题行 → 分隔线 → 正文，正文永远排在最后
        val wrap = header.parent as ViewGroup
        val body = wrap.getChildAt(wrap.childCount - 1)
        assertEquals(View.VISIBLE, body.visibility)
        header.performClick()
        assertEquals(View.GONE, body.visibility)
        header.performClick()
        assertEquals(View.VISIBLE, body.visibility)
    }

    /**
     * 真的走一遍绘制：时间戳胶囊和行内代码是自定义的 ReplacementSpan，
     * 画错（坐标算错 / 下标越界）会在手机上直接闪退，这里让它至少有地方能报出来。
     */
    @Test
    fun drawPassDoesNotCrash() {
        val (root, _) = render(DemoNote.markdown())
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val h = root.measuredHeight.coerceAtLeast(1)
        root.layout(0, 0, 1000, h)
        val canvas = Canvas(Bitmap.createBitmap(1000, h, Bitmap.Config.ARGB_8888))
        root.draw(canvas)
    }

    @Test
    fun fontScaleChangesTextSize() {
        fun titleSize(scale: Float): Float {
            val (_, all) = render("## 一、测试标题\n\n- 要点一\n- 要点二", scale)
            return all.filterIsInstance<TextView>().first { it.text?.toString() == "测试标题" }.textSize
        }
        assertTrue("字号倍率应该起作用", titleSize(1.3f) > titleSize(1f))
    }

    /**
     * 截图要有宽度上限：宽屏（平板竖屏的整栏、横屏的阅读栏）上不加限制的话，
     * 一张图能把整栏占满，一页翻下去全是图。
     */
    @Test
    fun shotsAreCappedOnWideColumns() {
        val ctx = context()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        NoteRenderer(ctx).apply { columnWidthDp = 1200 }
            .render(root, "- 要点\n\n![课堂截图 00:12](shots/x.jpg)\n", null)
        val dense = ctx.resources.displayMetrics.density
        val wide = (1200f * dense).toInt()
        measure(root, wide)
        val all = ArrayList<View>()
        collect(root, all)
        val iv = all.filterIsInstance<ImageView>().first()
        val holder = iv.parent as View
        val cap = (500f * dense).toInt()
        assertTrue("图不该顶满 1200dp 宽的栏：${holder.width}", holder.width < wide)
        assertTrue("图宽要收在 500dp 以内：${holder.width} / $cap", holder.width <= cap + 1)
    }

    /** 横屏（屏幕矮）时页面把上限调得更紧，让图再收一档、正文多露两行。 */
    @Test
    fun shotCapCanBeLoweredForShortScreens() {
        val ctx = context()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        NoteRenderer(ctx).apply {
            columnWidthDp = 1200
            shotMaxDp = 380
        }.render(root, "- 要点\n\n![课堂截图 00:12](shots/x.jpg)\n", null)
        val dense = ctx.resources.displayMetrics.density
        measure(root, (1200f * dense).toInt())
        val all = ArrayList<View>()
        collect(root, all)
        val holder = all.filterIsInstance<ImageView>().first().parent as View
        val cap = (380f * dense).toInt()
        assertTrue("横屏调紧的上限要生效：${holder.width} / $cap", holder.width <= cap + 1)
        assertTrue(
            "横屏的上限得比默认那份更紧：${holder.width}",
            holder.width < (NoteRenderer.SHOT_MAX_DP * dense).toInt()
        )
    }

    /** 栏比上限还窄（手机）时按栏宽来：500dp 的上限不能把图撑出栏外。 */
    @Test
    fun shotsKeepTheColumnWidthOnNarrowScreens() {
        val ctx = context()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        NoteRenderer(ctx).apply { columnWidthDp = 320 }
            .render(root, "- 要点\n\n![课堂截图 00:12](shots/x.jpg)\n", null)
        val narrow = (320f * ctx.resources.displayMetrics.density).toInt()
        measure(root, narrow)
        val all = ArrayList<View>()
        collect(root, all)
        val holder = all.filterIsInstance<ImageView>().first().parent as View
        assertTrue("窄栏里图就按栏宽来：${holder.width}", holder.width <= narrow)
        assertTrue("窄栏里不该被 500dp 的下限撑开：${holder.width}", holder.width > narrow / 2)
    }

    /** 没人告诉过栏宽（老调用方）时不限制，还是铺满整栏 —— 别让新参数变成新的默认行为。 */
    @Test
    fun shotsFillTheColumnWhenWidthIsUnknown() {
        val ctx = context()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        NoteRenderer(ctx).render(root, "- 要点\n\n![课堂截图 00:12](shots/x.jpg)\n", null)
        val wide = (1200f * ctx.resources.displayMetrics.density).toInt()
        measure(root, wide)
        val all = ArrayList<View>()
        collect(root, all)
        val holder = all.filterIsInstance<ImageView>().first().parent as View
        assertEquals("不知道栏宽就照旧铺满", wide, holder.width)
    }

    private fun measure(root: View, width: Int) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        root.layout(0, 0, width, root.measuredHeight.coerceAtLeast(1))
    }

    /**
     * 横屏两栏：目录要整块交到侧栏容器里，不能再压在正文最上面 ——
     * 横过来高度本来就只剩半屏，目录再占一条，正文就没剩多少了。
     */
    @Test
    fun tocMovesToTheSidePaneWhenThereIsOne() {
        val ctx = context()
        val body = StringBuilder()
        for (t in listOf("甲", "乙", "丙")) body.append("## 一、$t\n\n- $t 的内容\n\n")
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val side = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val renderer = NoteRenderer(ctx).apply { tocTitle = "本页目录" }
        renderer.render(root, body.toString(), null, side)

        val sideTexts = ArrayList<View>().also { collect(side, it) }
            .filterIsInstance<TextView>().map { it.text?.toString().orEmpty() }
        val bodyTexts = ArrayList<View>().also { collect(root, it) }
            .filterIsInstance<TextView>().map { it.text?.toString().orEmpty() }
        assertTrue("目录要落在侧栏里：$sideTexts", sideTexts.any { it == "本页目录" })
        assertTrue("正文里不该再有一条目录：$bodyTexts", bodyTexts.none { it == "本页目录" })
    }

    /** 没给侧栏（竖屏）时，目录还是老老实实待在正文最上面。 */
    @Test
    fun tocStaysOnTopWithoutASidePane() {
        val ctx = context()
        val body = StringBuilder()
        for (t in listOf("甲", "乙", "丙")) body.append("## 一、$t\n\n- $t 的内容\n\n")
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        NoteRenderer(ctx).apply { tocTitle = "本页目录" }.render(root, body.toString(), null, null)
        val texts = ArrayList<View>().also { collect(root, it) }
            .filterIsInstance<TextView>().map { it.text?.toString().orEmpty() }
        assertTrue("竖屏目录还在正文里：$texts", texts.any { it == "本页目录" })
    }
}
