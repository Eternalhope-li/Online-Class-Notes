package com.lecture.notes

import android.graphics.Bitmap
import com.lecture.notes.util.ImageUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 图片工具：发给视觉模型前必须先把长边压下来，否则免费模型 4K 的上下文装不下。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageUtilTest {

    @Test
    fun fitShrinksLongEdge() {
        val src = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888)
        val out = ImageUtil.fit(src, 1280)
        assertEquals(1280, maxOf(out.width, out.height))
        assertEquals(640, out.height)
    }

    @Test
    fun fitKeepsSmallBitmapUntouched() {
        val src = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        assertSame(src, ImageUtil.fit(src, 1280))
    }

    @Test
    fun dataHeadLooksLikeDataUrl() {
        assertTrue(ImageUtil.DATA_HEAD.endsWith(","))
        assertTrue(ImageUtil.DATA_HEAD.contains("base64"))
        assertEquals(ImageUtil.DATA_HEAD + "AAAA", ImageUtil.dataUrl("AAAA"))
    }

    @Test
    fun visionPayloadFromFileIsDecodable() {
        val dir = File(System.getProperty("java.io.tmpdir"), "lecture-image-test-" + System.nanoTime())
        dir.mkdirs()
        val f = File(dir, "shot.jpg")
        val src = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        assertTrue(ImageUtil.writeJpeg(f, src))
        val b64 = ImageUtil.visionBase64(f)
        assertNotNull(b64)
        assertTrue(b64!!.isNotEmpty())
        assertTrue(ImageUtil.sizeOf(f)!!.first == 1600)
        dir.deleteRecursively()
    }
}
