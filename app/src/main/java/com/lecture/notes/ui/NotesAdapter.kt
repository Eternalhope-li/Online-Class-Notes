package com.lecture.notes.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lecture.notes.R
import com.google.android.material.color.MaterialColors
import com.lecture.notes.data.NoteStore
import com.lecture.notes.databinding.ItemNoteBinding
import com.lecture.notes.util.Formats

class NotesAdapter(
    private val onClick: (NoteStore.Meta) -> Unit,
    private val onLongClick: (NoteStore.Meta) -> Unit
) : ListAdapter<NoteStore.Meta, NotesAdapter.VH>(DIFF) {

    class VH(val b: ItemNoteBinding) : RecyclerView.ViewHolder(b.root) {
        /** XML 里没写 strokeColor，卡片按主题取的那个默认描边色；选中要高亮，退出多选得还回来。 */
        val outline: Int = b.root.strokeColor
    }

    /** 选中的笔记 id。放在 Adapter 里，列表刷新（搜索 / 删完）之后不用再同步一遍。 */
    private val selected = LinkedHashSet<String>()

    /** 多选模式：卡片右侧露出勾选框，未选中的压暗一点。 */
    var selecting = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) selected.clear()
            notifyItemRangeChanged(0, itemCount)
        }

    fun selectedIds(): List<String> = selected.toList()

    fun selectedCount(): Int = selected.size

    /** 点一下卡片：多选里就是勾上 / 取消。 */
    fun toggle(id: String) {
        if (!selected.remove(id)) selected.add(id)
        val at = currentList.indexOfFirst { it.id == id }
        if (at >= 0) notifyItemChanged(at)
    }

    fun selectAll(ids: List<String>) {
        selected.clear()
        selected.addAll(ids)
        notifyItemRangeChanged(0, itemCount)
    }

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
        if (m.hasDigest) parts.add(holder.b.root.context.getString(R.string.main_digested))
        holder.b.meta.text = parts.joinToString(" · ")
        holder.b.preview.text = m.preview.ifBlank { "（没有识别到内容）" }
        val checked = m.id in selected
        holder.b.check.visibility = if (selecting) View.VISIBLE else View.GONE
        holder.b.check.isChecked = checked
        holder.b.root.alpha = if (selecting && !checked) 0.55f else 1f
        holder.b.root.strokeWidth = if (checked) dp(holder.b.root, 2f) else dp(holder.b.root, 1f)
        holder.b.root.strokeColor = if (checked) {
            MaterialColors.getColor(holder.b.root, com.google.android.material.R.attr.colorPrimary)
        } else {
            holder.outline
        }
        holder.b.root.setOnClickListener { onClick(m) }
        holder.b.root.setOnLongClickListener {
            onLongClick(m)
            true
        }
    }

    fun submit(list: List<NoteStore.Meta>) {
        // 删掉之后要把已经不存在的 id 从选中集里摘掉，否则「已选 N 篇」会虚高
        if (selected.isNotEmpty()) {
            val alive = list.mapTo(HashSet()) { it.id }
            selected.retainAll(alive)
        }
        submitList(list)
    }

    private fun dp(v: View, value: Float): Int = (value * v.resources.displayMetrics.density).toInt()

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<NoteStore.Meta>() {
            override fun areItemsTheSame(a: NoteStore.Meta, b: NoteStore.Meta) = a.id == b.id

            override fun areContentsTheSame(a: NoteStore.Meta, b: NoteStore.Meta) =
                a.title == b.title && a.count == b.count && a.durationMs == b.durationMs &&
                a.stars == b.stars && a.preview == b.preview && a.images == b.images &&
                        a.hasDigest == b.hasDigest
        }
    }
}
