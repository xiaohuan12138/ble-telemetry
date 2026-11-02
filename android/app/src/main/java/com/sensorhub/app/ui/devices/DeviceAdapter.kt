package com.sensorhub.app.ui.devices

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sensorhub.app.R
import com.sensorhub.app.ble.BleDevice
import com.sensorhub.app.databinding.ItemDeviceBinding

/**
 * 设备列表适配器
 *
 * 用 ListAdapter 而不是 RecyclerView.Adapter 的理由：
 *   ListAdapter 内置了 DiffUtil，在后台线程比对新旧列表，
 *   只对真正变化的 item 做刷新。
 *
 *   为什么这很重要？扫描回调每 100ms 就来一次，如果每次都
 *   notifyDataSetChanged()：
 *     - 整个列表重绘 → 掉帧
 *     - item 的动画被打断 → 闪烁
 *     - 用户正在滑动的位置会被重置
 *   用 DiffUtil 后，只有 RSSI 变化的那个 item 会单独刷新。
 */
class DeviceAdapter(
    private val onClick: (BleDevice) -> Unit
) : ListAdapter<BleDevice, DeviceAdapter.DeviceViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemDeviceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(getItem(position), onClick)
    }

    class DeviceViewHolder(
        private val binding: ItemDeviceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(device: BleDevice, onClick: (BleDevice) -> Unit) {
            binding.tvName.text = device.name
            binding.tvAddress.text = device.address
            binding.tvRssi.text = itemView.context.getString(R.string.rssi_label, device.rssi)

            // 三格信号条：按 signalLevel 决定哪几格着色
            val bars = listOf(binding.bar1, binding.bar2, binding.bar3)
            val activeColor = ContextCompat.getColor(itemView.context, R.color.success)
            val idleColor = ContextCompat.getColor(itemView.context, R.color.disabled)
            bars.forEachIndexed { index, bar ->
                val on = index < device.signalLevel
                // View 的背景是 shape drawable，改颜色要取出 GradientDrawable 再 setColor
                (bar.background as? GradientDrawable)?.setColor(if (on) activeColor else idleColor)
            }

            binding.root.setOnClickListener { onClick(device) }
        }
    }

    /**
     * DiffUtil 回调：告诉 ListAdapter"怎样判断两个对象是同一个"
     *   areItemsTheSame     —— 是不是同一条数据（用 MAC 地址，全局唯一）
     *   areContentsTheSame  —— 内容是否完全一样（不一样才重绘）
     */
    object DiffCallback : DiffUtil.ItemCallback<BleDevice>() {
        override fun areItemsTheSame(old: BleDevice, new: BleDevice): Boolean =
            old.address == new.address

        override fun areContentsTheSame(old: BleDevice, new: BleDevice): Boolean =
            old == new
    }
}
