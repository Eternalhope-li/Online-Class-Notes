package com.lecture.notes.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lecture.notes.R
import com.lecture.notes.data.NoteStore
import com.lecture.notes.databinding.ActivityTrashBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 回收站。
 *
 * 首页和详情页里删笔记只是「挪进来」，在这儿放满 7 天才会被清掉，中间随时能点一下放回去。
 * 多选删完给的那条「撤销」只活几秒 —— 手滑之后过一会儿才反应过来的人，得有这条正经的退路。
 */
class TrashActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrashBinding
    private lateinit var adapter: TrashAdapter
    private var items: List<NoteStore.Trashed> = emptyList()

    /** 恢复 / 清空是文件操作，防一手连点。 */
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_restore_all -> {
                    restore(items)
                    true
                }

                R.id.action_empty -> {
                    confirmEmpty()
                    true
                }

                else -> false
            }
        }

        adapter = TrashAdapter({ t -> restore(listOf(t)) }, { t -> confirmPurge(t) })
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        load()
    }

    /**
     * 彻底删掉一篇。
     *
     * 「清空回收站」以前是这里唯一能真正删东西的地方，想只扔一篇就得先把别的恢复出来。
     * 这一步没有退路，所以单独问一句。
     */
    private fun confirmPurge(t: NoteStore.Trashed) {
        if (busy) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.trash_delete_title, t.title))
            .setMessage(R.string.trash_delete_confirm)
            .setPositiveButton(R.string.trash_delete) { _, _ ->
                busy = true
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { NoteStore.deleteForever(t.id) }
                    busy = false
                    toast(getString(R.string.trash_deleted_one))
                    load()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun load() {
        lifecycleScope.launch {
            items = withContext(Dispatchers.IO) { NoteStore.trashedNotes() }
            adapter.submit(items)
            // 空的时候「回收站 · 0 篇」跟中间那句是重复的，收起来
            binding.count.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            binding.count.text = getString(R.string.trash_count, items.size)
            binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            // 空的时候这两项没意义，置灰比点了没反应好
            binding.toolbar.menu.findItem(R.id.action_restore_all)?.isEnabled = items.isNotEmpty()
            binding.toolbar.menu.findItem(R.id.action_empty)?.isEnabled = items.isNotEmpty()
        }
    }

    /** 放回去。一次多篇的时候只报个总数，别弹一串 toast。 */
    private fun restore(batch: List<NoteStore.Trashed>) {
        if (batch.isEmpty() || busy) return
        busy = true
        val ids = batch.map { it.id }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { ids.count { NoteStore.restore(it) } }
            busy = false
            toast(
                when {
                    ok == 0 -> getString(R.string.trash_restore_failed)
                    batch.size == 1 -> getString(R.string.trash_restored_one, batch[0].title)
                    else -> getString(R.string.trash_restored_all, ok)
                }
            )
            load()
        }
    }

    private fun confirmEmpty() {
        if (items.isEmpty() || busy) return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.trash_empty_confirm, items.size))
            .setPositiveButton(R.string.common_ok) { _, _ ->
                busy = true
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { NoteStore.emptyTrash() }
                    busy = false
                    toast(getString(R.string.trash_emptied))
                    load()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
