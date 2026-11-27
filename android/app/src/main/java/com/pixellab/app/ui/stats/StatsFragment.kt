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

    /** 当前选中记录的 id（下拉框刷新后按 id 恢复选中，不跟位置走） */
    private var selectedRecordId: Long? = null

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
                selectedRecordId = rec.id
                viewModel.select(rec)
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { s ->
                    // Spinner 选项 = "滤镜 · 时间"；记录变化时重建 adapter，
                    // 并按记录 id 恢复选中项（而不是跟位置走，避免错位选错记录）
                    if (s.records.isNotEmpty()) {
                        val labels = s.records.map {
                            "${it.filterName} · ${timeFmt.format(Date(it.createdAt))}"
                        }
                        val old = binding.spinnerRecord.adapter as? ArrayAdapter<String>
                        val oldLabels = (0 until (old?.count ?: 0)).map { old!!.getItem(it).toString() }
                        if (oldLabels != labels) {
                            binding.spinnerRecord.adapter = ArrayAdapter(
                                requireContext(), android.R.layout.simple_spinner_dropdown_item, labels
                            )
                            val idx = s.records.indexOfFirst { it.id == selectedRecordId }
                            if (idx >= 0) binding.spinnerRecord.setSelection(idx, false)
                        }
                        recordsLoaded = true
                    }

                    val before = s.beforeStats
                    val after = s.afterStats
                    // 前/后直方图双色：与指标表"处理前/处理后"列色一一对应；
                    // 数据缺失（文件被清理等）时清空画布，不残留上一条记录的曲线
                    if (before != null) {
                        binding.histBefore.setStats(before, ContextCompat.getColor(requireContext(), R.color.tag_before))
                    } else {
                        binding.histBefore.clear()
                    }
                    if (after != null) {
                        binding.histAfter.setStats(after, ContextCompat.getColor(requireContext(), R.color.primary))
                    } else {
                        binding.histAfter.clear()
                    }

                    // RGB 三通道叠画（处理后结果图）；缺失同样清空
                    val rgb = s.rgbStats
                    if (rgb != null) {
                        binding.histRgb.setRgbStats(rgb.rBins, rgb.gBins, rgb.bBins)
                    } else {
                        binding.histRgb.clear()
                    }

                    // 空态：记录加载完且为空 → 显示引导，隐藏全部统计卡片
                    val empty = recordsLoaded && s.records.isEmpty()
                    binding.emptyStats.visibility = if (empty) View.VISIBLE else View.GONE
                    binding.statsContent.visibility = if (empty) View.GONE else View.VISIBLE

                    renderDetailTable(before, after)
                    // 前后分布完全一致时给一句说明，避免「两列数值一样」被误读成统计失效
                    val same = before != null && after != null &&
                        before.bins.contentEquals(after.bins)
                    binding.tvSameHint.visibility = if (same) View.VISIBLE else View.GONE

                    // 滤镜性能排行榜（历史聚合）
                    binding.boardChart.setRows(s.board)

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

        // 数据缺失显示 "–" 而不是 0（0 会让人误读成"指标真的是 0"）
        fun fmt1(v: Float?) = if (v == null) "–" else String.format(Locale.CHINA, "%.1f", v)
        fun fmt0(v: Float?) = if (v == null) "–" else String.format(Locale.CHINA, "%.0f", v)
        fun pct(v: Float?) = if (v == null) "–" else String.format(Locale.CHINA, "%.1f%%", v * 100)
        fun g(v: Int?) = v?.toString() ?: "–"

        val rows = listOf(
            Triple("灰度均值 μ", fmt1(before?.mean), fmt1(after?.mean)),
            Triple("标准差 σ（对比度）", fmt1(before?.stdDev), fmt1(after?.stdDev)),
            Triple("最暗 / 最亮",
                "${g(before?.minGray)} / ${g(before?.maxGray)}",
                "${g(after?.minGray)} / ${g(after?.maxGray)}"),
            Triple("中位数", g(before?.median), g(after?.median)),
            Triple("众数（出现最多灰度）", g(before?.mode), g(after?.mode)),
            Triple("信息熵（bit/像素）", fmt1(before?.entropy), fmt1(after?.entropy)),
            Triple("过曝率 >245", pct(before?.blowoutRatio), pct(after?.blowoutRatio)),
            Triple("欠曝率 <10", pct(before?.darkRatio), pct(after?.darkRatio)),
            // R/G/B 均值用整数 + 紧凑分隔符：三段小数 + 空格会让两列文字粘连重叠
            Triple("R / G / B 均值",
                "${fmt0(before?.meanR)} / ${fmt0(before?.meanG)} / ${fmt0(before?.meanB)}",
                "${fmt0(after?.meanR)} / ${fmt0(after?.meanG)} / ${fmt0(after?.meanB)}")
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
                // 单行 + 省略号：任何超长数值都只在自己的列内截断，
                // 不会挤压/覆盖相邻列（R/G/B 均值这类长文本曾出现两列粘连）
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
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
