package com.pixellab.app.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.pixellab.app.R

/**
 * 前后对比滑块（Compare Slider）：修图 App 的经典交互，自绘实现。
 *
 * 为什么不用"两层 ImageView + 改容器宽度裁剪"：
 *   容器宽度变化会让上层 ImageView 的 fitCenter 基准跟着变，和底层错位。
 * 这里统一走 onDraw + canvas.clipRect：
 *   1. 两张 Bitmap 用同一个 Matrix.setRectToRect(..., ScaleToFit.CENTER)
 *      映射到同一块 View 矩形 = 像素级对齐（等价于 fitCenter）；
 *   2. 先整张画 before，再 clipRect(0..split) 只重画左边为 after，
 *      于是分割线左右天然是处理后 / 处理前；
 *   3. 分割线与把手同帧自绘，零 requestLayout，拖动 60fps 无抖动。
 * 手势冲突：水平位移超过 touchSlop 且大于垂直位移才拦截，
 * 否则放行给外层 NestedScrollView 正常滚动。
 */
class CompareSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3 * resources.displayMetrics.density
    }
    private val knobFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val knobRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.primary)
        style = Paint.Style.STROKE
        strokeWidth = 3 * resources.displayMetrics.density
    }

    /** 复用同一个 Matrix 对象：onDraw 零对象分配 */
    private val bitmapMatrix = Matrix()
    private val srcRect = RectF()
    private val dstRect = RectF()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val knobRadius = 15 * resources.displayMetrics.density

    private var before: Bitmap? = null
    private var after: Bitmap? = null
    private var fraction = 0.5f
    private var downX = 0f
    private var downY = 0f
    private var tracking = false

    fun setImages(beforeBmp: Bitmap?, afterBmp: Bitmap?) {
        before = beforeBmp
        after = afterBmp
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = before ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        dstRect.set(0f, 0f, w, h)

        // 底层：处理前，整张 fitCenter
        canvas.drawBitmap(b, fitMatrix(b, w, h), bitmapPaint)

        // 上层：处理后，只画分割线左侧
        val split = fraction * w
        canvas.save()
        canvas.clipRect(0f, 0f, split, h)
        canvas.drawBitmap(after ?: b, fitMatrix(after ?: b, w, h), bitmapPaint)
        canvas.restore()

        // 分割线 + 圆形把手
        canvas.drawLine(split, 0f, split, h, dividerPaint)
        canvas.drawCircle(split, h / 2f, knobRadius, knobFill)
        canvas.drawCircle(split, h / 2f, knobRadius, knobRing)
    }

    /** ScaleToFit.CENTER == ImageView.ScaleType.FIT_CENTER，两张图共用同一映射 */
    private fun fitMatrix(bmp: Bitmap, w: Float, h: Float): Matrix {
        srcRect.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        dstRect.set(0f, 0f, w, h)
        bitmapMatrix.setRectToRect(srcRect, dstRect, Matrix.ScaleToFit.CENTER)
        return bitmapMatrix
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                tracking = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(event.x - downX)
                val dy = Math.abs(event.y - downY)
                if (dx > touchSlop && dx > dy) {
                    tracking = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (tracking) {
                    fraction = (event.x / width).coerceIn(0.02f, 0.98f)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) {
                    // 轻点也把分割线移到点击处，符合直觉
                    fraction = (event.x / width).coerceIn(0.02f, 0.98f)
                    invalidate()
                }
                tracking = false
            }
            MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return true
    }
}
