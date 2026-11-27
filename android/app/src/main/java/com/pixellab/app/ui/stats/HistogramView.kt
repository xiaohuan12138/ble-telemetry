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
 *      和下方指标表的列色一一对应；
 *   4. RGB 三通道叠画模式：红/绿/蓝三条对数曲线，直观看色彩分布。
 *
 * 性能不变式：onDraw 零对象分配——Paint/Path/分桶数组全部预建复用，
 * shader 只在尺寸或颜色变化时重建。
 */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private enum class Mode { GRAY, RGB }

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

    /** RGB 三通道专用描边（半透明叠画，重叠处自然混色） */
    private val redPaint = strokePaintOf(0xFFEF4444.toInt())
    private val greenPaint = strokePaintOf(0xFF22C55E.toInt())
    private val bluePaint = strokePaintOf(0xFF3B82F6.toInt())

    private fun strokePaintOf(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        alpha = 200
        this.color = color
    }

    /** 面积填充（闭合）与顶部轮廓（不闭合）复用同一对象，RGB 模式三条各一个 */
    private val areaPath = Path()
    private val linePath = Path()
    private val rPath = Path()
    private val gPath = Path()
    private val bPath = Path()

    /** onDraw 用的分桶数组：成员复用，容量不够才重建（兑现"零分配"注释） */
    private var buckets = IntArray(64)

    private var mode = Mode.GRAY
    private var bins: IntArray? = null
    private var rBins: IntArray? = null
    private var gBins: IntArray? = null
    private var bBins: IntArray? = null
    private var baseColor: Int = context.getColor(R.color.histogram_bar)

    fun setStats(stats: Histogram.Stats) = setStats(stats, baseColor)

    /** 换数据或换主题色时调用；shader 在这里重建而不是 onDraw */
    fun setStats(stats: Histogram.Stats, color: Int) {
        mode = Mode.GRAY
        bins = stats.bins
        if (color != baseColor || areaPaint.shader == null) {
            baseColor = color
            rebuildShader()
        }
        invalidate()
    }

    /** RGB 三通道叠画模式 */
    fun setRgbStats(r: IntArray, g: IntArray, b: IntArray) {
        mode = Mode.RGB
        rBins = r
        gBins = g
        bBins = b
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

    /**
     * 把 256 个 bin 按屏宽分桶进成员数组 buckets（无分配），返回桶内最大计数。
     * 每桶取累加计数；对数归一在绘制侧做。
     */
    private fun bucketize(bins256: IntArray, bucketCount: Int): Long {
        if (buckets.size < bucketCount) buckets = IntArray(bucketCount)
        var maxVal = 1L
        val perBucket = 256f / bucketCount
        for (i in 0 until bucketCount) buckets[i] = 0
        for (i in 0 until 256) {
            val b = (i / perBucket).toInt().coerceIn(0, bucketCount - 1)
            buckets[b] += bins256[i]
            if (buckets[b] > maxVal) maxVal = buckets[b].toLong()
        }
        return maxVal
    }

    /** 按成员 buckets 画"面积+轮廓"或"纯轮廓"，path 复用（rewind 而非 new） */
    private fun drawCurve(canvas: Canvas, w: Float, h: Float, maxVal: Long, path: Path, stroke: Paint) {
        val logMax = ln(maxVal.toDouble() + 1.0).toFloat()
        val barW = w / buckets.size.coerceAtLeast(1)
        path.rewind()
        var started = false
        for (b in buckets.indices) {
            if (buckets[b] == 0 && !started) continue
            val norm = ln(buckets[b].toDouble() + 1.0).toFloat() / logMax
            val y = h - norm * h * 0.92f
            if (!started) {
                path.moveTo(b * barW, y)
                started = true
            } else {
                path.lineTo(b * barW, y)
            }
        }
        canvas.drawPath(path, stroke)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        for (i in 1..3) {
            val y = h * i / 4
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        val bucketCount = w.toInt().coerceIn(32, 256)

        when (mode) {
            Mode.GRAY -> {
                val data = bins ?: return
                val maxVal = bucketize(data, bucketCount)
                buildAreaAndLine(w, h, maxVal)
                canvas.drawPath(areaPath, areaPaint)
                canvas.drawPath(linePath, strokePaint)
            }
            Mode.RGB -> {
                val r = rBins ?: return
                val g = gBins ?: return
                val b = bBins ?: return
                // 三条曲线共用同一个最大值归一，彼此高度可比
                var maxVal = bucketize(r, bucketCount)
                maxVal = maxOf(maxVal, bucketize(g, bucketCount))
                maxVal = maxOf(maxVal, bucketize(b, bucketCount))
                drawCurve(canvas, w, h, maxVal, rPath, redPaint)
                drawCurve(canvas, w, h, maxVal, gPath, greenPaint)
                drawCurve(canvas, w, h, maxVal, bPath, bluePaint)
            }
        }

        canvas.drawText("0", 4f, h - 6f, textPaint)
        canvas.drawText("128", w / 2 - 20f, h - 6f, textPaint)
        canvas.drawText("255", w - 60f, h - 6f, textPaint)
    }

    /** 灰度模式：面积（闭合到底边）与轮廓两条 Path，逻辑同 v2 */
    private fun buildAreaAndLine(w: Float, h: Float, maxVal: Long) {
        val logMax = ln(maxVal.toDouble() + 1.0).toFloat()
        val barW = w / buckets.size.coerceAtLeast(1)
        areaPath.rewind()
        linePath.rewind()
        areaPath.moveTo(0f, h)
        var started = false
        for (b in buckets.indices) {
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
    }
}
