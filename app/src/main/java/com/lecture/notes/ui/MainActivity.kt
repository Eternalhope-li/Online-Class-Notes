package com.lecture.notes.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lecture.notes.R
import com.lecture.notes.core.CaptureService
import com.lecture.notes.core.Recorder
import com.lecture.notes.core.ShotGate
import com.lecture.notes.core.ShotService
import com.lecture.notes.data.NoteStore
import com.lecture.notes.databinding.ActivityMainBinding
import com.lecture.notes.util.Formats
import com.lecture.notes.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: NotesAdapter
    private var searchJob: Job? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.RECORD_AUDIO] == false) {
                toast(getString(R.string.perm_denied))
            }
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                CaptureService.start(this, Prefs.SOURCE_INTERNAL, result.resultCode, result.data)
                attachShotBall()
                openLive()
                toast(getString(R.string.toast_started))
            } else {
                toast(getString(R.string.toast_projection_denied))
            }
        }

    /**
     * 纯麦克风录音开始前的截图授权。
     *
     * 拿到就顺手把悬浮圆钮挂出来；用户点了拒绝也照样开始录音 —— 截图只是锦上添花，
     * 不能因为它把记录拦下来。
     */
    private val shotLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            CaptureService.start(this, Prefs.audioSource)
            openLive()
            toast(getString(R.string.toast_started))
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                ShotService.start(this, result.resultCode, data)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings, R.id.action_shot -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }

                else -> false
            }
        }

        adapter = NotesAdapter(onClick = { openNote(it) }, onLongClick = { noteMenu(it) })
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.search.doOnTextChanged { text, _, _, _ -> onQueryChanged(text?.toString().orEmpty()) }
        binding.fab.setOnClickListener { startRecording() }
        binding.recordingBar.setOnClickListener { openLive() }

        askPermissions()

        lifecycleScope.launch {
            Recorder.state.collect { st ->
                binding.recordingBar.visibility = if (st.active) View.VISIBLE else View.GONE
                val fabText = if (st.active) {
                    val t = Formats.hms(st.elapsedMs)
                    binding.recordingBar.text = getString(R.string.main_recording) + "  " + t
                    getString(R.string.main_fab_recording, t)
                } else {
                    getString(R.string.main_start)
                }
                // 状态每秒都在刷，文案没变就别动它，省得 FAB 一直重新布局
                if (binding.fab.text.toString() != fabText) binding.fab.text = fabText
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ------------------------------------------------------------------ 列表

    private fun onQueryChanged(q: String) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            delay(200)
            val list = withContext(Dispatchers.IO) {
                if (q.isBlank()) NoteStore.listMeta() else NoteStore.search(q)
            }
            showList(list, q)
        }
    }

    /**
     * 列表和它上面那行统计一起更新。
     *
     * 首页本来就是「我的笔记」列表（搜索框下面就是），这里补一行「共 N 篇 / 找到 N 篇」，
     * 让「保存下来的笔记都在哪」一目了然 —— 之前只有一列卡片，没有任何计数。
     */
    private fun showList(list: List<NoteStore.Meta>, query: String) {
        adapter.submit(list)
        binding.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.notesCount.text = getString(
            if (query.isBlank()) R.string.main_notes_count else R.string.main_notes_hit, list.size
        )
    }

    private fun refresh() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { NoteStore.listMeta() }
            val q = binding.search.text?.toString().orEmpty()
            if (q.isBlank()) showList(list, q)
        }
    }

    private fun openNote(meta: NoteStore.Meta) {
        startActivity(Intent(this, DetailActivity::class.java).putExtra(DetailActivity.EXTRA_ID, meta.id))
    }

    private fun noteMenu(meta: NoteStore.Meta) {
        val items = arrayOf(
            getString(R.string.detail_digest_view),
            getString(R.string.detail_rename),
            getString(R.string.detail_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(meta.title)
            .setItems(items) { _, which ->
                when (which) {
                    // 长按就能直接看整理稿：整理稿才是用户真正想读的东西，别逼人先进详情页翻菜单
                    0 -> startActivity(
                        Intent(this, DigestActivity::class.java).putExtra(DetailActivity.EXTRA_ID, meta.id)
                    )
                    1 -> renameDialog(meta)
                    2 -> AlertDialog.Builder(this)
                        .setMessage(R.string.detail_delete_msg)
                        .setPositiveButton(R.string.common_ok) { _, _ ->
                            NoteStore.delete(meta.id)
                            refresh()
                            toast(getString(R.string.toast_deleted))
                        }
                        .setNegativeButton(R.string.common_cancel, null)
                        .show()
                }
            }
            .show()
    }

    private fun renameDialog(meta: NoteStore.Meta) {
        val input = EditText(this).apply {
            setText(meta.title)
            setSelection(text.length)
        }
        val density = resources.displayMetrics.density
        val pad = (18 * density).toInt()
        val container = FrameLayout(this)
        container.addView(
            input,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(pad, pad / 2, pad, 0) }
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.detail_rename)
            .setView(container)
            .setPositiveButton(R.string.common_ok) { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    NoteStore.rename(meta.id, t)
                    refresh()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ 开录

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    /** Android 13 起通知要单独授权，没有它「AI 整理完通知你」那条路就是断的。 */
    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val need = ArrayList<String>(2)
        if (!hasMic()) need.add(Manifest.permission.RECORD_AUDIO)
        if (needsNotificationPermission()) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) permissionLauncher.launch(need.toTypedArray())
    }

    private fun startRecording() {
        if (Recorder.state.value.active) {
            openLive()
            return
        }
        if (!hasMic()) {
            toast(getString(R.string.perm_need_mic))
            askPermissions()
            return
        }
        // 麦克风早就给过了、通知权限却还没要过：以前只在「缺麦克风」那一支里顺带申请，
        // 于是升级上来的用户永远等不到「整理好了」那条通知。这里补上。
        if (needsNotificationPermission()) askPermissions()
        val source = Prefs.audioSource
        if (source == Prefs.SOURCE_INTERNAL && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 悬浮截图已经拿过录屏授权了就直接共用：一个 App 只能有一个 MediaProjection，
            // 再申请一次会把人家正在用的那个掐掉。
            if (ShotGate.isReady()) {
                CaptureService.start(this, source)
                attachShotBall()
                openLive()
                toast(getString(R.string.toast_started))
                return
            }
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
            return
        }
        // 纯麦克风录音本身不需要录屏授权，但截屏必须要。既然「开始记录就该有截图按钮」，
        // 就把这一次授权并进「开始记录」这一步：用户拒绝也不影响录音，只是少一个圆钮。
        if (Prefs.autoShot && !ShotGate.isReady() && Settings.canDrawOverlays(this) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            shotLauncher.launch(mgr.createScreenCaptureIntent())
            return
        }
        CaptureService.start(this, source)
        openLive()
        toast(getString(R.string.toast_started))
    }

    private fun openLive() {
        startActivity(Intent(this, LiveActivity::class.java))
    }

    /**
     * 开始记录之后顺手把悬浮截图圆钮挂出来。
     *
     * 用户要的是「开了记录就有截图按钮」，而不是再去设置里单独开一次。录屏授权本来就
     * 只会在开启记录时申请一次（内录要用），截图直接用同一个，所以这里不会再弹授权框。
     * 用的是纯麦克风、或者还没给悬浮窗权限，就安静跳过 —— 记录页里那颗「课件截图」
     * 按钮照样能截。
     */
    private fun attachShotBall() {
        if (!Prefs.autoShot) return
        if (!Settings.canDrawOverlays(this)) return
        lifecycleScope.launch {
            // 内录的 MediaProjection 是服务起来之后才登记的，等它登记好再挂圆钮
            var waited = 0
            while (!ShotGate.isReady() && waited < 2000) {
                delay(100)
                waited += 100
            }
            if (ShotGate.isReady()) ShotService.start(this@MainActivity, bySession = true)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
