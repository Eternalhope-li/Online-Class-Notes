package com.lecture.notes.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.lecture.notes.R
import com.lecture.notes.core.CaptureService
import com.lecture.notes.core.Recorder
import com.lecture.notes.core.ShotGate
import com.lecture.notes.core.ShotService
import com.lecture.notes.data.NoteStore
import com.lecture.notes.core.DigestJob
import com.lecture.notes.net.LlmDigest
import com.lecture.notes.databinding.ActivityMainBinding
import com.lecture.notes.util.Formats
import com.lecture.notes.util.OverlayPerm
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

    /** 多选：长按任意一篇进来，顶栏换成「已选 N 篇 / 全选 / 导出 / 删除」。 */
    private var selecting = false
    private var exporting = false

    /** 列表筛选「只看未整理」：整理完一批之后，剩下的那几篇一眼看得见。 */
    private var filterUndigested = false

    /** 当前页签：「记录」放录音和转写，「笔记」只放整理好的整理稿。 */
    private var tab = TAB_RECORDS

    /** 刚切过页签：列表得回到顶上，别让上一页的滚动位置带到下一页。 */
    private var scrollTopOnNextShow = false

    /**
     * 到这个时刻为止，列表上的点击一律不算。
     *
     * 从右上角菜单点「多选」时，手指抬起的那一下会漏给底下的列表 —— 顶栏一换、列表往上
     * 顶了一格，正好把某一篇勾上。300ms 之后就是用户自己的点击了。
     */
    private var ignoreClicksUntil = 0L

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
        tab = savedInstanceState?.getInt(KEY_TAB) ?: TAB_RECORDS

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings, R.id.action_shot -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }

                R.id.action_select -> {
                    enterSelect(null)
                    true
                }

                R.id.action_trash -> {
                    openTrash()
                    true
                }

                else -> false
            }
        }

        adapter = NotesAdapter(
            onClick = {
                when {
                    !selecting -> openNote(it)
                    SystemClock.uptimeMillis() >= ignoreClicksUntil -> toggleSelect(it)
                }
            },
            onLongClick = { enterSelect(it.id) }
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.search.doOnTextChanged { text, _, _, _ -> onQueryChanged(text?.toString().orEmpty()) }
        binding.fab.setOnClickListener { startRecording() }
        filterUndigested = Prefs.onlyUndigested
        binding.filter.setOnClickListener {
            filterUndigested = !filterUndigested
            Prefs.onlyUndigested = filterUndigested
            syncFilter()
            onQueryChanged(binding.search.text?.toString().orEmpty())
        }
        syncFilter()
        setupTabs()
binding.recordingBar.setOnClickListener { openLive() }
        // 多选：顶栏那排按钮 + 返回键。返回键只在多选里拦一下，别的时候照常退出页面。
        binding.selectClose.setOnClickListener { exitSelect() }
        binding.selectAll.setOnClickListener { toggleSelectAll() }
        binding.selectDigest.setOnClickListener { digestSelected() }
        binding.selectExport.setOnClickListener { exportSelected() }
        binding.selectDelete.setOnClickListener { deleteSelected() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selecting) {
                    exitSelect()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // 后台整理完一篇（或一批）之后，首页的「已整理」标记得跟上
        lifecycleScope.launch {
            var wasRunning = false
            DigestJob.state.collect { st ->
                if (wasRunning && !st.running) refresh()
                wasRunning = st.running
            }
        }

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
        // 刚从系统设置里给完悬浮窗权限回来：把挂在半路的事（挂圆钮）接着做完
        OverlayPerm.resume(this)
        // 详情页里删掉的那篇，提示得由活着的页面来弹
        if (pendingUndoIds.isNotEmpty()) {
            val ids = pendingUndoIds
            pendingUndoIds = emptyList()
            showUndo(getString(R.string.toast_deleted), ids, getString(R.string.toast_restored))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 转屏 / 被系统回收再回来：还停在原来那一页
        outState.putInt(KEY_TAB, tab)
    }

    /**
     * 顶上那个滑块：「记录」是录音和转写，「笔记」是整理好的整理稿。
     *
     * 做成左右两块、而不是一个列表里分两组：用户要的是「打开哪边就是哪样东西」——
     * 「记录」点进去是这节课的原始转写，「笔记」点进去直接就是整理稿。
     */
    private fun setupTabs() {
        binding.tabRecords.setOnClickListener { selectTab(TAB_RECORDS) }
        binding.tabNotes.setOnClickListener { selectTab(TAB_NOTES) }
        paintTabs(false)
        // 蓝底那块要等布局完才知道一半有多宽；转屏重建之后也靠这一下摆正
        binding.tabsRail.post { paintTabs(false) }
    }

    private fun selectTab(which: Int) {
        if (tab == which) return
        tab = which
        // 换了页签，选中的那几篇也跟着换了一批，先把多选退掉
        exitSelect()
        scrollTopOnNextShow = true
        paintTabs(true)
        syncFilter()
        reload()
    }

    /** 蓝底滑块滑到选中的那一半，两个字的颜色跟着深浅。 */
    private fun paintTabs(animate: Boolean) {
        val on = MaterialColors.getColor(binding.tabs, com.google.android.material.R.attr.colorOnPrimary)
        val off = MaterialColors.getColor(binding.tabs, com.google.android.material.R.attr.colorOnSurfaceVariant)
        binding.tabRecords.setTextColor(if (tab == TAB_RECORDS) on else off)
        binding.tabNotes.setTextColor(if (tab == TAB_NOTES) on else off)
        val to = if (tab == TAB_NOTES) binding.tabsRail.width / 2f else 0f
        if (animate) {
            binding.tabsRail.animate().translationX(to).setDuration(180).start()
        } else {
            binding.tabsRail.translationX = to
        }
    }

    /** 切页签时按当前搜索词立刻重来一遍，不走搜索那 200ms 的防抖。 */
    private fun reload() {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            val q = binding.search.text?.toString().orEmpty()
            val list = withContext(Dispatchers.IO) {
                if (q.isBlank()) NoteStore.listMeta() else NoteStore.search(q)
            }
            showList(list, q)
        }
    }

    // ------------------------------------------------------------------ 列表

    private fun onQueryChanged(q: String) {
        // 搜索会把列表换掉一批，多选状态下「选了几篇」会变得莫名其妙，直接退出多选
        exitSelect()
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
        // 「笔记」页签只放已经整理好的；「记录」页签是全部录音 —— 整理过的也留着，
        // 那节课的原始转写还在用（「记录」里点开看转写，「笔记」里点开看整理稿）。
        val tabbed = if (tab == TAB_NOTES) list.filter { it.hasDigest } else list
        val shown = if (tab == TAB_RECORDS && filterUndigested) tabbed.filter { !it.hasDigest } else tabbed
        adapter.submit(shown)
        if (scrollTopOnNextShow) {
            scrollTopOnNextShow = false
            binding.list.scrollToPosition(0)
        }
        if (shown.isEmpty()) {
            binding.empty.visibility = View.VISIBLE
            binding.emptyText.text = getString(
                when {
                    tab == TAB_NOTES -> R.string.main_tab_notes_empty
                    query.isNotBlank() -> R.string.main_empty_search
                    filterUndigested -> R.string.main_empty_filtered
                    else -> R.string.main_empty
                }
            )
        } else {
            binding.empty.visibility = View.GONE
        }
        val total = shown.sumOf { it.durationMs }
        binding.notesCount.text = when {
            query.isNotBlank() -> getString(R.string.main_notes_hit, shown.size)
            tab == TAB_NOTES -> getString(R.string.main_tab_notes_count, shown.size)
            filterUndigested -> getString(R.string.main_notes_undigested, shown.size)
            total >= 60_000 -> getString(R.string.main_tab_records_count_time, shown.size, Formats.duration(total))
            else -> getString(R.string.main_tab_records_count, shown.size)
        }
    }

    private fun refresh() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { NoteStore.listMeta() }
            val q = binding.search.text?.toString().orEmpty()
            if (q.isBlank()) showList(list, q)
        }
    }

    /**
     * 筛选按钮的文字就是「点它会怎样」：开着的时候写着「显示全部」；暗一点的是没开。
     *
     * 只在「记录」页签露面 ——「笔记」里全是整理好的，这个开关在那边没有意义。
     */
    private fun syncFilter() {
        binding.filter.visibility = if (tab == TAB_RECORDS) View.VISIBLE else View.GONE
        binding.filter.text = getString(
            if (filterUndigested) R.string.main_filter_show_all else R.string.main_filter_only_undigested
        )
        binding.filter.alpha = if (filterUndigested) 1f else 0.55f
    }

    /**
     * 删完给一条能撤销的提示。
     *
     * 删除是把笔记挪进回收站、七天之后才真删，所以这里来得及 —— 用户「啊，删错了」的时候
     * 手上正好有一条能点的「撤销」。
     */
    private fun showUndo(message: String, ids: List<String>, done: String) {
        if (ids.isEmpty()) return
        Snackbar.make(binding.root, message, 8000)
            .setAnchorView(binding.fab)
            .setAction(R.string.common_undo) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { ids.forEach { NoteStore.restore(it) } }
                    refresh()
                    toast(done)
                }
            }
            .show()
    }

    /**
     * 回收站：删除只是把笔记挪进来，满七天才会真清掉，这中间随时能点一下捞回来。
     *
     * 多选删完那条「撤销」只活几秒，手滑之后过一会儿才反应过来的人得另有条路 —— 就是这里。
     */
    private fun openTrash() = startActivity(Intent(this, TrashActivity::class.java))

    /**
     * 点开一篇。
     *
     * 「记录」页签点进去是这节课的原始转写，「笔记」页签点进去直接就是整理稿 ——
     * 省掉「先进详情页、再点一下看整理稿」那一步。
     */
    private fun openNote(meta: NoteStore.Meta) {
        val target = if (tab == TAB_NOTES) DigestActivity::class.java else DetailActivity::class.java
        startActivity(Intent(this, target).putExtra(DetailActivity.EXTRA_ID, meta.id))
    }

    // ------------------------------------------------------------------ 多选

    /**
     * 进多选：长按任意一篇，或者右上角菜单里的「多选」。
     *
     * 这里只放「一批笔记一起做的事」——全选、AI 整理、导出、删除。整理稿 / 重命名这些
     * 单篇操作留在笔记详情页的菜单里，顶栏按钮少一点，选了几篇一眼看得见。
     */
    private fun enterSelect(id: String?) {
        if (adapter.itemCount == 0) return
        if (!selecting) {
            selecting = true
            binding.toolbar.visibility = View.GONE
            binding.selectBar.visibility = View.VISIBLE
            binding.fab.hide()
            adapter.selecting = true
        }
        if (id != null) {
            adapter.toggle(id)
        } else {
            // 菜单进来的：手指抬起的那一下会漏给底下的列表，短暂屏蔽
            ignoreClicksUntil = SystemClock.uptimeMillis() + 300
        }
        syncSelectBar()
    }

    private fun exitSelect() {
        if (!selecting) return
        selecting = false
        binding.selectBar.visibility = View.GONE
        binding.toolbar.visibility = View.VISIBLE
        binding.fab.show()
        adapter.selecting = false
        syncSelectBar()
    }

    private fun toggleSelect(meta: NoteStore.Meta) {
        adapter.toggle(meta.id)
        syncSelectBar()
    }

    private fun toggleSelectAll() {
        val all = adapter.currentList.map { it.id }
        if (adapter.selectedCount() >= all.size) adapter.selectAll(emptyList()) else adapter.selectAll(all)
        syncSelectBar()
    }

    private fun syncSelectBar() {
        val n = adapter.selectedCount()
        binding.selectCount.text = getString(R.string.select_count, n)
        binding.selectAll.text = getString(
            if (n > 0 && n >= adapter.itemCount) R.string.select_none else R.string.select_all
        )
        val can = n > 0 && !exporting
        binding.selectDigest.isEnabled = can
        binding.selectExport.isEnabled = can
        binding.selectDelete.isEnabled = can
        // 按钮都是纯文字，没有禁用态样式，压暗一点就够了
        val alpha = if (can) 1f else 0.4f
        binding.selectDigest.alpha = alpha
        binding.selectExport.alpha = alpha
        binding.selectDelete.alpha = alpha
    }

    private fun deleteSelected() {
        val ids = adapter.selectedIds()
        if (ids.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_delete_title, ids.size))
            .setMessage(R.string.select_delete_msg)
            .setPositiveButton(R.string.common_ok) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { ids.forEach { NoteStore.delete(it) } }
                    exitSelect()
                    refresh()
                    showUndo(
                        getString(R.string.select_deleted, ids.size), ids,
                        getString(R.string.select_restored, ids.size)
                    )
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    /** 多选导出：每篇一套 md + html + shots/，在「下载」里各占一个文件夹。 */
    private fun exportSelected() {
        val ids = adapter.selectedIds()
        if (ids.isEmpty() || exporting) return
        exporting = true
        syncSelectBar()
        toast(getString(R.string.select_exporting, ids.size))
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                try {
                    for (id in ids) {
                        val n = NoteStore.load(id) ?: continue
                        NoteStore.exportAll(this@MainActivity, n, null)
                    }
                    null
                } catch (t: Throwable) {
                    t.message ?: t.javaClass.simpleName
                }
            }
            exporting = false
            toast(
                if (err == null) getString(R.string.select_exported, ids.size)
                else getString(R.string.select_export_failed, err)
            )
            syncSelectBar()
        }
    }

    /**
     * 多选 AI 整理：排进后台队列，一篇接一篇跑，跑完发通知。
     *
     * 这是批量的意义所在 —— 攒了一周的课，选一片点一下就能去睡觉，不用守着一篇一篇点。
     */
    private fun digestSelected() {
        val ids = adapter.selectedIds()
        if (ids.isEmpty()) return
        if (!LlmDigest.isReady()) {
            toast(getString(R.string.digest_ai_need_key))
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        lifecycleScope.launch {
            val notes = withContext(Dispatchers.IO) {
                ids.mapNotNull { NoteStore.load(it) }
                    .filter { n -> n.entries.any { e -> !e.isImage && e.text.isNotBlank() } }
            }
            val queued = DigestJob.enqueue(notes)
            if (queued == 0) {
                toast(getString(R.string.select_digest_none))
            } else {
                toast(getString(R.string.select_digest_queued, queued))
                exitSelect()
            }
        }
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
            // 系统那个授权框写的是「截取您的屏幕」，内录却非要它不可，先说一句免得用户以为要录屏
            toast(getString(R.string.toast_internal_projection))
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
            return
        }
        // 纯麦克风录音本身不需要录屏授权，但截屏必须要。既然「开始记录就该有截图按钮」，
        // 就把这一次授权并进「开始记录」这一步：用户拒绝也不影响录音，只是少一个圆钮。
        if (Prefs.autoShot && !ShotGate.isReady() && OverlayPerm.granted(this) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {
            toast(getString(R.string.toast_projection_scope))
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            shotLauncher.launch(mgr.createScreenCaptureIntent())
            return
        }
        CaptureService.start(this, source)
        // 还差悬浮窗权限就在这一步问，录音照常开始，不因为圆钮被拦下
        attachShotBall()
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
     * 用的是纯麦克风、或者还没给悬浮窗权限，就安静跳过 —— 少一个圆钮不影响录音。
     * 权限这事交给记录页去问：这里一按开始记录就跳到记录页，对话框会被盖住看不见。
     */
    private fun attachShotBall() {
        if (!Prefs.autoShot) return
        if (!OverlayPerm.granted(this)) return
        lifecycleScope.launch {
            // 内录的 MediaProjection 是服务起来之后才登记的，等它登记好再挂圆钮
            var waited = 0
            while (!ShotGate.isReady() && waited < 2000) {
                delay(100)
                waited += 100
            }
            if (ShotGate.isReady()) {
                ShotService.start(this@MainActivity, bySession = true)
                // 服务可能早就跑着了（只是当时没权限挂圆钮），这时补一个
                ShotService.showBall(this@MainActivity)
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        /**
         * 详情页删掉笔记之后，回到列表页得补一条「撤销」。
         *
         * 删除只是挪进回收站，来得及捞回来 —— 但提示得由还活着的那个页面来弹。
         */
        var pendingUndoIds: List<String> = emptyList()

        /** 首页两个页签：0 = 记录（录音 + 转写），1 = 笔记（整理稿）。 */
        private const val TAB_RECORDS = 0
        private const val TAB_NOTES = 1

        /** 转屏 / 重建之后还停在原来那一页。 */
        private const val KEY_TAB = "tab"
    }
}
