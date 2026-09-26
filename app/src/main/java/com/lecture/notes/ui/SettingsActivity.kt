package com.lecture.notes.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lecture.notes.R
import com.lecture.notes.core.Recorder
import com.lecture.notes.core.ShotGate
import com.lecture.notes.core.ShotService
import com.lecture.notes.databinding.ActivitySettingsBinding
import com.lecture.notes.net.LlmDigest
import com.lecture.notes.util.Prefs
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    /** 程序改开关状态时不要触发监听器，否则会绕回开启流程。 */
    private var syncingShare = false

    private val llmWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            Prefs.llmBase = binding.llmBase.text?.toString().orEmpty()
            Prefs.llmKey = binding.llmKey.text?.toString().orEmpty()
            Prefs.llmModel = binding.llmModel.text?.toString().orEmpty()
            Prefs.visionModel = binding.visionModel.text?.toString().orEmpty()
        }
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
        binding.llmBase.addTextChangedListener(llmWatcher)
        binding.llmKey.addTextChangedListener(llmWatcher)
        binding.llmModel.addTextChangedListener(llmWatcher)
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

        binding.visionModel.setText(Prefs.visionModel)
        binding.visionModel.addTextChangedListener(llmWatcher)
        binding.chipVisionFlash.setOnClickListener { applyVision("glm-4v-flash") }
        binding.chipVisionFlashNew.setOnClickListener { applyVision("glm-4.6v-flash") }
        binding.chipVisionQwen.setOnClickListener { applyVision("qwen-vl-max") }
        binding.chipVisionMoonshot.setOnClickListener { applyVision("moonshot-v1-8k-vision-preview") }
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
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun disableShot() {
        ShotService.stop(this)
        Prefs.shotFloat = false
        toast(getString(R.string.settings_shot_off))
    }

    private fun applyVision(model: String) {
        binding.visionModel.setText(model)
        binding.visionPresets.clearCheck()
        toast(getString(R.string.preset_filled, model))
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
}
