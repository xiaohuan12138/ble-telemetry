package com.pixellab.app.ui.viewer

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

/**
 * 支持捏合缩放 / 双击缩放 / 拖动平移的 ImageView（微信查看大图的手感）。
 *
 * 核心思路：不用 scaleType 的内置缩放，而是把所有变换烘进一个 imageMatrix：
 *   视口坐标 v = 内容坐标 c × scale + translate
 *   - 初始：fitCenter（完整显示图片），此时 scale = fitScale；
 *   - 手势只改 scale / translate 两个数，每帧 clamp() 后写回 imageMatrix；
 *   - clamp：内容小于视口时强制居中，大于视口时把平移限制在"边缘对齐"内，
 *     保证放大后拖不飞、缩回后自动归中。
 *
 * 手势检测复用框架的 ScaleGestureDetector（双指捏合）与
 * GestureDetector（双击），手写的只剩 Matrix 公式和边界逻辑，
 * 每一步都符合"能讲清楚"的要求。
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    /** 当前缩放（视口像素 / 内容像素） */
    private var scale = 1f
    private var transX = 0f
    private var transY = 0f

    /** fitCenter 的基准倍数 = 缩放下限；上限 5×基准 */
    private var fitScale = 1f
    private var doubleTapTarget = 2.5f

    private val matrix = Matrix()

    /** 双指手势的参考中点（用于缩放时的锚点补偿与双指平移） */
    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object :
        ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // 焦点不动：缩放后补偿平移，使手势焦点处的像素保持在原地
            scaleBy(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    private val tapDetector = GestureDetector(context, object :
        GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            // 双击：基准 ↔ 2.5×基准，围绕点击点切换。
            // 已放大 → factor = 1/当前倍率，乘回基准；在基准 → factor = 2.5
            val atFit = scale <= fitScale * 1.01f
            val factor = if (atFit) doubleTapTarget else 1f / (scale / fitScale)
            scaleBy(factor, e.x, e.y)
            return true
        }

        override fun onDown(e: MotionEvent): Boolean = true
    })

    init {
        super.setScaleType(ScaleType.MATRIX)
    }

    /** 视口尺寸或内容变化时，重置回 fitCenter */
    fun resetToFit() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dw = d.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val dh = d.intrinsicHeight.toFloat().coerceAtLeast(1f)
        fitScale = min(vw / dw, vh / dh)
        scale = fitScale
        transX = (vw - dw * scale) / 2f
        transY = (vh - dh * scale) / 2f
        commit()
    }

    override fun setFrame(l: Int, t: Int, r: Int, b: Int): Boolean {
        val changed = super.setFrame(l, t, r, b)
        resetToFit()
        return changed
    }

    /** 以视口点 (fx, fy) 为不动点缩放 s 倍（clamp 到 [fitScale, 5×fitScale]） */
    private fun scaleBy(factor: Float, fx: Float, fy: Float) {
        val d = drawable ?: return
        val oldScale = scale
        val newScale = (oldScale * factor).coerceIn(fitScale, fitScale * 5f)
        val ratio = newScale / oldScale
        // 焦点不动公式：trans' = fx - (fx - trans) × ratio
        transX = fx - (fx - transX) * ratio
        transY = fy - (fy - transY) * ratio
        scale = newScale
        clampAndCommit(d)
    }

    /** 边界钳制 + 写回 imageMatrix */
    private fun clampAndCommit(d: android.graphics.drawable.Drawable) {
        val vw = width.toFloat()
        val vh = height.toFloat()
        val w = d.intrinsicWidth * scale
        val h = d.intrinsicHeight * scale
        // 内容比视口小 → 强制居中；大 → 允许拖到边缘为止
        transX = if (w <= vw) (vw - w) / 2f else transX.coerceIn(vw - w, 0f)
        transY = if (h <= vh) (vh - h) / 2f else transY.coerceIn(vh - h, 0f)
        commit()
    }

    /** 把 scale/translate 烘进 matrix 并触发重绘 */
    private fun commit() {
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(transX, transY)
        imageMatrix = matrix
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val d = drawable ?: return super.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        tapDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    // 单指或双指平移：跟随指针位移（缩放已在 onScale 处理）
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    transX += dx
                    transY += dy
                }
                lastX = event.x
                lastY = event.y
                clampAndCommit(d)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Unit
        }
        return true
    }

    /** 供外部重建内容后调用（换图时归位） */
    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        resetToFit()
    }

    /**
     * 鼠标 / 触控板滚轮缩放：接上鼠标（模拟器、平板、Chromebook）后
     * 滚轮前后滚动即以指针为锚点缩放。AXIS_HSCROLL 是横向滚轮，
     * 同样映射到缩放（方向一致，体验统一）。
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER)
        ) {
            val dy = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val dx = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val factor = Math.pow(1.15, (-dy + dx).toDouble()).toFloat()
            if (factor != 1f) {
                scaleBy(factor, event.x, event.y)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }
}
