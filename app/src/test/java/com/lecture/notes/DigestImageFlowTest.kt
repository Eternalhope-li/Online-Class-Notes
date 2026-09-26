package com.lecture.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.lecture.notes.data.Entry
import com.lecture.notes.data.NoteStore
import com.lecture.notes.ui.NoteRenderer
import com.lecture.notes.util.NoteDigest
import com.lecture.notes.util.Prefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * 「截图进整理稿」这条链路的端到端回归。
 *
 * 它出过一次很难看的故障：图明明截下来、也存进笔记了，整理稿里却只有一个空白框。
 * 两个原因都在这里钉住：
 *  - 整理稿里写的是相对笔记目录的 `shots/xxx.jpg`，渲染时的基准目录必须是笔记目录，
 *    否则路径拼成 `shots/shots/xxx.jpg`，图片静默变空白；
 *  - 整理稿是「生成那一刻的快照」：先看整理稿、之后才截图，磁盘上那份 md 里根本没有图。
 *    所以要有一面「有新内容」的旗子提醒用户重做（[NoteStore.digestStale]）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
class DigestImageFlowTest {

    private fun collect(v: View, out: MutableList<View>) {
        out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), out)
    }

    private fun app(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun digestFromRealNoteShowsTheShot() {
        val ctx = app()
        Prefs.init(ctx)
        NoteStore.init(ctx)
        val note = NoteStore.create(Prefs.SOURCE_MIC)
        NoteStore.appendEntry(note.id, Entry(1000L, "二叉树是指每个节点最多有两个子节点的树结构。"))
        NoteStore.appendEntry(note.id, Entry(9000L, "中序遍历二叉搜索树可以得到一个有序序列。"))

        val bmp = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.RED)
        val rel = NoteStore.saveShot(note.id, bmp)!!
        NoteStore.appendImage(note.id, 9000L, rel, "- 课件：二叉树的定义")

        val loaded = NoteStore.load(note.id)!!
        val md = NoteDigest.build(loaded).first
        assertTrue("整理稿里应该有这张图：$md", md.contains("![课堂截图"))
        assertTrue("图片路径要指向笔记目录下的 shots/：$md", md.contains("]($rel)"))

        val themed = ContextThemeWrapper(ctx, R.style.Theme_LectureNotes)
        val root = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL }
        NoteRenderer(themed).apply { imageDir = NoteStore.noteDir(loaded.id) }
            .render(root, md, null)

        val all = ArrayList<View>()
        collect(root, all)
        val ivs = all.filterIsInstance<ImageView>()
        assertTrue("整理稿里应该有一个图片位", ivs.isNotEmpty())

        var decoded = false
        val deadline = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (ivs.any { it.drawable != null }) {
                decoded = true
                break
            }
            Thread.sleep(50)
        }
        assertTrue("截图应该真的解码出来，而不是一个空白框", decoded)
    }

    @Test
    fun digestGoesStaleAfterANewShot() {
        val ctx = app()
        Prefs.init(ctx)
        NoteStore.init(ctx)
        val note = NoteStore.create(Prefs.SOURCE_MIC)
        NoteStore.appendEntry(note.id, Entry(1000L, "第一句话"))
        NoteStore.saveDigest(note.id, "## 一、开场\n\n- `[00:01]` 第一句话\n")

        assertFalse("刚写完的整理稿不该是过期的", NoteStore.digestStale(note.id))

        // 把整理稿的时间按回 5 秒前，模拟「先看了整理稿，之后才截图」
        val digest = File(NoteStore.noteDir(note.id), "digest.md")
        digest.setLastModified(System.currentTimeMillis() - 5000)
        val bmp = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        val rel = NoteStore.saveShot(note.id, bmp)!!
        NoteStore.appendImage(note.id, 9000L, rel, "- 课件")

        assertTrue("整理稿之后又截了图，应该标记成有新内容", NoteStore.digestStale(note.id))
    }
}
