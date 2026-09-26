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
}
