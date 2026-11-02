package com.sensorhub.app.ui.detail

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.sensorhub.app.R
import com.sensorhub.app.databinding.FragmentDeviceDetailBinding
import com.sensorhub.app.domain.ConnectionState
import com.sensorhub.app.domain.FilterMode
import com.sensorhub.app.protocol.SensorData
import kotlinx.coroutines.launch

/**
 * 设备详情页：实时数据 + 反向控制
 *
 * 这是整个 App 的主界面，也是答辩演示时停留最久的一页。
 *
 * UI 更新的原则：所有更新都从 StateFlow 的 collect 里发起，
 * 绝不在 BLE 回调线程里碰 View。Flow 的 collect 发生在主线程
 * （lifecycleScope 默认 Main），天然安全。
 */
class DeviceDetailFragment : Fragment() {

    private var _binding: FragmentDeviceDetailBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DeviceDetailViewModel by viewModels()

    /** 当前曲线显示的通道 */
    private var chartChannel = Channel.TEMP

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDeviceDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 页面标题用传过来的设备名
        binding.tvDeviceName.text = viewModel.deviceName

        setupControls()
        observeState()
    }

    // ================= 控件 =================

    private fun setupControls() {
        // ---- 连接 / 断开 ----
        binding.btnConnect.setOnClickListener {
            val state = viewModel.connectionState.value
            if (state is ConnectionState.Ready ||
                state is ConnectionState.Connecting ||
                state is ConnectionState.Discovering ||
                state is ConnectionState.Retrying
            ) {
                viewModel.disconnect()
            } else {
                viewModel.connect()
            }
        }

        // ---- LED 亮度滑杆 ----
        // 只在"手指离开"时发送：拖动过程中会触发几十次回调，
        // 每次都往蓝牙写会让协议栈堵死。这是很典型的"节流"处理。
        binding.seekLed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {
                viewModel.setLed(seek?.progress ?: 0)
            }
        })

        binding.btnBeep.setOnClickListener { viewModel.beep(300) }

        // ---- 上报周期：滑杆 0~98 → 200~10000ms ----
        binding.seekPeriod.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvPeriod.text = "${200 + progress * 100} ms"
            }
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {
                val ms = 200 + (seek?.progress ?: 8) * 100
                viewModel.setPeriod(ms)
            }
        })

        // ---- 滤波方式 ----
        binding.rgFilter.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbFilterNone -> FilterMode.NONE
                R.id.rbFilterMoving -> FilterMode.MOVING_AVERAGE
                R.id.rbFilterKalman -> FilterMode.KALMAN
                else -> FilterMode.KALMAN
            }
            viewModel.setFilterMode(mode)
            Snackbar.make(binding.root, "已切换为：${mode.displayName}", Snackbar.LENGTH_SHORT).show()
        }

        // ---- 曲线通道 ----
        binding.rgChartChannel.setOnCheckedChangeListener { _, checkedId ->
            chartChannel = when (checkedId) {
                R.id.rbHum -> Channel.HUM
                R.id.rbPress -> Channel.PRESS
                R.id.rbLux -> Channel.LUX
                else -> Channel.TEMP
            }
            binding.chartView.clear()
            binding.chartView.setLineColor(ContextCompat.getColor(requireContext(), chartChannel.colorRes))
            binding.chartView.unit = getString(chartChannel.unitRes)
        }
        binding.chartView.setLineColor(ContextCompat.getColor(requireContext(), chartChannel.colorRes))
    }

    // ================= 状态观察 =================

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                // 连接状态 → 状态点颜色 + 文案 + 按钮
                launch {
                    viewModel.connectionState.collect { renderConnectionState(it) }
                }

                // 传感器数据 → 四个卡片 + 曲线
                launch {
                    viewModel.data.collect { data ->
                        if (data != null) renderData(data)
                    }
                }

                // 链路质量
                launch {
                    viewModel.linkStats.collect { stats ->
                        binding.tvLoss.text = "丢包 ${"%.1f".format(stats.lossRatePercent)}% · " +
                                "CRC错 ${"%.1f".format(stats.crcErrorRatePercent)}%"
                    }
                }

                // 传感器健康诊断
                launch {
                    viewModel.health.collect { binding.tvState.text = it }
                }

                // 周期
                launch {
                    viewModel.periodMs.collect { ms ->
                        binding.tvPeriod.text = "$ms ms"
                        binding.seekPeriod.progress = ((ms - 200) / 100).coerceIn(0, 98)
                    }
                }
            }
        }
    }

    private fun renderConnectionState(state: ConnectionState) {
        val (text, colorRes) = when (state) {
            is ConnectionState.Idle -> getString(R.string.state_disconnected) to R.color.disabled
            is ConnectionState.Connecting -> getString(R.string.state_connecting) to R.color.warning
            is ConnectionState.Discovering -> "正在发现服务…" to R.color.warning
            is ConnectionState.Ready -> getString(R.string.state_ready) to R.color.success
            is ConnectionState.Retrying ->
                getString(R.string.state_retrying, state.attempt) to R.color.warning
            is ConnectionState.Failed -> state.message to R.color.danger
        }

        // 状态点：取 background 的 GradientDrawable 改颜色
        (binding.statusDot.background as? GradientDrawable)?.setColor(
            ContextCompat.getColor(requireContext(), colorRes)
        )
        binding.tvState.text = text

        binding.btnConnect.text = if (state is ConnectionState.Ready ||
            state is ConnectionState.Connecting ||
            state is ConnectionState.Discovering ||
            state is ConnectionState.Retrying
        ) {
            getString(R.string.btn_disconnect)
        } else {
            getString(R.string.btn_connect)
        }

        // 失败时给出可操作的提示（而不是只显示一个错误码）
        if (state is ConnectionState.Failed) {
            Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun renderData(data: SensorData) {
        binding.tvTemp.text = "%.1f".format(data.temperature)
        binding.tvHum.text = "%.1f".format(data.hum)
        binding.tvPress.text = "%.1f".format(data.press)
        binding.tvLux.text = "${data.lux}"
        binding.tvRssi.text = "${data.rssi} dBm"
        binding.tvAltitude.text = "%.0f m".format(data.altitude)

        // 曲线只画当前选中的通道
        val value = when (chartChannel) {
            Channel.TEMP -> data.temperature
            Channel.HUM -> data.hum
            Channel.PRESS -> data.press
            Channel.LUX -> data.lux.toFloat()
        }
        binding.chartView.addPoint(value)
    }

    /** 曲线通道枚举 */
    private enum class Channel(val colorRes: Int, val unitRes: Int) {
        TEMP(R.color.chart_temp, R.string.unit_celsius),
        HUM(R.color.chart_hum, R.string.unit_percent),
        PRESS(R.color.chart_press, R.string.unit_hpa),
        LUX(R.color.chart_lux, R.string.unit_lux)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
