package com.lecture.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
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
}
