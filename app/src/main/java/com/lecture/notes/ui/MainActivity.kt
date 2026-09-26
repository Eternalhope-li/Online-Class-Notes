package com.lecture.notes.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
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
                openLive()
                toast(getString(R.string.toast_started))
            } else {
                toast(getString(R.string.toast_projection_denied))
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
            adapter.submit(list)
            binding.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun refresh() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { NoteStore.listMeta() }
            if (binding.search.text.isNullOrBlank()) {
                adapter.submit(list)
                binding.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun openNote(meta: NoteStore.Meta) {
        startActivity(Intent(this, DetailActivity::class.java).putExtra(DetailActivity.EXTRA_ID, meta.id))
    }

    private fun noteMenu(meta: NoteStore.Meta) {
        val items = arrayOf(getString(R.string.detail_rename), getString(R.string.detail_delete))
        AlertDialog.Builder(this)
            .setTitle(meta.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> renameDialog(meta)
                    1 -> AlertDialog.Builder(this)
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

    private fun askPermissions() {
        val need = ArrayList<String>(2)
        if (!hasMic()) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            if (!granted) need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
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
        val source = Prefs.audioSource
        if (source == Prefs.SOURCE_INTERNAL && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 悬浮截图已经拿过录屏授权了就直接共用：一个 App 只能有一个 MediaProjection，
            // 再申请一次会把人家正在用的那个掐掉。
            if (ShotGate.isReady()) {
                CaptureService.start(this, source)
                openLive()
                toast(getString(R.string.toast_started))
                return
            }
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
            return
        }
        CaptureService.start(this, source)
        openLive()
        toast(getString(R.string.toast_started))
    }

    private fun openLive() {
        startActivity(Intent(this, LiveActivity::class.java))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
