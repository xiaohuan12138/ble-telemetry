package com.pixellab.app.ui.gallery

import android.content.res.ColorStateList
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

        /** 滤镜 → 色标颜色（靛紫系主题 + 各滤镜一个区分色） */
        private val FILTER_COLORS = mapOf(
            "GAUSSIAN" to 0xFF6366F1.toInt(),
            "GRAYSCALE" to 0xFF64748B.toInt(),
            "SHARPEN" to 0xFFF59E0B.toInt(),
            "MEAN_BLUR" to 0xFF38BDF8.toInt(),
            "EDGE" to 0xFF14B8A6.toInt(),
            "EMBOSS" to 0xFFA78BFA.toInt(),
            "SOBEL_V" to 0xFFEF4444.toInt()
        )

        /** 未登记的滤镜回退中性灰 */
        private fun filterColor(key: String) =
            FILTER_COLORS[key] ?: 0xFF94A3B8.toInt()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.imgThumb)
        val dot: View = view.findViewById(R.id.dotFilter)
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
        // GAUSSIAN/GRAYSCALE 走 Convolver 专门实现，不在 FilterPreset 枚举里，单独给显示名
        holder.filter.text = when (photo.filterName) {
            "GAUSSIAN" -> "高斯模糊"
            "GRAYSCALE" -> "灰度"
            else -> FilterPreset.fromName(photo.filterName).displayName
        }
        // 滤镜色标：一眼区分这条记录用了哪类滤镜（颜色与 FilterChipAdapter 主题一致）
        holder.dot.backgroundTintList = ColorStateList.valueOf(filterColor(photo.filterName))
        holder.meta.text = "${photo.width}×${photo.height} · ${photo.costMs} ms"
        holder.time.text = TIME_FMT.format(Date(photo.createdAt))

        holder.itemView.setOnClickListener { onOpen(photo) }
        holder.delete.setOnClickListener { onDelete(photo) }
    }
}
