package com.sensorhub.app.ui.devices

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.sensorhub.app.R
import com.sensorhub.app.SensorHubApp
import com.sensorhub.app.databinding.FragmentDeviceListBinding
import com.sensorhub.app.ui.common.UiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 设备列表页
 *
 * 本页演示了三件课程要求的事：
 *   ① 列表 → 详情 的跳转（点 item 进 DeviceDetailFragment）
 *   ② 加载 / 空 / 错误 / 内容 四态 UI
 *   ③ 用 repeatOnLifecycle 安全地收集 Flow
 *
 * 关于 repeatOnLifecycle（重要，容易写错）：
 *   错误写法：lifecycleScope.launch { viewModel.uiState.collect { ... } }
 *   问题：App 退到后台时，Fragment 的 View 已销毁但协程还在跑，
 *         一旦数据变化就会去更新已经不存在的 View → 崩溃或泄漏。
 *
 *   正确写法：repeatOnLifecycle(STARTED) 会在 onStart 时开始收集，
 *             onStop 时自动取消，完美匹配"界面可见"的时间窗口。
 *   这是 Google 官方推荐的 Flow 收集方式。
 */
class DeviceListFragment : Fragment() {

    private var _binding: FragmentDeviceListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DeviceListViewModel by viewModels()
    private lateinit var adapter: DeviceAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDeviceListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecycler()
        setupButtons()
        observeState()
    }

    private fun setupRecycler() {
        adapter = DeviceAdapter { device ->
            // 列表 → 详情：把 MAC 和设备名作为参数传过去
            // 用 Bundle 显式传参（未使用 SafeArgs 插件）：
            // key 必须和 nav_graph.xml 里 <argument android:name="..."> 完全一致，
            // 这样详情页 ViewModel 的 SavedStateHandle 才能自动取到值
            val args = bundleOf(
                "address" to device.address,
                "name" to device.name
            )
            findNavController().navigate(R.id.action_deviceList_to_detail, args)
        }
        binding.recyclerDevices.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@DeviceListFragment.adapter
        }
    }

    private fun setupButtons() {
        binding.btnScan.setOnClickListener {
            if (viewModel.isScanning.value) {
                viewModel.stopScan()
            } else {
                checkMockOrReal()
            }
        }
        binding.btnRetry.setOnClickListener { checkMockOrReal() }
    }

    /**
     * 判断是否处于模拟模式
     * 模拟模式下不用真扫描，直接造两条假设备
     */
    private fun checkMockOrReal() {
        lifecycleScope.launch {
            val useMock = SensorHubApp.instance.settings.mockEnabled.first()
            if (useMock) viewModel.loadMockDevices() else viewModel.startScan()
        }
    }

    /**
     * 观察状态并渲染四态 UI
     * 四个 View 叠在同一区域，同一时刻只显示一个
     */
    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                // 收集 UI 状态
                launch {
                    viewModel.uiState.collect { state -> renderState(state) }
                }

                // 收集扫描中标记，更新按钮文案
                launch {
                    viewModel.isScanning.collect { scanning ->
                        binding.btnScan.text = if (scanning) {
                            getString(R.string.btn_stop_scan)
                        } else {
                            getString(R.string.btn_start_scan)
                        }
                    }
                }
            }
        }
    }

    private fun renderState(state: UiState<List<com.sensorhub.app.ble.BleDevice>>) {
        // 先全部隐藏，再按需显示某一个 —— 这样不会出现两个态同时可见的 bug
        binding.progressBar.visibility = View.GONE
        binding.emptyState.visibility = View.GONE
        binding.errorState.visibility = View.GONE
        binding.recyclerDevices.visibility = View.GONE

        when (state) {
            is UiState.Loading -> binding.progressBar.visibility = View.VISIBLE

            is UiState.Empty -> binding.emptyState.visibility = View.VISIBLE

            is UiState.Error -> {
                binding.errorState.visibility = View.VISIBLE
                binding.tvError.text = state.message
            }

            is UiState.Content -> {
                binding.recyclerDevices.visibility = View.VISIBLE
                adapter.submitList(state.data)
            }
        }
    }

    override fun onDestroyView() {
        // Fragment 的 View 被销毁但 Fragment 实例还在（例如回退栈），
        // 必须把 binding 置空，否则会持有一个已经泄漏的 View 树
        binding.recyclerDevices.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
