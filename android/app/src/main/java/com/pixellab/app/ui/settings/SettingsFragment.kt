package com.pixellab.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pixellab.app.PixelLabApp
import com.pixellab.app.R
import com.pixellab.app.databinding.FragmentSettingsBinding
import com.pixellab.app.image.FilterPreset
import kotlinx.coroutines.launch

/**
 * 设置页：默认滤镜 / 编辑最长边（性能旋钮）/ 自动分享开关 / 清空历史。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by viewModels {
        SettingsViewModel.Factory(requireContext().applicationContext as PixelLabApp)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        viewModel.load()

        // 默认滤镜下拉：枚举所有预设 + 灰度
        val filterNames = FilterPreset.entries.map { it.displayName } + "灰度"
        val filterKeys = FilterPreset.entries.map { it.name } + "GRAYSCALE"
        binding.spinnerFilter.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, filterNames
        )
        binding.spinnerFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                viewModel.setDefaultFilter(filterKeys[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }

        // 编辑长边滑条：直接影响卷积耗时（1280 ≈ 4.7MB，640 ≈ 1.2MB）
        binding.sliderEdge.addOnChangeListener { _, value, fromUser ->
            if (fromUser) viewModel.setEditEdge(value.toInt())
        }

        binding.swAutoShare.setOnCheckedChangeListener { _, checked ->
            viewModel.setAutoShare(checked)
        }

        // 清空历史：先弹确认框（防误删），确认后调 ViewModel
        binding.btnClear.setOnClickListener {
            val count = viewModel.state.value.count
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.settings_clear))
                .setMessage(getString(R.string.settings_clear_confirm, count))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    viewModel.clearAll()
                }
                .show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { s ->
                    binding.tvCount.text = getString(R.string.settings_count, s.count)
                    binding.tvEdgeValue.text = "${s.editEdge} px"
                    binding.sliderEdge.value = s.editEdge.toFloat()
                    binding.swAutoShare.isChecked = s.autoShare
                    val idx = filterKeys.indexOf(s.defaultFilter)
                    if (idx >= 0 && binding.spinnerFilter.selectedItemPosition != idx) {
                        binding.spinnerFilter.setSelection(idx, false)   // false：不触发回调循环
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
