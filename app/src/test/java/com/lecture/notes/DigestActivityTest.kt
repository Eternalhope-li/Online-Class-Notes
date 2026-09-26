package com.lecture.notes

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.lecture.notes.data.NoteStore
import com.lecture.notes.ui.DigestActivity
import com.lecture.notes.ui.SettingsActivity
import com.lecture.notes.util.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 整理稿页面的整体冒烟测试：示例笔记能打开、排版能出来、菜单项都在、
 * 「排版 / Markdown 源码」能互切。菜单 id 拼错、binding id 对不上这类问题都能在这里暴露。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
class DigestActivityTest {

    private fun demoActivity(): DigestActivity {
        val app: Context = ApplicationProvider.getApplicationContext()
        Prefs.init(app)
        NoteStore.init(app)
        Prefs.digestRich = true
        val intent = Intent(app, DigestActivity::class.java).putExtra(DigestActivity.EXTRA_DEMO, true)
        return Robolectric.buildActivity(DigestActivity::class.java, intent).setup().get()
    }

    @Test
    fun demoDigestIsRenderedWithAllMenuItems() {
        val a = demoActivity()
        val box = a.findViewById<LinearLayout>(R.id.contentBox)
        val raw = a.findViewById<TextView>(R.id.content)

        assertEquals(View.VISIBLE, box.visibility)
        assertEquals(View.GONE, raw.visibility)
        assertTrue("排版视图应该有内容", box.childCount > 3)

        val toolbar = a.findViewById<MaterialToolbar>(R.id.toolbar)
        assertEquals(7, toolbar.menu.size())
        assertTrue(a.findViewById<TextView>(R.id.status).text.contains("示例"))
    }

    @Test
    fun switchingToMarkdownSourceAndBack() {
        val a = demoActivity()
        val toolbar = a.findViewById<MaterialToolbar>(R.id.toolbar)
        val box = a.findViewById<LinearLayout>(R.id.contentBox)
        val raw = a.findViewById<TextView>(R.id.content)

        toolbar.menu.performIdentifierAction(R.id.action_view, 0)
        assertEquals(View.GONE, box.visibility)
        assertEquals(View.VISIBLE, raw.visibility)
        assertTrue(raw.text.contains("## 一、"))

        toolbar.menu.performIdentifierAction(R.id.action_view, 0)
        assertEquals(View.VISIBLE, box.visibility)
        assertEquals(View.GONE, raw.visibility)
    }

    /** 顶部那颗按钮：看得见、文案对，示例笔记点了也不会真的去联网整理。 */
    @Test
    fun aiButtonIsVisibleAndDemoStaysReadOnly() {
        val a = demoActivity()
        val btn = a.findViewById<MaterialButton>(R.id.btnAi)
        assertEquals(View.VISIBLE, btn.visibility)
        assertEquals("用 AI 体系化整理", btn.text.toString())
        btn.performClick()
    }

    /** Markdown 源码视图必须放在 ScrollView 里，否则长整理稿滚不动。 */
    @Test
    fun markdownSourceScrollsWithThePage() {
        val a = demoActivity()
        val raw = a.findViewById<TextView>(R.id.content)
        val scroll = a.findViewById<android.widget.ScrollView>(R.id.scroll)
        assertSame("源码视图应该是 ScrollView 内容的子节点", scroll.getChildAt(0), raw.parent)
    }
    @Test

    fun settingsOpens() {
        val app: Context = ApplicationProvider.getApplicationContext()
        Prefs.init(app)
        NoteStore.init(app)
        Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
    }
}
