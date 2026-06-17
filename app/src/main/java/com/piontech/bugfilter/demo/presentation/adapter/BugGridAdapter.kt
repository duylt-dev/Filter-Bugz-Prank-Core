package com.piontech.bugfilter.demo.presentation.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.piontech.bugfilter.demo.R
import com.piontech.bugfilter.demo.domain.model.BugFilter

/**
 * Lưới chọn con bọ ở [com.piontech.bugfilter.demo.presentation.screen.bugs.BugsActivity]: mỗi ô gồm ảnh thumbnail (load từ URL bằng Coil) + tên.
 * Bấm 1 ô → [onSelect] để mở màn camera với đúng con bọ đó.
 */
class BugGridAdapter(
    private val onSelect: (BugFilter) -> Unit
) : RecyclerView.Adapter<BugGridAdapter.VH>() {

    private var items: List<BugFilter> = emptyList()

    /** Cập nhật danh sách (gọi từ Activity khi ViewModel phát Content). */
    fun submit(list: List<BugFilter>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val img: ImageView = view.findViewById(R.id.imgThumb)
        val tvName: TextView = view.findViewById(R.id.tvName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_bug, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name
        holder.img.load(item.thumbnail) {
            crossfade(true)
        }
        holder.itemView.setOnClickListener { onSelect(item) }
    }

    override fun getItemCount() = items.size
}