package com.pixellab.app.ui.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pixellab.app.PixelLabApp
import com.pixellab.app.R
import com.pixellab.app.databinding.FragmentStatsBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统计页：选一条历史 → 前/后直方图对比 + μ/σ 指标 + EXIF 元数据卡。
 */
class StatsFragment : Fragment() {

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StatsViewModel by viewModels {
        StatsViewModel.Factory(requireContext().applicationContext as PixelLabApp)
    }
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        viewModel.loadRecords()

        // Spinner：选历史记录
        binding.spinnerRecord.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val rec = viewModel.state.value.records.getOrNull(pos) ?: return
                viewModel.select(rec)
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { s ->
                    // Spinner 选项 = "滤镜 · 时间"
                    if (s.records.isNotEmpty()) {
                        val labels = s.records.map {
                            "${it.filterName} · ${timeFmt.format(Date(it.createdAt))}"
                        }
                        val adapter = ArrayAdapter(
                            requireContext(), android.R.layout.simple_spinner_dropdown_item, labels
                        )
                        // 避免每次数据刷新都重置用户的选择
                        if (binding.spinnerRecord.adapter == null) {
                            binding.spinnerRecord.adapter = adapter
                        }
                    }

                    val before = s.beforeStats
                    val after = s.afterStats
                    if (before != null) binding.histBefore.setStats(before)
                    if (after != null) binding.histAfter.setStats(after)

                    (binding.tvMeanBefore as TextView).text = before?.let {
                        getString(R.string.stats_mean, it.mean) + "   " +
                            getString(R.string.stats_stddev, it.stdDev)
                    } ?: ""
                    (binding.tvMeanAfter as TextView).text = after?.let {
                        getString(R.string.stats_mean, it.mean) + "   " +
                            getString(R.string.stats_stddev, it.stdDev)
                    } ?: ""

                    if (s.exifText != null) {
                        binding.exifCard.visibility = View.VISIBLE
                        binding.tvExif.text = s.exifText
                    } else {
                        binding.exifCard.visibility = View.VISIBLE
                        binding.tvExif.text = getString(R.string.stats_exif_empty)
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
