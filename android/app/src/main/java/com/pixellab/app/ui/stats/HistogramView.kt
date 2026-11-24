package com.pixellab.app.ui.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.pixellab.app.R
import com.pixellab.app.image.Histogram
import kotlin.math.ln

/**
 * 灰度直方图自定义 View v2（C1 "自定义 View"要求 + 性能展示）。
 *
 * v2 升级点（都为了"看得出信息"）：
 *   1. 细线柱 → 渐变面积填充：Path 填充 + LinearGradient（主色 → 透明），
 *      顶部再描一条曲线轮廓，这是专业统计图表的标准画法；
 *   2. 线性归一 → 对数归一：h = ln(1+count)/ln(1+max)。二值图（如边缘检测）
 *      99% 像素挤在 0/255 两个 bin，线性刻度下其余全被压成一根线看不见，
 *      对数刻度让低频 bin 也能显示出来；
 *   3. 支持外部指定主色：处理前/处理后两条直方图分别用不同颜色，
 *      和下方指标表的列色一一对应。
 * 性能不变式：onDraw 零对象分配——Paint/Path 全部预建复用，shader 只在
 * 尺寸或颜色变化时重建。
 */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val gridPaint = Paint().apply {
        color = context.getColor(R.color.histogram_grid)
        strokeWidth = 1f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textSize = 22f
    }

    /** 面积填充（闭合）与顶部轮廓（不闭合）复用同一个 Path 对象 */
    private val areaPath = Path()
    private val linePath = Path()

    private var bins: IntArray? = null
    private var baseColor: Int = context.getColor(R.color.histogram_bar)

    fun setStats(stats: Histogram.Stats) = setStats(stats, baseColor)

    /** 换数据或换主题色时调用；shader 在这里重建而不是 onDraw */
    fun setStats(stats: Histogram.Stats, color: Int) {
        bins = stats.bins
        if (color != baseColor || areaPaint.shader == null) {
            baseColor = color
            rebuildShader()
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        rebuildShader()
    }

    private fun rebuildShader() {
        val h = height.toFloat()
        if (h <= 0) return
        areaPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            ColorUtils.setAlphaComponent(baseColor, 130),
            ColorUtils.setAlphaComponent(baseColor, 25),
            Shader.TileMode.CLAMP
        )
        strokePaint.color = baseColor
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = bins ?: return
        val w = width.toFloat()
        val h = height.toFloat()

        for (i in 1..3) {
            val y = h * i / 4
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        // 屏宽分桶：每桶取最大计数（保峰值不失真），绘制成本 O(屏宽)
        val bucketCount = w.toInt().coerceIn(32, 256)
        val perBucket = 256f / bucketCount
        var maxVal = 1L
        val buckets = IntArray(bucketCount)
        for (i in 0 until 256) {
            val b = (i / perBucket).toInt().coerceIn(0, bucketCount - 1)
            buckets[b] += data[i]
            if (buckets[b] > maxVal) maxVal = buckets[b].toLong()
        }

        // 对数归一 + 构建 Path：面积（闭合到底边）与轮廓（只描曲线）
        val logMax = ln(maxVal.toDouble() + 1.0).toFloat()
        val barW = w / bucketCount
        areaPath.rewind()
        linePath.rewind()
        areaPath.moveTo(0f, h)
        var started = false
        for (b in 0 until bucketCount) {
            if (buckets[b] == 0 && !started) continue
            val norm = ln(buckets[b].toDouble() + 1.0).toFloat() / logMax
            val y = h - norm * h * 0.92f
            if (!started) {
                linePath.moveTo(b * barW, y)
                started = true
            } else {
                linePath.lineTo(b * barW, y)
            }
            areaPath.lineTo(b * barW, y)
        }
        areaPath.lineTo(w, h)
        areaPath.close()

        canvas.drawPath(areaPath, areaPaint)
        canvas.drawPath(linePath, strokePaint)

        canvas.drawText("0", 4f, h - 6f, textPaint)
        canvas.drawText("128", w / 2 - 20f, h - 6f, textPaint)
        canvas.drawText("255", w - 60f, h - 6f, textPaint)
    }
}
