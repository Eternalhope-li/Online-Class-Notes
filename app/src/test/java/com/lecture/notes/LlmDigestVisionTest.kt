package com.lecture.notes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lecture.notes.net.LlmDigest
import com.lecture.notes.util.ImageUtil
import com.lecture.notes.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 视觉请求的请求体：图片必须走 content 数组（OpenAI 兼容的多模态写法），文字请求保持字符串。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LlmDigestVisionTest {

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        Prefs.init(ctx)
        Prefs.llmModel = "glm-4-flash"
        Prefs.visionModel = "glm-4v-flash"
    }

    @Test
    fun visionBodyUsesContentArray() {
        val image = ImageUtil.dataUrl("AAAA")
        val j = JSONObject(LlmDigest.debugBody("sys", "看图", image))
        assertEquals("glm-4v-flash", j.getString("model"))

        val messages = j.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        val user = messages.getJSONObject(1)
        assertEquals("user", user.getString("role"))
        val parts = user.get("content") as JSONArray
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("看图", parts.getJSONObject(0).getString("text"))
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        assertEquals(image, parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    @Test
    fun textBodyStaysPlainString() {
        val j = JSONObject(LlmDigest.debugBody("sys", "整理一下", null))
        assertEquals("glm-4-flash", j.getString("model"))
        assertTrue(j.getJSONArray("messages").getJSONObject(1).get("content") is String)
    }

    @Test
    fun visionOutputLimitFitsSmallContext() {
        val j = JSONObject(LlmDigest.debugBody("sys", "看图", ImageUtil.dataUrl("AAAA")))
        assertTrue(j.getInt("max_tokens") <= 1500)
    }

    // ------------------------------------------------------ 看图这一套配置怎么解析

    @Test
    fun visionGoesToItsOwnBase() {
        Prefs.llmBase = "https://api.deepseek.com/v1"
        Prefs.visionBase = "https://open.bigmodel.cn/api/paas/v4"
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            LlmDigest.debugEndpoints(true).first()
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            LlmDigest.debugEndpoints(false).first()
        )
    }

    @Test
    fun visionFallsBackToLlmBaseWhenBlank() {
        Prefs.llmBase = "https://api.deepseek.com/v1"
        Prefs.visionBase = ""
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            LlmDigest.debugEndpoints(true).first()
        )
    }

    /** 只看图那把 Key 也得算配好了 —— 否则「整理用 DeepSeek、看图用智谱」这种组合会被挡在门外。 */
    @Test
    fun visionReadyAcceptsEitherKey() {
        Prefs.llmKey = "text-key"
        Prefs.visionKey = ""
        assertTrue(LlmDigest.visionReady())

        Prefs.llmKey = ""
        Prefs.visionKey = "vision-key"
        assertTrue(LlmDigest.visionReady())

        Prefs.llmKey = ""
        Prefs.visionKey = ""
        assertFalse(LlmDigest.visionReady())
    }
}
