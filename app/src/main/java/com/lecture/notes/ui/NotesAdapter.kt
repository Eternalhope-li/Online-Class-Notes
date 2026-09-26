package com.lecture.notes.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lecture.notes.data.NoteStore
import com.lecture.notes.databinding.ItemNoteBinding
import com.lecture.notes.util.Formats

class NotesAdapter(
    private val onClick: (NoteStore.Meta) -> Unit,
    private val onLongClick: (NoteStore.Meta) -> Unit
) : ListAdapter<NoteStore.Meta, NotesAdapter.VH>(DIFF) {

    class VH(val b: ItemNoteBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = getItem(position)
        holder.b.title.text = m.title
        val parts = ArrayList<String>(4)
        parts.add(Formats.friendly(m.createdAt))
        if (m.durationMs > 0) parts.add(Formats.hms(m.durationMs))
        parts.add("${m.count} 句")
        if (m.stars > 0) parts.add("重点 ${m.stars}")
        if (m.images > 0) parts.add("图 ${m.images}")
        holder.b.meta.text = parts.joinToString(" · ")
        holder.b.preview.text = m.preview.ifBlank { "（没有识别到内容）" }
        holder.b.root.setOnClickListener { onClick(m) }
        holder.b.root.setOnLongClickListener {
            onLongClick(m)
            true
        }
    }

    fun submit(list: List<NoteStore.Meta>) = submitList(list)

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<NoteStore.Meta>() {
            override fun areItemsTheSame(a: NoteStore.Meta, b: NoteStore.Meta) = a.id == b.id

            override fun areContentsTheSame(a: NoteStore.Meta, b: NoteStore.Meta) =
                a.title == b.title && a.count == b.count && a.durationMs == b.durationMs &&
                        a.stars == b.stars && a.preview == b.preview && a.images == b.images
        }
    }
}
