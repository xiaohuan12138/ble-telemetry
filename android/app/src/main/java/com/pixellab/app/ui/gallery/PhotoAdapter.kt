package com.pixellab.app.ui.gallery

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pixellab.app.R
import com.pixellab.app.data.db.PhotoEntity
import com.pixellab.app.image.FilterPreset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 图库列表适配器：ListAdapter + DiffUtil。
 *
 * DiffUtil 只重绘"真的变了"的行（新插入一行，其它行零刷新），
 * 比 notifyDataSetChanged 整页重绘快得多——这也是列表性能优化的标准答案。
 */
class PhotoAdapter(
    private val onOpen: (PhotoEntity) -> Unit,
    private val onDelete: (PhotoEntity) -> Unit
) : ListAdapter<PhotoEntity, PhotoAdapter.VH>(DIFF) {

    companion object {
        // DiffUtil 回调：新旧列表逐项比较，areItemsTheSame 用主键，areContentsTheSame 用 data class 的 equals
        private val DIFF = object : DiffUtil.ItemCallback<PhotoEntity>() {
            override fun areItemsTheSame(a: PhotoEntity, b: PhotoEntity) = a.id == b.id
            override fun areContentsTheSame(a: PhotoEntity, b: PhotoEntity) = a == b
        }
        private val TIME_FMT = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.imgThumb)
        val filter: TextView = view.findViewById(R.id.tvFilter)
        val meta: TextView = view.findViewById(R.id.tvMeta)
        val time: TextView = view.findViewById(R.id.tvTime)
        val delete: View = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_photo, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val photo = getItem(position)
        // 缩略图是独立的小文件（≤256px），直接解码不卡列表。
        // 不引 Glide/Coil 是刻意的：加载逻辑自己写，答辩少一个"三方库帮你做了什么"的黑盒
        val bmp = BitmapFactory.decodeFile(photo.thumbPath)
        if (bmp != null) {
            holder.thumb.setImageBitmap(bmp)
        } else {
            holder.thumb.setImageResource(R.drawable.ic_tab_gallery)
        }
        holder.filter.text = FilterPreset.fromName(photo.filterName).displayName
        holder.meta.text = "${photo.width}×${photo.height} · ${photo.costMs} ms"
        holder.time.text = TIME_FMT.format(Date(photo.createdAt))

        holder.itemView.setOnClickListener { onOpen(photo) }
        holder.delete.setOnClickListener { onDelete(photo) }
    }
}
