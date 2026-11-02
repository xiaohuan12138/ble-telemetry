package com.sensorhub.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.sensorhub.app.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch

/** 设置页 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvAbout.text = viewModel.aboutText
        setupSwitches()
        observeState()
    }

    /**
     * 开关的"回环问题"与解法
     *
     * 问题：ViewModel 的 Flow 回流时会调用 switch.isChecked = x，
     *       这会触发 onCheckedChanged → 再次调用 ViewModel → 再次回流 … 死循环。
     *
     * 解法：用 buttonView.isPressed 判断是不是用户手指点的。
     *   代码赋值 → isPressed == false → 不处理
     *   用户点击 → isPressed == true  → 才写入设置
     * 比"先解绑再赋再绑"那套写法干净得多，也不会漏绑。
     */
    private fun setupSwitches() {
        binding.switchMock.setOnCheckedChangeListener { button, checked ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            viewModel.setMockEnabled(checked)
            Snackbar.make(
                binding.root,
                if (checked) "已切换到模拟数据源" else "已切换到真实蓝牙设备",
                Snackbar.LENGTH_SHORT
            ).show()
        }
        binding.switchAutoReconnect.setOnCheckedChangeListener { button, checked ->
            if (button.isPressed) viewModel.setAutoReconnect(checked)
        }
        binding.switchNotify.setOnCheckedChangeListener { button, checked ->
            if (button.isPressed) viewModel.setNotifyEnabled(checked)
        }

        binding.btnApplyMac.setOnClickListener {
            val mac = binding.etMac.text?.toString()?.trim().orEmpty()
            if (mac.isEmpty()) {
                Snackbar.make(binding.root, "请输入 MAC 地址", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.applyManualMac(mac)
            Snackbar.make(binding.root, "已保存，可在设备页手动直连", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.mockEnabled.collect { binding.switchMock.isChecked = it } }
                launch {
                    viewModel.autoReconnect.collect { binding.switchAutoReconnect.isChecked = it }
                }
                launch { viewModel.notifyEnabled.collect { binding.switchNotify.isChecked = it } }
                launch {
                    viewModel.manualMac.collect { mac ->
                        // 只在输入框为空时才回填，避免覆盖用户正在输入的内容
                        if (binding.etMac.text.isNullOrEmpty() && mac.isNotEmpty()) {
                            binding.etMac.setText(mac)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
