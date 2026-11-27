package com.pixellab.app.image

import kotlin.math.log2

/**
 * 灰度直方图统计（Histogram）
 *
 * 直方图 = 图像强度分布的概率密度估计：横轴 0~255 灰度级，
 * 纵轴落在该灰度级上的像素数。
 *
 * 用途（也是答辩亮点）：
 *   - 滤镜"处理前/后"的直方图对比，能量化地展示效果：
 *     模糊 → 分布收窄（方差变小）；边缘检测 → 双峰（平坦区黑 + 边缘亮）；
 *   - 全套量化指标（μ/σ/极值/中位数/众数/熵/过曝欠曝率/RGB 均值），
 *     把"看起来变亮了"变成"均值从 112 提升到 148"的量化叙事。
 */
class Histogram {

    /** 统计结果：256 个 bin 的灰度计数 + 全套汇总指标 */
    data class Stats(
        val bins: IntArray,        // 长度 256，bins[i] = 灰度值为 i 的像素数
        val mean: Float,           // 均值 μ
        val stdDev: Float,         // 标准差 σ（对比度的度量）
        val totalPixels: Int,
        val minGray: Int,          // 最暗灰度（分布左端点）
        val maxGray: Int,          // 最亮灰度（分布右端点）
        val median: Int,           // 中位数：累积计数过半处的灰度级
        val mode: Int,             // 众数：计数最大的灰度级
        val entropy: Float,        // 信息熵（bit/像素）：分布"散"的程度
        val darkRatio: Float,      // 欠曝率：灰度 <10 的像素占比
        val blowoutRatio: Float,   // 过曝率：灰度 >245 的像素占比
        val meanR: Float,          // R 通道均值（彩色信息，直方图只看灰度会丢）
        val meanG: Float,
        val meanB: Float
    )

    /**
     * 对像素数组统计灰度直方图。
     * 灰度用 BT.601 系数（0.299/0.587/0.114），与 Convolver.grayscale() 保持一致，
     * 保证"显示的灰度图"和"统计的灰度"是同一个定义。
     *
     * 实现：像素域单遍 O(N)（计数 + μ/σ 累加 + RGB 累加 + 极值追踪），
     * 再从 bins 做 O(256) 的后处理（中位数/众数/熵/过曝欠曝率）——
     * 全程不需要第二遍遍历像素。
     */
    fun compute(pixels: IntArray): Stats {
        val bins = IntArray(256)
        var sum = 0L
        var sumSq = 0L
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var minG = 255
        var maxG = 0

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
            sumR += r
            sumG += g
            sumB += b
            if (yy < minG) minG = yy
            if (yy > maxG) maxG = yy
        }

        val n = pixels.size
        val mean = if (n == 0) 0f else sum.toFloat() / n
        // E[X²] - E[X]²（注意 float 精度：用 Long 存中间和避免溢出，最后再转 float）
        val variance = if (n == 0) 0f
        else sumSq.toFloat() / n - mean * mean
        val stdDev = kotlin.math.sqrt(variance.coerceAtLeast(0f))

        // ── 从 bins 后处理（O(256)，与像素数无关） ──
        var median = 0
        var mode = 0
        var modeCount = 0
        var darkCount = 0
        var blowoutCount = 0
        var entropySum = 0.0
        var acc = 0L
        val half = n / 2L
        for (i in 0..255) {
            val c = bins[i]
            // 中位数：累积计数第一次过半（n 为偶数时取下中位，与 int 域 μ 精度一致）
            if (acc < half) { median = i; acc += c }
            if (c > modeCount) { modeCount = c; mode = i }
            if (i < 10) darkCount += c
            if (i > 245) blowoutCount += c
            // 熵：p·log2(p) 按灰度级累加，p=0 的项贡献为 0
            if (c > 0) {
                val p = c.toDouble() / n
                entropySum -= p * log2(p)
            }
        }

        return Stats(
            bins, mean, stdDev, n,
            if (n == 0) 0 else minG,
            if (n == 0) 0 else maxG,
            median, mode,
            entropySum.toFloat(),
            if (n == 0) 0f else darkCount.toFloat() / n,
            if (n == 0) 0f else blowoutCount.toFloat() / n,
            if (n == 0) 0f else sumR.toFloat() / n,
            if (n == 0) 0f else sumG.toFloat() / n,
            if (n == 0) 0f else sumB.toFloat() / n
        )
    }

    /** RGB 三通道直方图结果：三条独立的 256 bin 计数 */
    data class RgbStats(
        val rBins: IntArray,
        val gBins: IntArray,
        val bBins: IntArray
    )

    /** RGB 分通道统计：单遍 O(N)，三个通道各计一个 256 bin 直方图 */
    fun computeRgb(pixels: IntArray): RgbStats {
        val r = IntArray(256)
        val g = IntArray(256)
        val b = IntArray(256)
        for (p in pixels) {
            r[p shr 16 and 0xFF]++
            g[p shr 8 and 0xFF]++
            b[p and 0xFF]++
        }
        return RgbStats(r, g, b)
    }
}
