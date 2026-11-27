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
 *
 * 多选模式：长按任意一条进入，此后点按变为"选中/取消选中"，
 * 勾选数变化通过 onSelectionChanged 通知 Fragment 更新操作栏。
 * 选中状态按 id 记在 Set 里，DiffUtil 复用行时由 onBindViewHolder 重新渲染。
 */
class PhotoAdapter(
    private val onOpen: (PhotoEntity) -> Unit,
    private val onDelete: (PhotoEntity) -> Unit,
    private val onSelectionChanged: (Int) -> Unit = {}
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
            "SOBEL_V" to 0xFFEF4444.toInt(),
            "MEDIAN" to 0xFFF97316.toInt(),
            "EQUALIZE" to 0xFF10B981.toInt(),
            "OTSU" to 0xFF0EA5E9.toInt()
        )

        /** 未登记的滤镜回退中性灰 */
        private fun filterColor(key: String) =
            FILTER_COLORS[key] ?: 0xFF94A3B8.toInt()

        /** 滤镜显示名（GAUSSIAN/GRAYSCALE 与特殊滤镜不在 FilterPreset 枚举里，单独给名） */
        private fun filterLabel(key: String) = when (key) {
            "GAUSSIAN" -> "高斯模糊"
            "GRAYSCALE" -> "灰度"
            "EQUALIZE" -> "直方图均衡"
            "OTSU" -> "自动二值化"
            "MEDIAN" -> "中值滤波"
            else -> FilterPreset.fromName(key).displayName
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.imgThumb)
        val check: View = view.findViewById(R.id.checkIndicator)
        val dot: View = view.findViewById(R.id.dotFilter)
        val filter: TextView = view.findViewById(R.id.tvFilter)
        val meta: TextView = view.findViewById(R.id.tvMeta)
        val time: TextView = view.findViewById(R.id.tvTime)
        val delete: View = view.findViewById(R.id.btnDelete)
    }

    /** 是否处于多选模式（长按进入，clearSelection 退出） */
    var selectionMode = false
        private set

    /** 已选中的记录 id 集 */
    val selectedIds = LinkedHashSet<Long>()

    /** 当前选中条目对应的实体（删除时用；随列表刷新自然失效，删除前现取） */
    fun selectedPhotos(): List<PhotoEntity> =
        currentList.filter { it.id in selectedIds }

    /** 退出多选并整页刷新 */
    fun clearSelection() {
        if (!selectionMode && selectedIds.isEmpty()) return
        selectionMode = false
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    private fun toggleSelect(photo: PhotoEntity) {
        if (selectedIds.contains(photo.id)) selectedIds.remove(photo.id) else selectedIds.add(photo.id)
        // 全部取消时自动退出多选模式，避免停在"空选择"状态
        if (selectedIds.isEmpty()) {
            selectionMode = false
            notifyDataSetChanged()
        } else {
            val pos = currentList.indexOfFirst { it.id == photo.id }
            if (pos >= 0) notifyItemChanged(pos)
        }
        onSelectionChanged(selectedIds.size)
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
        holder.filter.text = filterLabel(photo.filterName)
        // 滤镜色标：一眼区分这条记录用了哪类滤镜（颜色与 FilterChipAdapter 主题一致）
        holder.dot.backgroundTintList = ColorStateList.valueOf(filterColor(photo.filterName))
        holder.meta.text = "${photo.width}×${photo.height} · ${photo.costMs} ms"
        holder.time.text = TIME_FMT.format(Date(photo.createdAt))

        // 多选勾选指示：选中主色实心，未选透明描边
        if (selectionMode) {
            holder.check.visibility = View.VISIBLE
            holder.check.backgroundTintList = if (photo.id in selectedIds)
                ColorStateList.valueOf(0xFF6366F1.toInt()) else ColorStateList.valueOf(0x00000000)
        } else {
            holder.check.visibility = View.GONE
        }

        // 点击行为随模式切换：普通模式打开，多选模式切换勾选
        holder.itemView.setOnClickListener {
            if (selectionMode) toggleSelect(photo) else onOpen(photo)
        }
        // 长按进入多选并选中当前条目
        holder.itemView.setOnLongClickListener {
            if (!selectionMode) {
                selectionMode = true
                selectedIds.add(photo.id)
                notifyDataSetChanged()
                onSelectionChanged(selectedIds.size)
                true
            } else {
                toggleSelect(photo)
                true
            }
        }
        holder.delete.setOnClickListener { onDelete(photo) }
    }
}
