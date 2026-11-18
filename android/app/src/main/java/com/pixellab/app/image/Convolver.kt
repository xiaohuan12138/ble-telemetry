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
}
