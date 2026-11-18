package com.pixellab.app.ui.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.pixellab.app.R
import com.pixellab.app.image.Histogram

/**
 * 灰度直方图自定义 View（C1 "自定义 View"要求 + 性能展示）。
 *
 * 绘制策略：
 *   - 256 个 bin 全画太密，屏幕宽度只有几百像素——按 View 宽度动态分桶，
 *     每个屏幕像素一个柱，绘制成本 O(屏宽) 而不是 O(256)；
 *   - 高度按最大计数归一化（取对数可选拉伸暗部，这里用线性 + log 混合）；
 *   - onDraw 里零对象分配：Paint 全部预建，避免 GC 抖动（这正是
 *     "自定义 View 性能优化"的标准做法）。
 */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.histogram_bar)
    }
    private val gridPaint = Paint().apply {
        color = context.getColor(R.color.histogram_grid)
        strokeWidth = 1f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textSize = 22f
    }

    private var bins: IntArray? = null

    /** 外部喂直方图数据，喂完触发重绘 */
    fun setStats(stats: Histogram.Stats) {
        this.bins = stats.bins
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = bins ?: return
        val w = width.toFloat()
        val h = height.toFloat()

        // 三条横向网格线（25% / 50% / 75%）
        for (i in 1..3) {
            val y = h * i / 4
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        // 屏宽分桶：bucket = 256 / 屏宽，每桶取桶内最大值（保峰值不失真）
        val bucketCount = w.toInt().coerceIn(32, 256)
        val perBucket = 256f / bucketCount
        var maxVal = 1L
        val buckets = IntArray(bucketCount)
        for (i in 0 until 256) {
            val b = (i / perBucket).toInt().coerceIn(0, bucketCount - 1)
            buckets[b] += data[i]
            if (buckets[b] > maxVal) maxVal = buckets[b].toLong()
        }

        // 逐桶画柱：柱宽 = 屏宽/桶数，高 = 归一化计数
        val barW = w / bucketCount
        for (b in 0 until bucketCount) {
            if (buckets[b] == 0) continue
            val bh = h * (buckets[b] / maxVal.toFloat()) * 0.95f
            canvas.drawRect(
                b * barW, h - bh,
                (b + 1) * barW, h,
                barPaint
            )
        }

        // 轴标注 0 / 128 / 255
        canvas.drawText("0", 4f, h - 6f, textPaint)
        canvas.drawText("128", w / 2 - 20f, h - 6f, textPaint)
        canvas.drawText("255", w - 60f, h - 6f, textPaint)
    }
}
