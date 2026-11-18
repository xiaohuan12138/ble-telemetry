package com.pixellab.app.image

/**
 * 灰度直方图统计（Histogram）
 *
 * 直方图 = 图像强度分布的概率密度估计：横轴 0~255 灰度级，
 * 纵轴落在该灰度级上的像素数。
 *
 * 用途（也是答辩亮点）：
 *   - 滤镜"处理前/后"的直方图对比，能量化地展示效果：
 *     模糊 → 分布收窄（方差变小）；边缘检测 → 双峰（平坦区黑 + 边缘亮）；
 *     直方图均衡化 → 分布拉开（对比度增大）。
 *   - 统计均值 / 标准差：一图一行数字就能说明"这张图偏亮还是偏暗、对比高还是低"，
 *     这是把"看起来变亮了"变成"均值从 112 提升到 148"的量化叙事。
 */
class Histogram {

    /** 统计结果：256 个 bin 的灰度计数 + 汇总指标 */
    data class Stats(
        val bins: IntArray,       // 长度 256，bins[i] = 灰度值为 i 的像素数
        val mean: Float,          // 均值 μ
        val stdDev: Float,        // 标准差 σ（对比度的度量）
        val totalPixels: Int
    )

    /**
     * 对像素数组统计灰度直方图。
     * 灰度用 BT.601 系数（0.299/0.587/0.114），与 Convolver.grayscale() 保持一致，
     * 保证"显示的灰度图"和"统计的灰度"是同一个定义。
     *
     * 实现是单遍 O(N)：先累加计数，再用计数直接算均值和方差（E[X²]-E[X]²），
     * 不需要第二遍遍历。
     */
    fun compute(pixels: IntArray): Stats {
        val bins = IntArray(256)
        var sum = 0L
        var sumSq = 0L

        for (p in pixels) {
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            // BT.601 灰度，直接在 int 域近似计算（乘 1000 后取整，最后除回去）
            val y = (299 * r + 587 * g + 114 * b) / 1000
            val yy = y.coerceIn(0, 255)
            bins[yy]++
            sum += yy
            sumSq += yy.toLong() * yy
        }

        val n = pixels.size
        val mean = if (n == 0) 0f else sum.toFloat() / n
        // E[X²] - E[X]²（注意 float 精度：用 Long 存中间和避免溢出，最后再转 float）
        val variance = if (n == 0) 0f
        else sumSq.toFloat() / n - mean * mean
        val stdDev = kotlin.math.sqrt(variance.coerceAtLeast(0f))

        return Stats(bins, mean, stdDev, n)
    }
}
