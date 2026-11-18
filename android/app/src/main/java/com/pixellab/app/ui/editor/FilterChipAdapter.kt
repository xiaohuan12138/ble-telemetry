package com.pixellab.app.ui.editor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pixellab.app.R

/** 滤镜芯片条的数据项：key 传给引擎，label 显示给用户 */
data class FilterChip(val key: String, val label: String)

/**
 * 滤镜选择芯片条。列表 = 5 个卷积预设 + 高斯 + 灰度，
 * "灰度"和"高斯"走 Convolver 的专门实现，其余走通用 3×3 卷积。
 */
class FilterChipAdapter(
    private val onSelect: (FilterChip) -> Unit
) : ListAdapter<FilterChip, FilterChipAdapter.VH>(DIFF) {

    companion object {
        val DEFAULT = listOf(
            FilterChip("SHARPEN", "锐化"),
            FilterChip("MEAN_BLUR", "均值模糊"),
            FilterChip("GAUSSIAN", "高斯模糊"),
            FilterChip("EDGE", "边缘检测"),
            FilterChip("SOBEL_V", "垂直边缘"),
            FilterChip("EMBOSS", "浮雕"),
            FilterChip("GRAYSCALE", "灰度")
        )
        private val DIFF = object : DiffUtil.ItemCallback<FilterChip>() {
            override fun areItemsTheSame(a: FilterChip, b: FilterChip) = a.key == b.key
            override fun areContentsTheSame(a: FilterChip, b: FilterChip) = a == b
        }
    }

    /** 当前选中项，用于渲染选中态 */
    var selectedKey: String = "SHARPEN"
        set(value) {
            val old = field
            field = value
            // 选中态变化只影响两个位置，用 notifyItemChanged 精准刷新
            val idxOld = currentList.indexOfFirst { it.key == old }
            val idxNew = currentList.indexOfFirst { it.key == value }
            if (idxOld >= 0) notifyItemChanged(idxOld)
            if (idxNew >= 0) notifyItemChanged(idxNew)
        }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = TextView(parent.context).apply {
            setPadding(28, 12, 28, 12)
            setTextSize(13f)
            setBackgroundResource(R.drawable.bg_chip)
        }
        val lp = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = 12 }
        tv.layoutParams = lp
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val chip = getItem(position)
        val selected = chip.key == selectedKey
        holder.label.text = chip.label
        holder.label.setTextColor(
            holder.label.context.getColor(
                if (selected) R.color.primary else R.color.text_secondary
            )
        )
        holder.label.alpha = if (selected) 1f else 0.75f
        holder.label.setOnClickListener { onSelect(chip) }
    }
}
