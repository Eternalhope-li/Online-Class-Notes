package com.lecture.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.lecture.notes.ui.NoteRenderer
import com.lecture.notes.util.ImageUtil
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 整理稿里带截图时的渲染：View 树要建得出来，绘制也不能崩。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
class ShotRenderTest {

    private fun context(): Context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_LectureNotes)

    private fun collect(v: View, out: MutableList<View>) {
        out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), out)
    }

    @Test
    fun rendersImageBlockAndDraws() {
        val ctx = context()
        val dir = File(ctx.cacheDir, "shots").apply { mkdirs() }
        ImageUtil.writeJpeg(File(dir, "a.jpg"), Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888))

        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        NoteRenderer(ctx).apply { imageDir = dir }.render(
            root,
            "## 本课图示\n\n![课堂截图 00:05](a.jpg)\n\n- `[00:05]` 图上的要点",
            null
        )

        val all = ArrayList<View>()
        collect(root, all)
        assertTrue(all.any { it is ImageView })

        root.measure(
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        root.layout(0, 0, 360, root.measuredHeight.coerceAtLeast(1))
        root.draw(Canvas(Bitmap.createBitmap(360, root.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)))
    }

    @Test
    fun missingImageFileDoesNotCrash() {
        val ctx = context()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        NoteRenderer(ctx).apply { imageDir = File(ctx.cacheDir, "nope") }.render(
            root, "![课堂截图](gone.jpg)", null
        )
        assertTrue(root.childCount > 0)
    }

    /**
     * 整理稿里写的是相对笔记目录的 `shots/a.jpg`。之前界面把 imageDir 设成了 shots 目录，
     * 于是拼出 shots/shots/a.jpg，图片一直静默显示成空白框 —— 这里把两种约定都钉住。
     */
    @Test
    fun imageSrcResolvesAgainstBothConventions() {
        val ctx = context()
        val noteDir = File(ctx.cacheDir, "note-1")
        val shots = File(noteDir, "shots").apply { mkdirs() }
        ImageUtil.writeJpeg(File(shots, "a.jpg"), Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))

        val againstNote = NoteRenderer(ctx).apply { imageDir = noteDir }
        assertNotNull("相对笔记目录的 shots/a.jpg 必须找得到", againstNote.resolveImage("shots/a.jpg"))

        val againstShots = NoteRenderer(ctx).apply { imageDir = shots }
        assertNotNull("imageDir 直接给 shots 目录时，用文件名也要找得到", againstShots.resolveImage("shots/a.jpg"))

        assertNull("不存在的图返回 null 而不是乱指一个文件", againstNote.resolveImage("shots/nope.jpg"))
    }
}
