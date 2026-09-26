package com.lecture.notes.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lecture.notes.R
import com.lecture.notes.data.NoteStore
import com.lecture.notes.databinding.ItemTrashBinding
import com.lecture.notes.util.Formats

/**
 * 回收站列表。
 *
 * 一行一篇：标题、「几天前删的」、开头两句，右边一颗「恢复」。整张卡片都能点，点的意思
 * 就是放回去 —— 回收站里不该再有第二个动作。
 */
class TrashAdapter(private val onRestore: (NoteStore.Trashed) -> Unit) :
    RecyclerView.Adapter<TrashAdapter.VH>() {

    private val items = ArrayList<NoteStore.Trashed>()

    class VH(val b: ItemTrashBinding) : RecyclerView.ViewHolder(b.root)

    fun submit(list: List<NoteStore.Trashed>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemTrashBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        val ctx = holder.b.root.context
        holder.b.title.text = t.title
        val parts = ArrayList<String>(3)
        parts.add(ctx.getString(R.string.trash_meta_delete, Formats.ago(t.deletedAt)))
        if (t.count > 0) parts.add(ctx.getString(R.string.trash_meta_count, t.count))
        if (t.images > 0) parts.add(ctx.getString(R.string.trash_meta_images, t.images))
        holder.b.meta.text = parts.joinToString(" · ")
        holder.b.preview.text = t.preview.ifBlank { ctx.getString(R.string.trash_no_preview) }
        holder.b.root.setOnClickListener { onRestore(t) }
    }
}
