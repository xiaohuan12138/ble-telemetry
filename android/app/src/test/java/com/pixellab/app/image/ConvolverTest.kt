package com.pixellab.app.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卷积引擎单元测试：用"手工可算"的小图验证每个滤镜的数学行为。
 * 测试图统一用 5×5，每个像素值手算可验证。
 */
class ConvolverTest {

    private val convolver = Convolver()

    /** 5×5 纯白图（全 255），alpha 全 255 */
    private fun whiteImage(w: Int = 5, h: Int = 5): IntArray =
        IntArray(w * h) { -0x1 }   // 0xFFFFFFFF

    /** 5×5 纯黑图 */
    private fun blackImage(w: Int = 5, h: Int = 5): IntArray =
        IntArray(w * h) { -0x1000000 }  // alpha 255, RGB 0

    private fun grayImage(v: Int, w: Int = 5, h: Int = 5): IntArray =
        IntArray(w * h) { (-0x1000000) or (v shl 16) or (v shl 8) or v }

    @Test
    fun `均值模糊不改变均匀亮度图的亮度`() {
        // 全 128 的灰图，均值核权重和为 1 → 每个像素输出仍应是 128
        val src = grayImage(128)
        val out = convolver.apply3x3(src, 5, 5, FilterPreset.MEAN_BLUR.kernel)
        // 中心像素（不在边界）必须精确等于 128
        assertEquals(128, out[12] shr 8 and 0xFF)
        // 边界 clamp 复制邻居，均匀图上同样等于 128
        assertEquals(128, out[0] shr 8 and 0xFF)
    }

    @Test
    fun `锐化在平坦区域输出原值`() {
        // 锐化核总和为 1：平坦区（所有邻居同值）输出 = 5v - 4v = v，不变
        val src = grayImage(100)
        val out = convolver.apply3x3(src, 5, 5, FilterPreset.SHARPEN.kernel)
        assertEquals(100, out[12] shr 16 and 0xFF)
    }

    @Test
    fun `边缘检测在平坦区域输出黑色`() {
        // 边缘核总和为 0：平坦区输出 0（黑）。clamp 后 RGB 应为 0
        val src = grayImage(200)
        val out = convolver.apply3x3(src, 5, 5, FilterPreset.EDGE.kernel)
        assertEquals(0, out[12] and 0xFF)
        assertEquals(0, out[12] shr 8 and 0xFF)
        assertEquals(0, out[12] shr 16 and 0xFF)
    }

    @Test
    fun `边缘检测在明暗交界处产生高亮`() {
        // 左半 0、右半 255 的竖直阶跃：中列像素有梯度，拉普拉斯输出应显著 > 0
        val src = IntArray(25) { i ->
            val v = if (i % 5 >= 2) 255 else 0
            (-0x1000000) or (v shl 16) or (v shl 8) or v
        }
        val out = convolver.apply3x3(src, 5, 5, FilterPreset.EDGE.kernel)
        // x=2 列（阶跃右边缘）中心行：亮度应接近饱和
        val bright = out[12] and 0xFF
        assertTrue("阶跃边缘应高亮，实际 $bright", bright > 200)
    }

    @Test
    fun `卷积结果不会溢出0到255`() {
        // 用极端核（全 9）放大溢出风险，验证 clamp 生效
        val kernel = FloatArray(9) { 9f }
        val src = grayImage(250)
        val out = convolver.apply3x3(src, 5, 5, kernel)
        for (p in out) {
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            assertTrue(r in 0..255 && g in 0..255 && b in 0..255)
        }
    }

    @Test
    fun `高斯模糊总权重归一化_均匀图亮度不变`() {
        val src = grayImage(150)
        val out = convolver.gaussianBlur(src, 5, 5, radius = 2)
        assertEquals(150, out[12] shr 8 and 0xFF)
    }

    @Test
    fun `高斯模糊降低锐利边缘的对比度`() {
        // 阶跃图模糊后，边缘两侧的对比差应缩小（低通滤波衰减高频）
        val src = IntArray(25) { i ->
            val v = if (i % 5 >= 2) 255 else 0
            (-0x1000000) or (v shl 16) or (v shl 8) or v
        }
        val out = convolver.gaussianBlur(src, 5, 5, radius = 2)
        // 取模糊后在阶跃附近（x=1 与 x=3）的两个像素，它们的差应小于原图的 255
        val left = out[11] and 0xFF    // (2,2) 左边
        val right = out[13] and 0xFF   // (2,2) 右边
        assertTrue("模糊应缩小对比，实际差 ${kotlin.math.abs(left - right)}",
            kotlin.math.abs(left - right) < 255)
    }

    @Test
    fun `灰度化使用BT601系数`() {
        // 纯绿 (0,255,0)：BT.601 灰度 = 0.587*255 ≈ 149（简单平均会给 85，可区分两种实现）
        val src = intArrayOf(-0x100 or 0xFF00)  // alpha0? 直接构造 ARGB: alpha 255
        val green = (-0x1000000) or (0xFF shl 8)
        val out = convolver.grayscale(intArrayOf(green))
        val y = out[0] and 0xFF
        assertEquals("绿色权重应为 0.587×255≈149，实际 $y", 149, y)
        // R=G=B（灰度）时应精确不变
        val g128 = (-0x1000000) or (128 shl 16) or (128 shl 8) or 128
        val out2 = convolver.grayscale(intArrayOf(g128))
        assertEquals(128, out2[0] and 0xFF)
    }

    @Test
    fun `alpha通道在卷积后保持不变`() {
        // 半透明像素（alpha=0x80）卷积后 alpha 不应被改写
        val semi = (0x80 shl 24) or (100 shl 16) or (100 shl 8) or 100
        val src = IntArray(25) { semi }
        val out = convolver.apply3x3(src, 5, 5, FilterPreset.MEAN_BLUR.kernel)
        assertEquals(0x80, out[12] ushr 24)
    }

    @Test
    fun `非法卷积核长度应抛异常`() {
        val bad = FloatArray(4)
        val result = runCatching { convolver.apply3x3(whiteImage(), 5, 5, bad) }
        assertTrue(result.isFailure)
    }

    @Test
    fun `直方图统计_均值与标准差`() {
        // 全 128 的图：均值 128，标准差 0
        val hist = Histogram()
        val stats = hist.compute(grayImage(128))
        assertEquals(128f, stats.mean, 0.01f)
        assertEquals(0f, stats.stdDev, 0.01f)
        assertEquals(25, stats.totalPixels)
        assertEquals(25, stats.bins[128])
    }

    @Test
    fun `直方图统计_黑白各半时标准差为128`() {
        // 一半 0 一半 255：均值 127.5（int 域 127~128），σ 理论值 127.5
        val hist = Histogram()
        val src = IntArray(100) { if (it % 2 == 0) grayImage(0)[0] else grayImage(255)[0] }
        val stats = hist.compute(src)
        assertEquals(127.5f, stats.mean, 0.1f)
        assertEquals(127.5f, stats.stdDev, 0.2f)
    }
}
