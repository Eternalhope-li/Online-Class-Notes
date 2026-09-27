package com.lecture.notes.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.lecture.notes.R
import com.lecture.notes.core.Recorder
import com.lecture.notes.core.ShotGate
import com.lecture.notes.core.ShotService
import com.lecture.notes.databinding.ActivitySettingsBinding
import com.lecture.notes.net.LlmDigest
import com.lecture.notes.util.ImageUtil
import com.lecture.notes.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    /** 程序改开关状态时不要触发监听器，否则会绕回开启流程。 */
    private var syncingShare = false

    /**
     * 每个输入框只管自己那一项。
     *
     * 原来是一个 TextWatcher 被六个框共用、每次改动把六项一起写回去 —— 那样
     * 系统自动填充、或用户在某一格上全选删除，都会顺手把别的配置一起冲掉。
     */
    private fun watch(view: TextView, save: (String) -> Unit) {
        view.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                save(s?.toString().orEmpty())
                syncStates()
            }
        })
    }

    /** 用户同意了录屏（只用来截图）。 */
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                ShotService.start(this, result.resultCode, data)
                Prefs.shotFloat = true
                syncShotSwitch()
                toast(getString(R.string.settings_shot_on))
            } else {
                Prefs.shotFloat = false
                syncShotSwitch()
                toast(getString(R.string.settings_shot_denied))
            }
        }

    /** 去系统设置页开「显示在其他应用上层」。 */
    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) askProjection() else {
                Prefs.shotFloat = false
                syncShotSwitch()
                toast(getString(R.string.settings_shot_need_float))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.radioMic.isChecked = Prefs.audioSource == Prefs.SOURCE_MIC
        binding.radioInternal.isChecked = Prefs.audioSource == Prefs.SOURCE_INTERNAL
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            binding.radioInternal.isEnabled = false
        }
        binding.sourceGroup.setOnCheckedChangeListener { _, checked ->
            val source = if (checked == R.id.radioInternal) Prefs.SOURCE_INTERNAL else Prefs.SOURCE_MIC
            if (source != Prefs.audioSource) {
                Prefs.audioSource = source
                if (Recorder.state.value.active) {
                    toast("下次开始记录时生效")
                }
            }
        }

        binding.threads.max = 3
        binding.threads.progress = Prefs.numThreads - 1
        binding.threadsValue.text = getString(R.string.settings_threads_value, Prefs.numThreads)
        binding.threads.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.threadsValue.text = getString(R.string.settings_threads_value, progress + 1)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}

            override fun onStopTrackingTouch(bar: SeekBar?) {
                val v = (bar?.progress ?: 0) + 1
                if (v != Prefs.numThreads) {
                    Prefs.numThreads = v
                    Recorder.reloadEngine()
                    toast("已切换到 $v 线程，模型重新加载中")
                }
            }
        })

        binding.highlight.isChecked = Prefs.autoHighlight
        binding.highlight.setOnCheckedChangeListener { _, v -> Prefs.autoHighlight = v }

        binding.keepScreen.isChecked = Prefs.keepScreenOn
        binding.keepScreen.setOnCheckedChangeListener { _, v -> Prefs.keepScreenOn = v }

        binding.llmBase.setText(Prefs.llmBase)
        binding.llmKey.setText(Prefs.llmKey)
        binding.llmModel.setText(Prefs.llmModel)
        watch(binding.llmBase) { Prefs.llmBase = it }
        watch(binding.llmKey) { Prefs.llmKey = it }
        watch(binding.llmModel) { Prefs.llmModel = it }
        binding.btnTestLlm.setOnClickListener { testLlm() }
        binding.btnPreview.setOnClickListener {
            startActivity(
                Intent(this, DigestActivity::class.java).putExtra(DigestActivity.EXTRA_DEMO, true)
            )
        }

        binding.chipZhipu.setOnClickListener {
            applyPreset("https://open.bigmodel.cn/api/paas/v4", "glm-4-flash")
        }
        binding.chipDeepseek.setOnClickListener {
            applyPreset("https://api.deepseek.com/v1", "deepseek-chat")
        }
        binding.chipKimi.setOnClickListener {
            applyPreset("https://api.moonshot.cn/v1", "moonshot-v1-8k")
        }
        binding.chipQwen.setOnClickListener {
            applyPreset("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus")
        }

        // ---------------------------------------------------------- 悬浮截图

        binding.shotSwitch.isChecked = Prefs.shotFloat
        binding.shotSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingShare) return@setOnCheckedChangeListener
            if (checked) enableShot() else disableShot()
        }

        binding.autoShot.isChecked = Prefs.autoShot
        binding.autoShot.setOnCheckedChangeListener { _, v -> Prefs.autoShot = v }

        binding.shotAuto.isChecked = Prefs.shotAutoAnalyze
        binding.shotAuto.setOnCheckedChangeListener { _, v -> Prefs.shotAutoAnalyze = v }

        binding.shotToRecording.isChecked = Prefs.shotToRecording
        binding.shotToRecording.setOnCheckedChangeListener { _, v -> Prefs.shotToRecording = v }

        // app:simpleItems 在个别机型上不顶用（箭头点开是空列表），这里自己挂适配器
        binding.visionModel.setAdapter(
            VisionModelAdapter(this, resources.getStringArray(R.array.vision_model_presets))
        )
        binding.visionModel.setText(Prefs.visionModel)
        binding.visionBase.setText(Prefs.visionBase)
        binding.visionKey.setText(Prefs.visionKey)
        watch(binding.visionModel) { Prefs.visionModel = it }
        watch(binding.visionBase) { Prefs.visionBase = it }
        watch(binding.visionKey) { Prefs.visionKey = it }
        binding.btnTestVision.setOnClickListener { testVision() }
        binding.visionHelp.setOnClickListener { showVisionHelp() }
        // 这两行状态字本身就是「怎么配」的入口：没配好时点一下，直接跳到该填的那个输入框
        binding.visionState.setOnClickListener { if (!LlmDigest.visionReady()) jumpToKey() }
        binding.llmState.setOnClickListener { if (!LlmDigest.isReady()) jumpToKey() }
        binding.aboutVersion.text = getString(R.string.settings_about_version, versionName())
        syncStates()
    }

    /** 版本号直接从包里读，省得和 build.gradle 里的数字对不上。 */
    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty().ifEmpty { "?" }
    } catch (t: Throwable) {
        "?"
    }

    /**
     * 两行状态字一起刷新：「在线整理」和「看图」这两条链路各自通没通，一眼就能看见。
     *
     * 看图默认复用整理那套配置，所以这里要区分「复用」和「单独配」，
     * 免得用户以为还得再填一遍。
     */
    private fun syncStates() {
        val llmReady = LlmDigest.isReady()
        paintState(
            binding.llmState,
            llmReady,
            if (llmReady) {
                getString(
                    R.string.settings_llm_state_ok,
                    Prefs.llmModel.trim().ifEmpty { "deepseek-chat" },
                    host(Prefs.llmBase)
                )
            } else {
                getString(R.string.settings_llm_state_none)
            }
        )

        val ready = LlmDigest.visionReady()
        val own = Prefs.visionBase.isNotBlank() || Prefs.visionKey.isNotBlank()
        paintState(
            binding.visionState,
            ready,
            getString(
                when {
                    !ready -> R.string.settings_vision_state_none
                    own -> R.string.settings_vision_state_own
                    else -> R.string.settings_vision_state_shared
                }
            )
        )
    }

    /** 状态行的统一画法：通了就是淡一点的正常色；没通就是红字，而且点得动。 */
    private fun paintState(view: TextView, ready: Boolean, text: String) {
        view.text = text
        view.alpha = if (ready) 0.75f else 1f
        view.setTextColor(
            if (ready) MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant)
            else MaterialColors.getColor(view, com.google.android.material.R.attr.colorError)
        )
        view.isClickable = !ready
    }

    /** 「api.deepseek.com」这种短名，用来在状态行里报一下现在接的是哪一家。 */
    private fun host(url: String): String = url.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .ifEmpty { "未填接口地址" }

    /** 跳到「在线整理」的 API Key 输入框：状态行点一下就到这里，不用自己翻。 */
    private fun jumpToKey() {
        binding.scroll.post { binding.scroll.smoothScrollTo(0, offsetInScroll(binding.llmKeyBox)) }
        binding.llmKey.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(
            binding.llmKey, InputMethodManager.SHOW_IMPLICIT
        )
    }

    /**
     * 视图在滚动内容里的纵向位置。
     *
     * 不能直接用 view.top —— 分了卡片之后，每块内容的 top 是相对自己那层容器的，
     * 拿它当滚动值不是滚过头就是没滚到，所以要从自己一路累加到 ScrollView 的直接子 View。
     */
    private fun offsetInScroll(v: View): Int {
        val content = binding.scroll.getChildAt(0)
        var y = 0
        var cur: View? = v
        while (cur != null && cur !== content) {
            y += cur.top
            cur = cur.parent as? View
        }
        return (y - 24).coerceAtLeast(0)
    }

    /** 「测试看图」：发一张写着 42 的小图，读对了就说明这条路真的通了。 */
    private fun testVision() {
        if (!LlmDigest.visionReady()) {
            toast(getString(R.string.settings_vision_need_key))
            jumpToKey()
            return
        }
        binding.btnTestVision.isEnabled = false
        toast(getString(R.string.settings_vision_testing))
        lifecycleScope.launch {
            val jpeg = withContext(Dispatchers.IO) { ImageUtil.testImageJpeg() }
            val msg = try {
                val reply = LlmDigest.pingVision(jpeg)
                if (reply.contains("42")) getString(R.string.settings_vision_ok, reply)
                else getString(R.string.settings_vision_soft, reply)
            } catch (t: Throwable) {
                visionFailText(t.message ?: t.javaClass.simpleName)
            }
            binding.btnTestVision.isEnabled = true
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.settings_vision_test)
                .setMessage(msg)
                .setPositiveButton(R.string.common_ok, null)
                .show()
        }
    }

    /**
     * 失败提示按原因分开：429 是「模型这会儿太挤」，401 是 Key 不对，网络问题就是没连上。
     *
     * 一律甩一句「常见原因：没有视觉模型 / 模型名写错 / Key 没开通」的话，
     * 用户照着改半天也改不对 —— 报错里其实已经写清是哪一种了。
     */
    private fun visionFailText(msg: String): String = when {
        msg.contains("429") -> getString(R.string.settings_vision_busy)
        msg.contains("401") || msg.contains("403") -> getString(R.string.settings_vision_auth, msg)
        msg.startsWith("网络不通") || msg.contains("等待超时") -> getString(R.string.settings_vision_net, msg)
        else -> getString(R.string.settings_vision_fail, msg)
    }

    /** 配置帮助：一段人话 + 一条直达智谱 Key 页面的链接。 */
    private fun showVisionHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_vision_help_title)
            .setMessage(R.string.settings_vision_help_body)
            .setNeutralButton(R.string.settings_vision_help_open) { _, _ ->
                openUrl("https://open.bigmodel.cn/usercenter/apikeys")
            }
            .setPositiveButton(R.string.common_ok, null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            toast(t.message ?: "打不开浏览器")
        }
    }

    override fun onResume() {
        super.onResume()
        // 服务可能被系统或者通知栏关掉了，回来对一下状态
        syncShotSwitch()
    }

    private fun syncShotSwitch() {
        syncingShare = true
        binding.shotSwitch.isChecked = Prefs.shotFloat
        syncingShare = false
    }

    private fun enableShot() {
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.settings_shot_need_float))
            overlayLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        askProjection()
    }

    private fun askProjection() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Prefs.shotFloat = false
            syncShotSwitch()
            toast(getString(R.string.settings_shot_denied))
            return
        }
        // 录音（内录）已经拿过录屏授权了就直接共用：系统只允许一个 MediaProjection，
        // 再申请一次会把正在录音的那次掐断。
        if (ShotGate.isReady()) {
            ShotService.start(this)
            Prefs.shotFloat = true
            syncShotSwitch()
            toast(getString(R.string.settings_shot_on))
            return
        }
        toast(getString(R.string.toast_projection_scope))
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun disableShot() {
        ShotService.stop(this)
        Prefs.shotFloat = false
        toast(getString(R.string.settings_shot_off))
    }



    private fun applyPreset(base: String, model: String) {
        binding.llmBase.setText(base)
        binding.llmModel.setText(model)
        binding.llmPresets.clearCheck()
        toast(getString(R.string.preset_filled, model))
    }

    private fun testLlm() {
        if (!LlmDigest.isReady()) {
            toast(getString(R.string.digest_ai_need_key))
            return
        }
        binding.btnTestLlm.isEnabled = false
        toast(getString(R.string.settings_llm_testing))
        lifecycleScope.launch {
            val msg = try {
                getString(R.string.settings_llm_ok, LlmDigest.ping())
            } catch (t: Throwable) {
                getString(R.string.settings_llm_fail, t.message ?: t.javaClass.simpleName)
            }
            binding.btnTestLlm.isEnabled = true
            AlertDialog.Builder(this@SettingsActivity)
                .setMessage(msg)
                .setPositiveButton(R.string.common_ok, null)
                .show()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /**
     * 「模型名」的下拉：不管框里写着什么，列出来的永远是全部常用模型。
     *
     * 用默认 ArrayAdapter 的话它会拿框里的文字去过滤 —— 框里本来就写着当前模型，
     * 过滤完只剩它自己一条，点开箭头等于什么也没看到。这个下拉的意义就是让你看到还能换哪些，
     * 所以这里把过滤这一步直接短路掉。
     */
    private class VisionModelAdapter(ctx: Context, items: Array<String>) :
        ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_1, items) {

        private val all: List<String> = items.toList()

        private val passthrough = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults =
                FilterResults().apply {
                    values = all
                    count = all.size
                }

            /**
             * 什么都不改 —— 适配器里本来就一直是那 4 个。
             *
             * 这里千万不能 clear()/addAll()：ArrayAdapter 拿数组构造时底层是定长列表，
             * clear() 会直接抛 UnsupportedOperationException，一进设置页就崩。
             */
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) = Unit
        }

        override fun getFilter(): Filter = passthrough
    }
}
