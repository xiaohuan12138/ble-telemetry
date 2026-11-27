package com.pixellab.app.ui.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.pixellab.app.R
import com.pixellab.app.image.FilterCatalog
import kotlin.math.roundToInt

/**
 * 横向柱状图自定义 View：滤镜性能排行榜。
 *
 * 数据 = 历史记录按滤镜聚合（平均耗时 + 使用次数），柱长 ∝ 平均耗时，
 * 行尾标注 "xx ms · n 次"。与 [HistogramView] 互补：直方图看"一张图的分布"，
 * 排行榜看"整个使用历史里各算法的性能"——数据驱动的性能页。
 *
 * 性能不变式：onDraw 零对象分配（Paint/RectF 预建复用）。
 */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 一行数据：滤镜 key + 平均耗时 + 使用次数 */
    data class Row(val key: String, val avgMs: Float, val count: Int)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF303F9F.toInt() }
    private val barBgPaint = Paint().apply { color = 0xFFE8EAF6.toInt() }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#555555")
        textSize = 30f
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#303F9F")
        textSize = 26f
        textAlign = Paint.Align.RIGHT
    }
    private val rect = RectF()

    private var rows: List<Row> = emptyList()

    fun setRows(list: List<Row>) {
        rows = list.sortedByDescending { it.avgMs }   // 平均耗时最高的排最上面
        invalidate()
        requestLayout()   // 行数变了 → 高度跟着变（wrap_content 需要重新 measure）
    }

    /** wrap_content 支持：高度 = 行数 × 单行高（空态给一个提示条的高度） */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rowH = 108
        val desired = (rows.size * rowH).coerceAtLeast(96)
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (rows.isEmpty()) {
            canvas.drawText(context.getString(R.string.stats_board_empty), w / 2, h / 2, labelPaint.apply { textAlign = Paint.Align.CENTER })
            labelPaint.textAlign = Paint.Align.LEFT
            return
        }

        val rowH = h / rows.size
        val barLeft = w * 0.30f          // 左侧留给滤镜名
        // 右侧数值区要放得下 "478 ms · 3次"：0.86 时数值区只剩 0.14w，
        // 右对齐长文本左端会超出 View 被裁成 "8 ms"（数值 478 只剩 8）
        val barRight = w * 0.78f
        val maxMs = rows.maxOf { it.avgMs }.coerceAtLeast(1f)

        for ((i, row) in rows.withIndex()) {
            val cy = rowH * i + rowH / 2
            val barH = (rowH * 0.42f).coerceAtMost(34f)

            // 滤镜名（中文显示名）
            canvas.drawText(
                FilterCatalog.displayName(row.key),
                12f, cy + barH * 0.4f, labelPaint
            )

            // 背景条 + 数据条（长度 ∝ 平均耗时，圆角胶囊）
            rect.set(barLeft, cy - barH / 2, barRight, cy + barH / 2)
            canvas.drawRoundRect(rect, barH / 2, barH / 2, barBgPaint)
            val len = (barRight - barLeft) * (row.avgMs / maxMs).coerceIn(0.04f, 1f)
            rect.set(barLeft, cy - barH / 2, barLeft + len, cy + barH / 2)
            canvas.drawRoundRect(rect, barH / 2, barH / 2, barPaint)

            // 数值：平均 ms · 次数
            canvas.drawText(
                "${row.avgMs.roundToInt()} ms · ${row.count}次",
                w - 8f, cy + barH * 0.4f, valuePaint
            )
        }
    }
}
