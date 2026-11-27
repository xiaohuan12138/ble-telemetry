package com.pixellab.app.ui.stats

import android.os.Bundle
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pixellab.app.PixelLabApp
import com.pixellab.app.R
import com.pixellab.app.databinding.FragmentStatsBinding
import com.pixellab.app.image.Histogram
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

    /** 历史记录是否已加载过一次（避免 DB 异步加载前闪现空态） */
    private var recordsLoaded = false

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
                        recordsLoaded = true
                    }

                    val before = s.beforeStats
                    val after = s.afterStats
                    // 前/后直方图双色：与指标表"处理前/处理后"列色一一对应
                    if (before != null) {
                        binding.histBefore.setStats(before, ContextCompat.getColor(requireContext(), R.color.tag_before))
                    }
                    if (after != null) {
                        binding.histAfter.setStats(after, ContextCompat.getColor(requireContext(), R.color.primary))
                    }

                    // RGB 三通道叠画（处理后结果图）
                    s.rgbStats?.let {
                        binding.histRgb.setRgbStats(it.rBins, it.gBins, it.bBins)
                    }

                    // 空态：记录加载完且为空 → 显示引导，隐藏全部统计卡片
                    val empty = recordsLoaded && s.records.isEmpty()
                    binding.emptyStats.visibility = if (empty) View.VISIBLE else View.GONE
                    binding.statsContent.visibility = if (empty) View.GONE else View.VISIBLE

                    renderDetailTable(before, after)

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

    /**
     * 渲染"量化指标对比"表：每个指标一行（指标名 | 处理前 | 处理后）。
     * 动态构建而不是写死 XML：行数由指标决定，且前后值要用不同颜色区分。
     */
    private fun renderDetailTable(
        before: Histogram.Stats?, after: Histogram.Stats?
    ) {
        val table = binding.detailTable
        table.removeAllViews()
        if (before == null && after == null) return

        fun fmt1(v: Float) = String.format(Locale.CHINA, "%.1f", v)
        fun pct(v: Float) = String.format(Locale.CHINA, "%.1f%%", v * 100)

        val rows = listOf(
            Triple("灰度均值 μ", fmt1(before?.mean ?: 0f), fmt1(after?.mean ?: 0f)),
            Triple("标准差 σ（对比度）", fmt1(before?.stdDev ?: 0f), fmt1(after?.stdDev ?: 0f)),
            Triple("最暗 / 最亮", "${before?.minGray ?: 0} / ${before?.maxGray ?: 0}",
                "${after?.minGray ?: 0} / ${after?.maxGray ?: 0}"),
            Triple("中位数", "${before?.median ?: 0}", "${after?.median ?: 0}"),
            Triple("众数（出现最多灰度）", "${before?.mode ?: 0}", "${after?.mode ?: 0}"),
            Triple("信息熵（bit/像素）", fmt1(before?.entropy ?: 0f), fmt1(after?.entropy ?: 0f)),
            Triple("过曝率 >245", pct(before?.blowoutRatio ?: 0f), pct(after?.blowoutRatio ?: 0f)),
            Triple("欠曝率 <10", pct(before?.darkRatio ?: 0f), pct(after?.darkRatio ?: 0f)),
            Triple("R / G / B 均值",
                "${fmt1(before?.meanR ?: 0f)} / ${fmt1(before?.meanG ?: 0f)} / ${fmt1(before?.meanB ?: 0f)}",
                "${fmt1(after?.meanR ?: 0f)} / ${fmt1(after?.meanG ?: 0f)} / ${fmt1(after?.meanB ?: 0f)}")
        )
        for ((label, bv, av) in rows) {
            table.addView(buildRow(label, bv, av))
        }
    }

    /** 构建一行：label / before / after 三列 TextView */
    private fun buildRow(label: String, beforeVal: String, afterVal: String): View {
        val ctx = requireContext()
        fun tv(text: String, weight: Float, color: Int, bold: Boolean = false) =
            TextView(ctx).apply {
                this.text = text
                textSize = 12f
                setTextColor(color)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
                if (bold) setTypeface(typeface, Typeface.BOLD)
            }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
            addView(tv(label, 1.2f, ctx.getColor(R.color.text_secondary)))
            addView(tv(beforeVal, 1f, ctx.getColor(R.color.tag_before)))
            addView(tv(afterVal, 1f, ctx.getColor(R.color.tag_after), bold = true))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
