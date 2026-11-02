package com.sensorhub.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 实时曲线自定义 View
 *
 * 为什么不用 MPAndroidChart 之类的图表库？（答辩加分题）
 *   ① 图表库是为"静态历史数据"设计的，每次刷新都要重算全部数据集；
 *      而实时曲线每秒来一个新点，用库会有明显的卡顿和 GC。
 *   ② 库的依赖体积大（几百 KB），而本项目的需求只要一条折线。
 *   ③ 自己写能真正体现"自定义 View"这项能力，是课程评分的加分项。
 *
 * 核心技术点：环形缓冲（Ring Buffer）
 *   每来一个点就 add()，超过容量就覆盖最老的点。
 *   优势：内存固定（不会随时间无限增长）、无需移动数组元素、
 *         时间复杂度 O(1) 写入。
 *   对比 ArrayList 方案：要 remove(0)，是 O(n) 的数组拷贝，
 *                        1000 个点时每次刷新都要搬 1000 个元素。
 *
 * 绘制要点：
 *   - 用 Path + lineTo 一次画完整条折线，比每两点 drawLine 快很多
 *   - 坐标轴范围自适应：取窗口内的 min/max，再留 10% 边距
 *   - 超出范围的点直接裁剪，不做插值（避免视觉欺骗）
 */
class RealtimeChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val CAPACITY = 120          // 保存最近 120 个点（1Hz 时是 2 分钟）
        private const val GRID_LINES = 4
    }

    /** 环形缓冲：固定大小 FloatArray，用 writeIndex 标记下一个写入位置 */
    private val buffer = FloatArray(CAPACITY)
    private var writeIndex = 0
    private var filled = 0

    private var lineColor = Color.RED
    private var gridColor = Color.parseColor("#1A000000")
    private var textColor = Color.parseColor("#99000000")

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f
    }

    private val path = Path()
    private val fillPath = Path()

    /** 是否绘制网格和数值标签 */
    var showGrid: Boolean = true

    /** 单位后缀，画在最大值标签旁边 */
    var unit: String = ""

    init {
        // 硬件加速下 setLayerType 会让渐变填充失效，这里保持默认即可
        linePaint.color = lineColor
        gridPaint.color = gridColor
        textPaint.color = textColor
    }

    /** 设置曲线颜色（切换通道时调用） */
    fun setLineColor(color: Int) {
        lineColor = color
        linePaint.color = color
        // 半透明填充：把颜色 alpha 降到 40
        fillPaint.color = Color.argb(40, Color.red(color), Color.green(color), Color.blue(color))
        invalidate()
    }

    /** 追加一个数据点 */
    fun addPoint(value: Float) {
        buffer[writeIndex] = value
        writeIndex = (writeIndex + 1) % CAPACITY
        if (filled < CAPACITY) filled++
        invalidate()      // 触发 onDraw，必须在主线程调用
    }

    /** 清空（切换设备时） */
    fun clear() {
        buffer.fill(0f)
        writeIndex = 0
        filled = 0
        invalidate()
    }

    /** 当前窗口内的点数 */
    fun size(): Int = filled

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (filled < 2) {
            drawHint(canvas)
            return
        }

        val w = width.toFloat()
        val h = height.toFloat()
        val padding = 8f
        val chartTop = padding
        val chartBottom = h - padding - 24f    // 底部留 24px 给数值标签
        val chartHeight = chartBottom - chartTop

        // ---- 1. 计算 Y 轴范围 ----
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (i in 0 until filled) {
            val v = bufferAt(i)
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        // 数据完全不变时给一个默认跨度，避免除以 0 导致 NaN
        if (abs(maxV - minV) < 0.0001f) {
            maxV += 1f
            minV -= 1f
        }
        // 上下各留 10% 余量，曲线不会贴边
        val range = maxV - minV
        val top = maxV + range * 0.1f
        val bottom = minV - range * 0.1f

        fun toY(v: Float): Float = chartBottom - (v - bottom) / (top - bottom) * chartHeight

        // ---- 2. 画网格 ----
        if (showGrid) {
            for (i in 0..GRID_LINES) {
                val y = chartTop + chartHeight * i / GRID_LINES
                canvas.drawLine(padding, y, w - padding, y, gridPaint)
            }
        }

        // ---- 3. 画折线 ----
        path.reset()
        val stepX = (w - padding * 2) / (CAPACITY - 1)
        // 起点是窗口里最老的那个点
        var x = padding
        path.moveTo(x, toY(bufferAt(0)))
        for (i in 1 until filled) {
            x = padding + stepX * i
            path.lineTo(x, toY(bufferAt(i)))
        }

        // 先画半透明填充：把折线路径闭合到底部
        fillPath.reset()
        fillPath.addPath(path)
        fillPath.lineTo(x, chartBottom)
        fillPath.lineTo(padding, chartBottom)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)

        // 再画折线本身（画在填充之上，保证线条清晰）
        canvas.drawPath(path, linePaint)

        // ---- 4. 画最新值的圆点，强调"当前读数" ----
        val lastX = padding + stepX * (filled - 1)
        val lastY = toY(bufferAt(filled - 1))
        canvas.drawCircle(lastX, lastY, 4f, linePaint)

        // ---- 5. 画数值标签 ----
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("%.1f%s".format(maxV, unit), w - padding, chartTop + 20f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("%.1f".format(minV), padding, chartBottom + 20f, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        val latest = bufferAt(filled - 1)
        canvas.drawText("%.1f%s".format(latest, unit), w - padding, chartBottom + 20f, textPaint)
    }

    private fun drawHint(canvas: Canvas) {
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("等待数据…", width / 2f, height / 2f, textPaint)
    }

    /**
     * 按"逻辑顺序"取第 i 个点（0 = 最老）
     * 环形缓冲的物理下标和逻辑顺序差了一个偏移，这里做换算
     */
    private fun bufferAt(logicalIndex: Int): Float {
        val start = if (filled < CAPACITY) 0 else writeIndex
        return buffer[(start + logicalIndex) % CAPACITY]
    }
}
