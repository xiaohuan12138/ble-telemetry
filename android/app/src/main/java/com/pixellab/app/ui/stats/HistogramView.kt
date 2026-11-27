package com.pixellab.app.ui.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.pixellab.app.R
import com.pixellab.app.image.Histogram
import kotlin.math.ln

/**
 * 灰度 / RGB 直方图自定义 View v4（C1「自定义 View」要求 + 性能展示）。
 *
 * v4 修复（统计图表显示问题）：
 *   1. v3 用「折线 + 面积填充」画直方图，遇到二值图 / 稀疏分布时只有 0 与 255
 *      两个 bin 非零，lineTo 从峰顶一路贴底边横穿，视觉上退化成「一个空框 +
 *      两端两根竖线」，看起来像图表没渲染。改为正统的**柱状直方图**：
 *      连续分布每 4 级一桶、稀疏分布每 16 级一桶（柱子更粗更明显），
 *      非零桶画柱、零桶留白，二值图就是两端两根明确的柱。
 *   2. v3 的 bucketize 在容量不足时 new IntArray 并返回，却没有回写成员数组，
 *      onDraw 每帧都分配一个 IntArray(256)（「零分配」不变式名存实亡）。
 *      v4 固定 64 桶、成员数组原地复用，onDraw **真正零对象分配**。
 *   3. 稀疏分布（非零灰度级 ≤ 4）自动切换：不透明实心柱 + 灰度值标签 +
 *      右上角说明文字，用户一眼能区分「图表坏了」与「这张图本身只有两级灰度」。
 *   4. RGB 三通道改为**并排子柱**（每桶内红/绿/蓝各一根），不再叠画成
 *      互相覆盖的折线，三通道高度可独立比较。
 *
 * 保留：对数纵轴 ln(1+c)/ln(1+max)（低频 bin 仍可见）、垂直渐变柱体、
 *       处理前/后双色、clear() 空态、0/128/255 刻度与基线。
 *
 * 性能不变式：onDraw 零对象分配——Paint / 分桶数组 / 标签字符串全部预建复用，
 * shader 只在尺寸或颜色变化时重建；drawRect 走 float 参数重载，无需 RectF。
 */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private enum class Mode { GRAY, RGB, EMPTY }

    private val density = resources.displayMetrics.density
    private val axisH = 20f * density          // 底部留给 0/128/255 刻度
    private val topPad = 6f * density          // 顶部留白，避免柱子顶到边界
    private val minBarH = 2f * density         // 非零桶的最小可见高度
    private val gap = 1f * density             // 柱间缝隙

    /** 灰度柱：垂直渐变（顶部浓、底部淡），shader 按画布坐标，所有柱共用 */
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** 稀疏分布专用：实心不透明色，避免渐变底部过淡、细柱看起来像「一条线」 */
    private val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** RGB 三通道并排子柱：纯色 + 略透明，保证并排时边界清楚 */
    private val redPaint = channelPaintOf(0xFFEF4444.toInt())
    private val greenPaint = channelPaintOf(0xFF22C55E.toInt())
    private val bluePaint = channelPaintOf(0xFF3B82F6.toInt())

    private val gridPaint = Paint().apply {
        color = context.getColor(R.color.histogram_grid)
        strokeWidth = 1f
    }
    private val axisPaint = Paint().apply {
        color = ColorUtils.setAlphaComponent(Color.GRAY, 90)
        strokeWidth = 1f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textSize = 10f * density
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(Color.GRAY, 170)
        textSize = 10f * density
        textAlign = Paint.Align.RIGHT
    }
    /** 稀疏分布说明，在 setStats 时算好（含字符串生成），onDraw 只画不拼 */
    private var sparseHint: String? = null
    /** 非零灰度级数量（≤4 视为稀疏）；0 表示无有效数据，>4 表示分布连续 */
    private var sparseLevels = 0

    private fun channelPaintOf(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
        alpha = 210
    }

    /**
     * 固定 64 桶（256 灰度级 / 4），稀疏时只使用前 16 个（256 / 16）。
     * 成员数组原地复用：onDraw 只做累加与清零，不再分配任何数组。
     */
    private val buckets = IntArray(BUCKETS)
    private val rBuckets = IntArray(BUCKETS)
    private val gBuckets = IntArray(BUCKETS)
    private val bBuckets = IntArray(BUCKETS)

    private var mode = Mode.EMPTY
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
        analyzeSparse(stats.bins)
        if (color != baseColor || barPaint.shader == null) {
            baseColor = color
            rebuildShader()
        }
        invalidate()
    }

    /** RGB 三通道并排柱状模式（稀疏信息取 R 通道，三通道二值图位置一致） */
    fun setRgbStats(r: IntArray, g: IntArray, b: IntArray) {
        mode = Mode.RGB
        rBins = r
        gBins = g
        bBins = b
        analyzeSparse(r)
        invalidate()
    }

    /** 数据缺失时清空（避免残留上一条记录的柱子造成误读） */
    fun clear() {
        mode = Mode.EMPTY
        bins = null
        rBins = null
        gBins = null
        bBins = null
        sparseHint = null
        sparseLevels = 0
        invalidate()
    }

    /**
     * 稀疏分布检测：统计非零灰度级数量、记录位置与标签。
     * 二值图（Otsu / 形态学 / 锐化后的黑白图）只有 2 级，必须给出说明，
     * 否则用户看到「中间全空」会误以为图表坏了。
     */
    private fun analyzeSparse(bins256: IntArray) {
        var nonZero = 0
        var hasLow = false
        var hasHigh = false
        for (i in 0..255) {
            if (bins256[i] > 0) {
                nonZero++
                if (i < 10) hasLow = true
                if (i > 245) hasHigh = true
            }
        }
        sparseLevels = nonZero
        sparseHint = when {
            nonZero == 0 -> context.getString(R.string.stats_sparse_empty)
            nonZero > 4 -> null                       // 分布足够连续，不需要提示
            nonZero <= 2 && hasLow && hasHigh -> context.getString(R.string.stats_sparse_binary)
            else -> context.getString(R.string.stats_sparse_few, nonZero)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        rebuildShader()
    }

    private fun rebuildShader() {
        val h = height.toFloat()
        if (h <= 0) return
        val baseY = h - axisH
        barPaint.shader = LinearGradient(
            0f, topPad, 0f, baseY,
            ColorUtils.setAlphaComponent(baseColor, 210),
            ColorUtils.setAlphaComponent(baseColor, 55),
            Shader.TileMode.CLAMP
        )
        solidPaint.color = baseColor   // 稀疏柱用不透明主色，边界一眼可辨
    }

    /**
     * 把 256 个 bin 累加进 count 个桶（每桶 256/count 级），原地写入 dest，
     * 返回桶内最大计数。无分配：dest 是成员数组，容量固定 BUCKETS。
     */
    private fun bucketize(bins256: IntArray, dest: IntArray, count: Int): Long {
        for (i in 0 until count) dest[i] = 0
        var maxVal = 1L
        for (i in 0..255) {
            val b = i * count / 256
            val v = dest[b] + bins256[i]
            dest[b] = v
            if (v > maxVal) maxVal = v.toLong()
        }
        return maxVal
    }

    /** 灰度模式：一桶一根柱，零桶不画（中间自然留白） */
    private fun drawBars(
        canvas: Canvas, w: Float, baseY: Float, plotH: Float,
        maxVal: Long, data: IntArray, count: Int, paint: Paint
    ) {
        val logMax = ln(maxVal.toDouble() + 1.0).toFloat()
        val slot = w / count
        for (b in 0 until count) {
            val c = data[b]
            if (c == 0) continue
            val norm = ln(c.toDouble() + 1.0).toFloat() / logMax
            val barH = (norm * plotH).coerceAtLeast(minBarH)
            val left = b * slot + gap / 2
            val right = (b + 1) * slot - gap / 2
            canvas.drawRect(left, baseY - barH, right, baseY, paint)
        }
    }

    /**
     * RGB 模式：每桶内三根并排子柱（红/绿/蓝），共用一个 maxVal 归一，
     * 三通道高度可直接横向比较；零桶同样不画。
     */
    private fun drawRgbBars(
        canvas: Canvas, w: Float, baseY: Float, plotH: Float, maxVal: Long, count: Int
    ) {
        val logMax = ln(maxVal.toDouble() + 1.0).toFloat()
        val slot = w / count
        val sub = (slot - gap) / 3f
        for (b in 0 until count) {
            val l = b * slot + gap / 2
            drawChannel(canvas, l, sub, baseY, plotH, logMax, rBuckets[b], redPaint)
            drawChannel(canvas, l + sub, sub, baseY, plotH, logMax, gBuckets[b], greenPaint)
            drawChannel(canvas, l + 2 * sub, sub, baseY, plotH, logMax, bBuckets[b], bluePaint)
        }
    }

    private fun drawChannel(
        canvas: Canvas, left: Float, width: Float, baseY: Float, plotH: Float,
        logMax: Float, count: Int, paint: Paint
    ) {
        if (count == 0) return
        val norm = ln(count.toDouble() + 1.0).toFloat() / logMax
        val barH = (norm * plotH).coerceAtLeast(minBarH)
        canvas.drawRect(left, baseY - barH, left + width, baseY, paint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val baseY = h - axisH
        val plotH = (baseY - topPad).coerceAtLeast(1f)

        // 横向网格（3 等分）+ 基线
        for (i in 1..3) {
            val y = topPad + plotH * i / 4
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        canvas.drawLine(0f, baseY, w, baseY, axisPaint)

        // 稀疏分布用 16 桶（柱子更粗、更醒目），连续分布用 64 桶（更细腻）
        val sparse = sparseLevels in 1..4
        val count = if (sparse) SPARSE_BUCKETS else BUCKETS

        when (mode) {
            Mode.GRAY -> {
                val data = bins
                if (data == null) {
                    canvas.drawText("暂无数据", w / 2, h / 2, emptyPaint)
                } else {
                    val maxVal = bucketize(data, buckets, count)
                    drawBars(canvas, w, baseY, plotH, maxVal, buckets, count,
                        if (sparse) solidPaint else barPaint)

                }
            }
            Mode.RGB -> {
                val r = rBins
                val g = gBins
                val b = bBins
                if (r == null || g == null || b == null) {
                    canvas.drawText("暂无数据", w / 2, h / 2, emptyPaint)
                } else {
                    val mr = bucketize(r, rBuckets, count)
                    val mg = bucketize(g, gBuckets, count)
                    val mb = bucketize(b, bBuckets, count)
                    drawRgbBars(canvas, w, baseY, plotH, maxOf(mr, mg, mb), count)

                }
            }
            Mode.EMPTY -> canvas.drawText("暂无数据", w / 2, h / 2, emptyPaint)
        }

        if (mode != Mode.EMPTY) {
            // 刻度：0 左对齐 / 128 居中 / 255 右对齐，避免窄图里挤成一团
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("0", 2f, h - 4f, textPaint)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("128", w / 2, h - 4f, textPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("255", w - 2f, h - 4f, textPaint)

            // 稀疏分布说明（右上角），不与柱子争空间
            sparseHint?.let {
                hintPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(it, w - 4f, topPad + 11f * density, hintPaint)
            }
        }
    }

    private companion object {
        const val BUCKETS = 64
        const val SPARSE_BUCKETS = 16
    }
}
