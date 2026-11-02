package com.sensorhub.app.ui.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sensorhub.app.data.db.ReadingEntity
import com.sensorhub.app.databinding.ItemHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 历史记录适配器 */
class HistoryAdapter : ListAdapter<ReadingEntity, HistoryAdapter.HistoryViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class HistoryViewHolder(private val binding: ItemHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        // SimpleDateFormat 创建开销大，做成 companion 常量复用
        private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

        fun bind(item: ReadingEntity) {
            binding.tvTime.text = timeFormat.format(Date(item.timestamp))
            binding.tvValues.text = "%.1f℃ · %.0f%% · %.0fhPa · %dlux".format(
                item.temperature, item.humidity, item.pressure, item.lux
            )
            // 来源标记：区分真实蓝牙数据和模拟数据，演示时不会混淆
            binding.tvSource.text = if (item.source == "mock") "模拟" else "BLE"
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<ReadingEntity>() {
        override fun areItemsTheSame(old: ReadingEntity, new: ReadingEntity) = old.id == new.id
        override fun areContentsTheSame(old: ReadingEntity, new: ReadingEntity) = old == new
    }
}
