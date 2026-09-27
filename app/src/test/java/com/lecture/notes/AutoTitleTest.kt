package com.lecture.notes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lecture.notes.data.NoteStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 「这个名字还是 App 自动起的吗」这个标记：AI 整理完可以拿这节课的题目换掉自动名，
 * 用户自己敲的名字则一次都不能被动。标记错一次，用户的笔记名就被悄悄改掉了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoTitleTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        NoteStore.init(ctx)
    }

    @Test
    fun freshNoteCountsAsAuto() {
        val n = NoteStore.create("mic")
        assertTrue("刚建的笔记名字是自动起的", n.autoTitle)
        assertTrue("读回来也要还是自动名", NoteStore.load(n.id)!!.autoTitle)
    }

    @Test
    fun userRenameLocksTheName() {
        val n = NoteStore.create("mic")
        NoteStore.rename(n.id, "我自己起的名字")
        assertFalse("用户改过名字之后就不能再被自动改掉", NoteStore.load(n.id)!!.autoTitle)
    }

    @Test
    fun digestRenameKeepsItAutoButOnlyOnce() {
        val n = NoteStore.create("mic")
        NoteStore.rename(n.id, "ARP 协议与 ARP 欺骗攻击", auto = true, ai = true)
        val back = NoteStore.load(n.id)!!
        assertEquals("ARP 协议与 ARP 欺骗攻击", back.title)
        assertTrue("用户没动过，所以还算自动名", back.autoTitle)
        assertTrue("但要记住这次改名是 AI 做的 —— 重跑一次不该再换名字", back.aiTitle)
    }

    /** 保存对话框里没改名字（Recorder 把建议名写回去）：还算自动名，AI 第一次整理可以换掉它。 */
    @Test
    fun saveDialogKeepsAutoWithoutAiFlag() {
        val n = NoteStore.create("mic")
        NoteStore.rename(n.id, "mac地址的过程就是ARP协议", auto = true)
        val back = NoteStore.load(n.id)!!
        assertTrue("保存时没改名，算自动名", back.autoTitle)
        assertFalse("这不是 AI 起的题目，AI 第一次整理还能换", back.aiTitle)
    }

    /** 老笔记没有这个字段：只看名字像不像默认名。 */
    @Test
    fun oldNotesFallBackToTheName() {
        val id = "oldnote00001"
        val d = File(ctx.filesDir, "notes/$id")
        d.mkdirs()
        File(d, "meta.json").writeText("""{"id":"$id","title":"网课笔记 09-27 17:22","createdAt":1,"updatedAt":1}""")
        assertTrue("默认名可以被换掉", NoteStore.load(id)!!.autoTitle)
        File(d, "meta.json").writeText("""{"id":"$id","title":"我整理的 socket 笔记","createdAt":1,"updatedAt":1}""")
        assertFalse("看着像自己起的名字就不能动", NoteStore.load(id)!!.autoTitle)
    }
}