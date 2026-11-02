package com.sensorhub.app.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sensorhub.app.R
import com.sensorhub.app.databinding.FragmentHistoryBinding
import kotlinx.coroutines.launch

/** 历史记录页 */
class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HistoryViewModel by viewModels()
    private val adapter = HistoryAdapter()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerHistory.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@HistoryFragment.adapter
        }

        binding.btnClear.setOnClickListener { confirmClear() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.readings.collect { list ->
                        adapter.submitList(list)
                        binding.emptyState.visibility =
                            if (list.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.count.collect {
                        binding.tvCount.text = getString(R.string.history_count, it)
                    }
                }
                launch {
                    viewModel.stats.collect { stats ->
                        binding.tvTempRange.text = if (stats?.minT != null) {
                            "%.1f ~ %.1f ℃".format(stats.minT, stats.maxT)
                        } else "--"
                        binding.tvHumRange.text = if (stats?.minH != null) {
                            "%.0f ~ %.0f %%".format(stats.minH, stats.maxH)
                        } else "--"
                    }
                }
            }
        }
    }

    /** 危险操作二次确认：这是 Material Design 的规范，也是基本的产品素养 */
    private fun confirmClear() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("确认清空")
            .setMessage("将删除全部历史采样记录，此操作不可撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> viewModel.clearAll() }
            .show()
    }

    override fun onDestroyView() {
        binding.recyclerHistory.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
