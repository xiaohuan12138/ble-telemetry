package com.pixellab.app.ui.demo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 卷积滑窗动画演示 View（C 组"课程设计杀手锏"）。
 *
 * 左半边画一张合成的"迷你灰度图"（每个格子显示像素值，灰度做背景色），
 * 右半边画卷积结果；3×3 滑窗在源图上逐格移动，右侧同步高亮结果像素。
 * 计算式（"out = Σ k·v"展开式 + 结果）通过回调交给页面的 TextView 展示。
 *
 * 评委一眼看懂"卷积到底是什么"：窗口内 9 个像素 × 9 个权重 → 加权和 → 一个新像素。
 */
class ConvolutionDemoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 迷你图尺寸：故意做小（12×9），值全部可见，计算过程一目了然 */
    companion object {
        const val COLS = 12
        const val ROWS = 9

        /** 当前演示用的 3×3 核（锐化）——与编辑页 SHARPEN 同款，学以致用 */
        val KERNEL = floatArrayOf(
            0f, -1f, 0f,
            -1f, 5f, -1f,
            0f, -1f, 0f
        )

        /** 合成迷你图：左暗右亮渐变 + 中间一个亮方块 + 右下一个暗点（有结构，卷积效果才看得见） */
        val SOURCE: IntArray = IntArray(COLS * ROWS) { i ->
            val x = i % COLS
            val y = i / COLS
            var v = 40 + (x * 215) / (COLS - 1)          // 水平渐变 40~255
            if (x in 4..6 && y in 3..5) v = 235          // 亮方块（会生成一圈边缘响应）
            if (x in 8..10 && y in 1..2) v = 20          // 暗块（反向边缘）
            v
        }

        /** 全图卷积结果（一次性预计算，右图整体可展示，滑窗只做高亮） */
        val RESULT: IntArray = IntArray(COLS * ROWS) { i ->
            val x = i % COLS
            val y = i / COLS
            convolveAt(x, y)
        }

        /** 单点卷积：边界复制（clamp），与真实引擎同一套边界策略 */
        private fun convolveAt(x: Int, y: Int): Int {
            var sum = 0f
            for (dy in -1..1) for (dx in -1..1) {
                val xx = (x + dx).coerceIn(0, COLS - 1)
                val yy = (y + dy).coerceIn(0, ROWS - 1)
                sum += KERNEL[(dy + 1) * 3 + (dx + 1)] * SOURCE[yy * COLS + xx]
            }
            return sum.toInt().coerceIn(0, 255)
        }
    }

    /** 滑窗当前位置（列,行），边界内移动 */
    var cursorX = 1; private set
    var cursorY = 1; private set

    /** 计算式更新回调（页面用它喂 TextView） */
    var onExpressionChanged: ((String) -> Unit)? = null

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textAlign = Paint.Align.CENTER
    }
    private val valueTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#303F9F")
        textAlign = Paint.Align.CENTER
    }
    private val windowStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.parseColor("#EF5350")
    }
    private val cursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.parseColor("#22C55E")
    }
    private val rect = RectF()

    init {
        notifyExpression()
    }

    /** 滑窗前进一步（行优先扫描），扫完回到起点循环 */
    fun step() {
        cursorX++
        if (cursorX >= COLS - 1) {
            cursorX = 1
            cursorY++
            if (cursorY >= ROWS - 1) cursorY = 1
        }
        invalidate()
        notifyExpression()
    }

    fun reset() {
        cursorX = 1
        cursorY = 1
        invalidate()
        notifyExpression()
    }

    /** 生成当前滑窗的计算式：out(x,y) = Σ k·v，逐项展开（核权重 × 邻居像素值） */
    private fun notifyExpression() {
        val sb = StringBuilder()
        var acc = 0f
        var first = true
        for (dy in -1..1) for (dx in -1..1) {
            val k = KERNEL[(dy + 1) * 3 + (dx + 1)]
            if (k == 0f) continue
            val xx = (cursorX + dx).coerceIn(0, COLS - 1)
            val yy = (cursorY + dy).coerceIn(0, ROWS - 1)
            val v = SOURCE[yy * COLS + xx]
            if (!first) sb.append(if (k < 0) " − " else " + ")
            sb.append("${k.toInt().coerceAtLeast(0) * if (k < 0) -1 else 1}×$v")
            acc += k * v
            first = false
        }
        val out = acc.toInt().coerceIn(0, 255)
        sb.append("  =  $out")
        onExpressionChanged?.invoke("out($cursorX,$cursorY) = $sb")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 单元格大小：左右两块图 + 中缝
        val gap = w * 0.10f
        val cell = min((w - gap) / 2 / COLS, h / (ROWS + 2))
        val boardW = cell * COLS
        val boardH = cell * ROWS
        val leftX = (w / 2 - gap / 2 - boardW) / 1f
        val rightX = w / 2 + gap / 2
        val topY = (h - boardH) / 2 + cell * 0.6f

        textPaint.textSize = cell * 0.30f
        valueTextPaint.textSize = cell * 0.32f
        titlePaint.textSize = cell * 0.44f

        canvas.drawText("原图（灰度值）", leftX + boardW / 2, topY - cell * 0.9f, titlePaint)
        canvas.drawText("卷积结果", rightX + boardW / 2, topY - cell * 0.9f, titlePaint)

        // ── 源图 ──
        for (y in 0 until ROWS) for (x in 0 until COLS) {
            val v = SOURCE[y * COLS + x]
            rect.set(leftX + x * cell, topY + y * cell, leftX + (x + 1) * cell, topY + (y + 1) * cell)
            cellPaint.color = Color.rgb(v, v, v)
            canvas.drawRoundRect(rect, 4f, 4f, cellPaint)
            // 深底白字 / 浅底黑字，保证数值可读
            if (v < 128) {
                valueTextPaint.color = Color.WHITE
                canvas.drawText("$v", rect.centerX(), rect.centerY() + cell * 0.12f, valueTextPaint)
            } else {
                textPaint.color = Color.DKGRAY
                canvas.drawText("$v", rect.centerX(), rect.centerY() + cell * 0.12f, textPaint)
            }
        }

        // ── 结果图 ──
        for (y in 0 until ROWS) for (x in 0 until COLS) {
            val v = RESULT[y * COLS + x]
            rect.set(rightX + x * cell, topY + y * cell, rightX + (x + 1) * cell, topY + (y + 1) * cell)
            cellPaint.color = Color.rgb(v, v, v)
            canvas.drawRoundRect(rect, 4f, 4f, cellPaint)
            if (v < 128) {
                valueTextPaint.color = Color.WHITE
                canvas.drawText("$v", rect.centerX(), rect.centerY() + cell * 0.12f, valueTextPaint)
            } else {
                textPaint.color = Color.DKGRAY
                canvas.drawText("$v", rect.centerX(), rect.centerY() + cell * 0.12f, textPaint)
            }
        }

        // ── 滑窗高亮：源图红色 3×3 框（当前中心加白描边），结果图绿色单格框 ──
        rect.set(leftX + (cursorX - 1) * cell, topY + (cursorY - 1) * cell,
            leftX + (cursorX + 2) * cell, topY + (cursorY + 2) * cell)
        canvas.drawRoundRect(rect, 6f, 6f, windowStroke)
        rect.set(leftX + cursorX * cell, topY + cursorY * cell,
            leftX + (cursorX + 1) * cell, topY + (cursorY + 1) * cell)
        canvas.drawRoundRect(rect, 4f, 4f, valueTextPaint.apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.WHITE })
        valueTextPaint.style = Paint.Style.FILL

        rect.set(rightX + cursorX * cell, topY + cursorY * cell,
            rightX + (cursorX + 1) * cell, topY + (cursorY + 1) * cell)
        canvas.drawRoundRect(rect, 4f, 4f, cursorStroke)
    }
}
