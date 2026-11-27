package com.pixellab.app.image

/**
 * 卷积滤镜引擎（Convolution Filter Engine）
 *
 * 这是本项目最核心的自定义业务逻辑模块。它不调任何图像库，而是把一张图当作
 * 一个"二维离散信号"，用卷积核（Kernel）对每个像素做加权求和——这和
 * 《信号与系统》里的一维卷积完全是同一个数学，只是从一维扩展到了二维。
 *
 * 数学定义（离散二维卷积）：
 *   out(x, y) = Σ Σ kernel(i, j) · in(x + i - c, y + j - d)
 *   其中 (c, d) 是核中心（对 3×3 核就是 (1, 1)）
 *
 * 工程上必须处理的两个问题：
 *   1. 边界：核"伸出"图片边缘时怎么办？这里用「边缘复制」（clamp）——
 *      最简单、不产生黑边、视觉上自然，Photoshop 的默认行为也是它。
 *   2. 溢出：RGB 每个通道是 0~255，卷积结果可能超出范围，必须 clamp。
 *
 * 性能设计（为什么不用 getPixel/setPixel）：
 *   Bitmap.getPixel() 每次调用都有 JNI 开销，一张 400 万像素的图要调 800 万次，
 *   实测要好几秒。正确做法是 getPixels() 一次性把整张图搬进 IntArray（纯内存
 *   数组），在数组上做完卷积再 setPixels() 写回——纯数组运算走 Dispatchers.Default
 *   并行化，百万像素级只需几百毫秒。
 */
class Convolver {

    /**
     * 对 ARGB 像素数组应用 3×3 卷积核。
     *
     * @param pixels  长度 = width * height 的 ARGB 像素数组（来自 Bitmap.getPixels()）
     * @param width   图宽
     * @param height  图高
     * @param kernel  长度 9 的卷积核，行优先排列，[-1..1] 坐标对应 [0..8] 下标
     * @return        新的像素数组（不修改入参，方便"处理前/后"对比展示）
     */
    fun apply3x3(pixels: IntArray, width: Int, height: Int, kernel: FloatArray): IntArray {
        require(kernel.size == 9) { "3x3 卷积核长度必须是 9，实际 ${kernel.size}" }
        val out = IntArray(pixels.size)

        // 逐行扫描，y/x 是"当前像素"，对每个像素用核的 9 个权重取邻居加权求和
        for (y in 0 until height) {
            // 预先算好三个行号，clamp 到边界：越界时复制边缘行，避免黑边
            val yUp = (y - 1).coerceIn(0, height - 1)
            val yMid = y
            val yDown = (y + 1).coerceIn(0, height - 1)
            // 行首在数组中的偏移：像素按行主序存储，第 y 行从 y * width 开始
            val rowUp = yUp * width
            val rowMid = yMid * width
            val rowDown = yDown * width

            for (x in 0 until width) {
                val xLeft = (x - 1).coerceIn(0, width - 1)
                val xRight = (x + 1).coerceIn(0, width - 1)

                // 9 个邻居像素（ARGB int），按核的顺序取
                val p0 = pixels[rowUp + xLeft];   val p1 = pixels[rowUp + x];   val p2 = pixels[rowUp + xRight]
                val p3 = pixels[rowMid + xLeft];  val p4 = pixels[rowMid + x];  val p5 = pixels[rowMid + xRight]
                val p6 = pixels[rowDown + xLeft]; val p7 = pixels[rowDown + x]; val p8 = pixels[rowDown + xRight]

                // 逐通道卷积。ARGB int 的位布局：AAAAAAAARRRRRRRRGGGGGGGGBBBBBBBB
                // 对 A（透明度）不做卷积直接保留原值——对不透明图片它永远是 255，
                // 对半透明图卷积 RGB 通道也足够，保 A 是最稳妥的工程选择。
                val r = convolveChannel(kernel, p0, p1, p2, p3, p4, p5, p6, p7, p8) { it shr 16 and 0xFF }
                val g = convolveChannel(kernel, p0, p1, p2, p3, p4, p5, p6, p7, p8) { it shr 8 and 0xFF }
                val b = convolveChannel(kernel, p0, p1, p2, p3, p4, p5, p6, p7, p8) { it and 0xFF }

                val alpha = p4 and 0xFF000000.toInt()
                out[y * width + x] = alpha or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    /**
     * 单通道卷积：9 个邻居各取该通道分量，与对应权重相乘后求和，clamp 到 0~255。
     * @param extract 从 ARGB int 中抽出 0~255 通道值的 lambda
     */
    private inline fun convolveChannel(
        kernel: FloatArray,
        p0: Int, p1: Int, p2: Int, p3: Int, p4: Int, p5: Int, p6: Int, p7: Int, p8: Int,
        extract: (Int) -> Int
    ): Int {
        var sum = 0f
        sum += kernel[0] * extract(p0)
        sum += kernel[1] * extract(p1)
        sum += kernel[2] * extract(p2)
        sum += kernel[3] * extract(p3)
        sum += kernel[4] * extract(p4)
        sum += kernel[5] * extract(p5)
        sum += kernel[6] * extract(p6)
        sum += kernel[7] * extract(p7)
        sum += kernel[8] * extract(p8)
        // 浮点转 int 会向零取整，clamp 保证不越界产生花屏
        return sum.toInt().coerceIn(0, 255)
    }

    /**
     * 高斯模糊（可变半径）。
     *
     * 标准二维高斯核是**可分离**的：G(x,y) = G(x)·G(y)，二维卷积可以拆成
     * 先水平一维、再垂直一维，计算量从 O(N·k²) 降到 O(N·2k)。
     * 半径 r 的模糊用 2r+1 个抽头，大半径时分离优化省一个数量级的计算。
     * 这就是《数字信号处理》里 FIR 滤波的"级联分解"思想在二维的翻版。
     */
    fun gaussianBlur(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        require(radius in 1..10) { "模糊半径限定 1~10，实际 $radius" }
        // 一维高斯系数：中心权重最大、两侧按 exp(-x²/2σ²) 衰减，σ = radius/2
        // 归一化保证总权重为 1，否则整张图会整体变亮或变暗
        val sigma = radius / 2.0f
        val taps = 2 * radius + 1
        val k = FloatArray(taps)
        var norm = 0f
        for (i in 0 until taps) {
            val x = (i - radius).toFloat()
            k[i] = kotlin.math.exp(-(x * x) / (2 * sigma * sigma))
            norm += k[i]
        }
        for (i in 0 until taps) k[i] /= norm

        val tmp = IntArray(pixels.size)   // 水平方向的结果先放这里
        val out = IntArray(pixels.size)

        // Pass 1：水平方向一维卷积
        horizontalPass(pixels, tmp, width, height, k, radius)
        // Pass 2：把 Pass 1 的输出再做垂直方向一维卷积
        verticalPass(tmp, out, width, height, k, radius)
        return out
    }

    private fun horizontalPass(src: IntArray, dst: IntArray, width: Int, height: Int, k: FloatArray, r: Int) {
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var sr = 0f; var sg = 0f; var sb = 0f
                for (i in -r..r) {
                    // clamp 到 [0, width-1]，边缘像素的"外侧邻居"复制边缘
                    val xx = (x + i).coerceIn(0, width - 1)
                    val p = src[row + xx]
                    val w = k[i + r]
                    sr += w * (p shr 16 and 0xFF)
                    sg += w * (p shr 8 and 0xFF)
                    sb += w * (p and 0xFF)
                }
                val alpha = src[row + x] and 0xFF000000.toInt()
                dst[row + x] = alpha or (sr.toInt().coerceIn(0, 255) shl 16) or
                        (sg.toInt().coerceIn(0, 255) shl 8) or sb.toInt().coerceIn(0, 255)
            }
        }
    }

    private fun verticalPass(src: IntArray, dst: IntArray, width: Int, height: Int, k: FloatArray, r: Int) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sr = 0f; var sg = 0f; var sb = 0f
                for (i in -r..r) {
                    val yy = (y + i).coerceIn(0, height - 1)
                    val p = src[yy * width + x]
                    val w = k[i + r]
                    sr += w * (p shr 16 and 0xFF)
                    sg += w * (p shr 8 and 0xFF)
                    sb += w * (p and 0xFF)
                }
                val alpha = src[y * width + x] and 0xFF000000.toInt()
                dst[y * width + x] = alpha or (sr.toInt().coerceIn(0, 255) shl 16) or
                        (sg.toInt().coerceIn(0, 255) shl 8) or sb.toInt().coerceIn(0, 255)
            }
        }
    }

    /**
     * 灰度化（加权平均法）。
     *
     * 人眼对绿色最敏感（约 60% 感知权重）、红色次之、蓝色最低，
     * 所以用 ITU-R BT.601 标准系数 0.299R + 0.587G + 0.114B，
     * 而不是简单 (R+G+B)/3——后者会把绿色偏多的场景压得过暗。
     */
    fun grayscale(pixels: IntArray): IntArray {
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            val y = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
            out[i] = (p and 0xFF000000.toInt()) or (y shl 16) or (y shl 8) or y
        }
        return out
    }

    /**
     * 直方图均衡化（Histogram Equalization）。
     *
     * 经典的点操作增强：把直方图 via 累积分布函数（CDF）重新映射到整个 0~255，
     * 让"占用像素多的灰度段被拉开、占用少的被压缩"，整体对比度提升。
     * 数学：y' = round((CDF(y) - CDFmin) / (N - CDFmin) * 255)
     *
     * 只对亮度（BT.601 Y）做均衡，RGB 通道按新旧亮度比同缩放——
     * 直接对 R/G/B 各自均衡会产生色彩漂移（三通道 CDF 不同步）。
     */
    fun equalize(pixels: IntArray): IntArray {
        val hist = IntArray(256)
        for (p in pixels) {
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            hist[((299 * r + 587 * g + 114 * b) / 1000).coerceIn(0, 255)]++
        }
        // CDF → 映射表。标准公式的 CDFmin = 第一个非零灰度级的 CDF 值（含该级计数），
        // 这样最低灰度级映射到 0，分布才能真正铺满 0~255
        val lut = IntArray(256)
        var cdf = 0
        var cdfMin = -1
        val n = pixels.size
        for (i in 0..255) {
            if (hist[i] == 0) continue
            cdf += hist[i]
            if (cdfMin < 0) cdfMin = cdf
            lut[i] = if (n - cdfMin <= 0) i
            else ((cdf - cdfMin) * 255f / (n - cdfMin)).toInt().coerceIn(0, 255)
        }
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            val y = (299 * r + 587 * g + 114 * b) / 1000
            val yNew = lut[y.coerceIn(0, 255)]
            // RGB 同乘 yNew/y 保持色相：整数域放大 256 倍做定点除
            val ratio = if (y == 0) 256 else (yNew * 256) / y
            val r2 = ((r * ratio) shr 8).coerceIn(0, 255)
            val g2 = ((g * ratio) shr 8).coerceIn(0, 255)
            val b2 = ((b * ratio) shr 8).coerceIn(0, 255)
            out[i] = (p and 0xFF000000.toInt()) or (r2 shl 16) or (g2 shl 8) or b2
        }
        return out
    }

    /**
     * Otsu 自动二值化（大津法）。
     *
     * 遍历全部 256 个候选阈值 t，取"类间方差 σ²_b(t) = w0·w1·(μ0-μ1)²"最大的那个：
     * 直观含义——前景和背景两个灰度群被分得最开、群内最紧的切分点。
     * 复杂度 O(256 + N)，无需任何参数（对比固定阈值的全自动）。
     */
    fun otsuBinarize(pixels: IntArray): IntArray {
        val hist = IntArray(256)
        for (p in pixels) {
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            hist[((299 * r + 587 * g + 114 * b) / 1000).coerceIn(0, 255)]++
        }
        val n = pixels.size
        // 全局均值与累计量：一次前向扫描维护 w0/cum0，μ1 = (μT·N - cum0) / w1
        var sumAll = 0L
        for (i in 0..255) sumAll += hist[i].toLong() * i
        var w0 = 0L
        var sum0 = 0L
        var bestT = 0
        var bestVar = -1.0
        for (t in 0..255) {
            w0 += hist[t]
            if (w0 == 0L) continue
            val w1 = n - w0
            if (w1 == 0L) break
            sum0 += hist[t].toLong() * t
            val mu0 = sum0.toDouble() / w0
            val mu1 = (sumAll - sum0).toDouble() / w1
            val wb = w0.toDouble() / n * w1.toDouble() / n
            val between = wb * (mu0 - mu1) * (mu0 - mu1)
            if (between > bestVar) { bestVar = between; bestT = t }
        }
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            val y = ((299 * r + 587 * g + 114 * b) / 1000).coerceIn(0, 255)
            val v = if (y > bestT) 255 else 0
            out[i] = (p and 0xFF000000.toInt()) or (v shl 16) or (v shl 8) or v
        }
        return out
    }

    /**
     * 中值滤波（3×3）。
     *
     * 非线性滤波：邻域 9 个值排序取中位数，而不是加权平均。
     * 对"椒盐噪声"（孤立的极亮/极暗点）几乎完美去除——中位数直接把离群值排掉；
     * 而线性滤波（均值/高斯）会把噪声点摊开成一片灰斑。
     * 9 个元素用插入排序取中位：n 极小，插入排序比快排常数更小。
     */
    fun medianFilter(pixels: IntArray, width: Int, height: Int): IntArray {
        val out = IntArray(pixels.size)
        // 三个通道的滑动窗口在行循环外预分配：整个滤波过程零临时对象
        val vr = IntArray(9)
        val vg = IntArray(9)
        val vb = IntArray(9)
        for (y in 0 until height) {
            val yUp = (y - 1).coerceIn(0, height - 1)
            val yDown = (y + 1).coerceIn(0, height - 1)
            val rows = intArrayOf(yUp, y, yDown)   // 每行建一次，不进像素循环
            for (x in 0 until width) {
                val xLeft = (x - 1).coerceIn(0, width - 1)
                val xRight = (x + 1).coerceIn(0, width - 1)
                // 收集 3×3 邻域的 RGB 分量到各自窗口（9 邻居手工展开，零临时数组）
                var idx = 0
                for (yy in rows) {
                    val row = yy * width
                    vr[idx] = pixels[row + xLeft] shr 16 and 0xFF
                    vg[idx] = pixels[row + xLeft] shr 8 and 0xFF
                    vb[idx] = pixels[row + xLeft] and 0xFF
                    idx++
                    vr[idx] = pixels[row + x] shr 16 and 0xFF
                    vg[idx] = pixels[row + x] shr 8 and 0xFF
                    vb[idx] = pixels[row + x] and 0xFF
                    idx++
                    vr[idx] = pixels[row + xRight] shr 16 and 0xFF
                    vg[idx] = pixels[row + xRight] shr 8 and 0xFF
                    vb[idx] = pixels[row + xRight] and 0xFF
                    idx++
                }
                sort9(vr); sort9(vg); sort9(vb)
                val alpha = pixels[y * width + x] and 0xFF000000.toInt()
                out[y * width + x] = alpha or (vr[4] shl 16) or (vg[4] shl 8) or vb[4]
            }
        }
        return out
    }

    /** 9 元素插入排序（原地）。n 极小，插入排序比快排常数更小；中位即下标 4 */
    private fun sort9(v: IntArray) {
        for (i in 1..8) {
            val key = v[i]
            var j = i - 1
            while (j >= 0 && v[j] > key) { v[j + 1] = v[j]; j-- }
            v[j + 1] = key
        }
    }

    /**
     * 亮度 / 对比度：线性点操作 y = a·x + b（a 对比度增益，b 亮度偏移）。
     *
     * 用 256 项查找表（LUT）预计算映射，逐像素只查表不乘加——
     * 点操作与位置无关（不像卷积要邻居），所以一张表全图复用。
     * a=1、b=0 时是恒等映射，直接原样返回。
     */
    fun adjust(pixels: IntArray, brightness: Int, contrast: Int): IntArray {
        if (brightness == 0 && contrast == 0) return pixels   // 恒等：零成本直通
        // 对比度滑块 0~100 → 增益 0.5~1.5（50 即 ±50%）；亮度滑块 -100~100 直接做偏移
        val a = 1f + contrast / 100f
        val b = brightness
        val lut = IntArray(256) { i ->
            (a * (i - 128) + 128 + b).toInt().coerceIn(0, 255)   // 围绕中灰 128 缩放，避免整体漂移
        }
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = lut[p shr 16 and 0xFF]
            val g = lut[p shr 8 and 0xFF]
            val b2 = lut[p and 0xFF]
            out[i] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b2
        }
        return out
    }
}
