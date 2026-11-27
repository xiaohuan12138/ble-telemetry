package com.pixellab.app.image

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 高级滤波引擎（Advanced Filters）—— A 组"硬核算法"集合。
 *
 * 相比 [Convolver] 的"单算子"，这里把完整算法链做深，全部手写、零第三方图像库：
 *
 *  1. Canny 边缘     —— 高斯降噪 → Sobel 梯度 → 非极大值抑制 → 双阈值滞回连接
 *  2. 双边滤波       —— 高斯空域核 × 值域权重 exp(-(Δgray)²/2σr²)，保边去噪
 *  3. CLAHE          —— 分 tile 直方图均衡 + 裁剪限幅 + 四邻 tile 双线性插值合并
 *  4. 形态学         —— 二值图腐蚀/膨胀/开/闭（接 Otsu 之后做连通域修复）
 *  5. USM 锐化       —— 原图 + (原图 − 高斯模糊) × 系数
 *  6. 插值缩放       —— 最近邻 / 双线性 / 双三次 2× 放大（锯齿直观对比）
 *  7. 颜色空间       —— RGB↔HSV（色相旋转/饱和度）、RGB↔YCbCr（色度偏移）
 *  8. DCT 压缩演示   —— 8×8 DCT + JPEG 量化表，看块效应、讲 JPEG 原理
 *
 * 性能设计：行范围变体（y0..y1）供 [ProcessEngine] 多线程分块并行调用；
 * 全程 IntArray/FloatArray 域运算，不做逐像素 JNI。
 */
class AdvancedFilters(private val convolver: Convolver) {

    // ══════════════════════════ 1. Canny 边缘 ══════════════════════════

    /**
     * Canny 边缘检测（完整四步算法链）。
     *
     * @param lowT  低阈值：弱边缘准入（滞回连接的起点）
     * @param highT 高阈值：强边缘确认（梯度 ≥ highT 直接保留）
     */
    fun canny(pixels: IntArray, width: Int, height: Int, lowT: Int = 30, highT: Int = 90): IntArray {
        val n = pixels.size
        // ① 灰度 + 高斯降噪（σ≈1.4 的 5×5 近似核；Canny 对噪声极敏感，降噪是第一步）
        val gray = FloatArray(n)
        for (i in 0 until n) {
            val p = pixels[i]
            gray[i] = 0.299f * (p shr 16 and 0xFF) + 0.587f * (p shr 8 and 0xFF) + 0.114f * (p and 0xFF)
        }
        val smooth = gaussianGray(gray, width, height, 1.4f)

        // ② Sobel 梯度：gx/gy 一阶差分 → 幅值与方向
        val mag = FloatArray(n)
        val dir = FloatArray(n)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                // 3×3 邻域（已保证在界内）
                val tl = smooth[i - width - 1]; val tc = smooth[i - width]; val tr = smooth[i - width + 1]
                val ml = smooth[i - 1];         val mr = smooth[i + 1]
                val bl = smooth[i + width - 1]; val bc = smooth[i + width]; val br = smooth[i + width + 1]
                val gx = -tl - 2f * ml - bl + tr + 2f * mr + br
                val gy = -tl - 2f * tc - tr + bl + 2f * bc + br
                mag[i] = sqrt(gx * gx + gy * gy)
                // 方向 ∈ [0, π)，转为角度制供 NMS 分扇区用
                var d = Math.toDegrees(kotlin.math.atan2(gy.toDouble(), gx.toDouble())).toFloat()
                if (d < 0) d += 180f
                dir[i] = d
            }
        }

        // ③ 非极大值抑制（NMS）：沿梯度方向只保留局部最大，把"粗边"细化成单像素
        val nms = FloatArray(n)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val d = dir[i]
                // 梯度方向量化到 4 个扇区（0°/45°/90°/135°），对应两组比较邻居
                val (a, b) = when {
                    d < 22.5f || d >= 157.5f -> i - 1 to i + 1                 // 水平
                    d < 67.5f -> i - width + 1 to i + width - 1               // 45°（右下↔左上）
                    d < 112.5f -> i - width to i + width                      // 垂直
                    else -> i - width - 1 to i + width + 1                     // 135°
                }
                nms[i] = if (mag[i] >= mag[a] && mag[i] >= mag[b]) mag[i] else 0f
            }
        }

        // ④ 双阈值 + 滞回连接：强边缘直接保留，弱边缘只有连通到强边缘才算
        val out = IntArray(n)
        val stack = IntArray(n)          // 强边缘种子栈（int 域 BFS，避免 kotlin Array 装箱）
        var sp = 0
        for (i in 0 until n) {
            if (nms[i] >= highT) {
                out[i] = 255
                stack[sp++] = i
            }
        }
        val tLow = lowT.toFloat()
        while (sp > 0) {
            val i = stack[--sp]
            val y = i / width
            val x = i % width
            // 8 邻域扩散：把与强边缘连通的弱边缘"长"出来
            for (dy in -1..1) for (dx in -1..1) {
                if (dy == 0 && dx == 0) continue
                val yy = y + dy
                val xx = x + dx
                if (yy < 0 || yy >= height || xx < 0 || xx >= width) continue
                val j = yy * width + xx
                if (out[j] == 0 && nms[j] >= tLow) {
                    out[j] = 255
                    if (sp < n) stack[sp++] = j
                }
            }
        }
        // 非（强∪连通弱）全部压黑；alpha 保留原值
        for (i in 0 until n) {
            val g = out[i].coerceIn(0, 255)   // out[i] ∈ {0,255}，直接当灰度用
            out[i] = (pixels[i] and 0xFF000000.toInt()) or (g shl 16) or (g shl 8) or g
        }
        return out
    }

    /** 灰度域高斯模糊（FloatArray 版，供 Canny 第一步用）：可分离两趟，σ 直接给定 */
    private fun gaussianGray(src: FloatArray, width: Int, height: Int, sigma: Float): FloatArray {
        val r = 2
        val taps = 2 * r + 1
        val k = FloatArray(taps)
        var norm = 0f
        for (i in 0 until taps) {
            val x = (i - r).toFloat()
            k[i] = exp(-(x * x) / (2f * sigma * sigma))
            norm += k[i]
        }
        for (i in 0 until taps) k[i] /= norm
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        // 水平
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var s = 0f
                for (i in -r..r) s += k[i + r] * src[row + (x + i).coerceIn(0, width - 1)]
                tmp[row + x] = s
            }
        }
        // 垂直
        for (y in 0 until height) {
            for (x in 0 until width) {
                var s = 0f
                for (i in -r..r) s += k[i + r] * tmp[(y + i).coerceIn(0, height - 1) * width + x]
                out[y * width + x] = s
            }
        }
        return out
    }

    // ══════════════════════════ 2. 双边滤波 ══════════════════════════

    /**
     * 双边滤波（行范围版，写进调用方给的 dst，供多线程分块并行）。
     *
     * 权重 = 空域高斯（离得近权重高）× 值域高斯 exp(-(Δgray)²/2σr²)（灰度接近权重高）。
     * 值域项让"跨边缘"的像素几乎不参与平均 → 平坦区被磨平（去噪）、边缘被保住（不糊）。
     * 这是它和高斯模糊的本质区别：高斯只看距离，双边还看"长得像不像"。
     */
    fun bilateralRange(
        src: IntArray, dst: IntArray, width: Int, height: Int,
        y0: Int, y1: Int, radius: Int = 2, sigmaR: Float = 30f
    ) {
        // 空域权重只和 (dx,dy) 有关，行循环外预计算；值域权重逐像素现算
        val size = 2 * radius + 1
        val spatial = FloatArray(size * size)
        var sNorm = 0f
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val w = exp(-((dx * dx + dy * dy).toFloat()) / (2f * radius * radius))
            spatial[(dy + radius) * size + (dx + radius)] = w
            sNorm += w
        }
        val inv2sr2 = 1f / (2f * sigmaR * sigmaR)

        for (y in y0 until y1) {
            for (x in 0 until width) {
                val ci = y * width + x
                val cp = src[ci]
                val cy = grayOf(cp)
                var wr = 0f; var wg = 0f; var wb = 0f; var wSum = 0f
                for (dy in -radius..radius) {
                    val yy = (y + dy).coerceIn(0, height - 1)
                    val row = yy * width
                    for (dx in -radius..radius) {
                        val xx = (x + dx).coerceIn(0, width - 1)
                        val p = src[row + xx]
                        val sw = spatial[(dy + radius) * size + (dx + radius)]
                        // 值域权重：中心与邻居的灰度差越大 → 权重越小（边缘被"挡住"）
                        val dGray = grayOf(p) - cy
                        val wv = sw * exp(-(dGray * dGray) * inv2sr2)
                        wSum += wv
                        wr += wv * (p shr 16 and 0xFF)
                        wg += wv * (p shr 8 and 0xFF)
                        wb += wv * (p and 0xFF)
                    }
                }
                val alpha = cp and 0xFF000000.toInt()
                dst[ci] = alpha or
                        ((wr / wSum).roundToInt().coerceIn(0, 255) shl 16) or
                        ((wg / wSum).roundToInt().coerceIn(0, 255) shl 8) or
                        ((wb / wSum).roundToInt().coerceIn(0, 255))
            }
        }
    }

    fun bilateral(pixels: IntArray, width: Int, height: Int, radius: Int = 2, sigmaR: Float = 30f): IntArray {
        val out = IntArray(pixels.size)
        bilateralRange(pixels, out, width, height, 0, height, radius, sigmaR)
        return out
    }

    // ══════════════════════════ 3. CLAHE ══════════════════════════

    /**
     * CLAHE（限制对比度自适应直方图均衡）。
     *
     * 与全局均衡化（EQUALIZE）的区别：
     *   1. 图分成 tiles×tiles 个小块，每块独立均衡 → 局部对比度各自拉开；
     *   2. 直方图裁剪限幅：超过 clipLimit×平均密度的部分被"削平"再均匀摊回
     *      全部灰度级 → 抑制噪声区域的过度放大（CLAHE 名字里 "Limited" 的含义）；
     *   3. 相邻 tile 的映射表按像素到 tile 中心的距离双线性插值 → 块与块的过渡
     *      平滑，不会出现"补丁感"。
     */
    fun clahe(pixels: IntArray, width: Int, height: Int, tiles: Int = 8, clipLimit: Float = 3f): IntArray {
        val n = pixels.size
        val gray = IntArray(n)
        for (i in 0 until n) gray[i] = grayOf(pixels[i])

        // tile 实际网格尺寸（最后一块吸收余数）
        val tw = (width + tiles - 1) / tiles
        val th = (height + tiles - 1) / tiles
        // 每个 tile 一张 256 项映射表
        val luts = Array(tiles * tiles) { IntArray(256) }
        val tileArea = tw * th
        // 裁剪上限 = clipLimit × 平均每级像素数；最低保底 1，避免极小 tile 全被削
        val clipCount = max(1f, clipLimit * tileArea / 256f).toInt()

        for (ty in 0 until tiles) for (tx in 0 until tiles) {
            val hist = IntArray(256)
            val x0 = tx * tw; val y0 = ty * th
            val x1 = min(width, x0 + tw); val y1 = min(height, y0 + th)
            var cnt = 0
            for (y in y0 until y1) {
                val row = y * width
                for (x in x0 until x1) hist[gray[row + x]]++
                cnt += x1 - x0
            }
            // 直方图裁剪：超出限幅的部分均匀摊回所有灰度级
            var excess = 0
            for (i in 0..255) if (hist[i] > clipCount) { excess += hist[i] - clipCount; hist[i] = clipCount }
            val bonus = excess / 256
            for (i in 0..255) hist[i] += bonus
            // CDF → LUT（同全局均衡化的映射公式，只是作用域是 tile）
            var cdf = 0; var cdfMin = -1
            for (i in 0..255) {
                if (hist[i] == 0) continue
                cdf += hist[i]
                if (cdfMin < 0) cdfMin = cdf
                val denom = cnt - cdfMin
                luts[ty * tiles + tx][i] = if (denom <= 0) i
                else ((cdf - cdfMin) * 255f / denom).roundToInt().coerceIn(0, 255)
            }
        }

        // 逐像素：四个最近 tile 中心的双线性插值，块过渡平滑
        val out = IntArray(n)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // 像素在 tile 坐标系里的"浮点 tile 位置"，-0.5 让插值以 tile 中心对称
                val fx = x.toFloat() / tw - 0.5f
                val fy = y.toFloat() / th - 0.5f
                val tx0 = fx.toInt(); val ty0 = fy.toInt()
                val dxf = fx - tx0; val dyf = fy - ty0
                val x0 = tx0.coerceIn(0, tiles - 1); val x1 = (tx0 + 1).coerceIn(0, tiles - 1)
                val y0 = ty0.coerceIn(0, tiles - 1); val y1 = (ty0 + 1).coerceIn(0, tiles - 1)
                val wx = if (tx0 == x0) 1f else dxf          // 贴边时权重退化为 1
                val wy = if (ty0 == y0) 1f else dyf
                val gy = gray[y * width + x]
                // 双线性混合四张表的同级输出
                val v00 = luts[y0 * tiles + x0][gy].toFloat()
                val v10 = luts[y0 * tiles + x1][gy].toFloat()
                val v01 = luts[y1 * tiles + x0][gy].toFloat()
                val v11 = luts[y1 * tiles + x1][gy].toFloat()
                val yNew = (v00 * (1 - wx) * (1 - wy) + v10 * wx * (1 - wy) +
                        v01 * (1 - wx) * wy + v11 * wx * wy).roundToInt().coerceIn(0, 255)
                // RGB 按 yNew/y 同比例缩放保色相（与 equalize 同一套保色策略）
                val p = pixels[y * width + x]
                val r = p shr 16 and 0xFF; val g = p shr 8 and 0xFF; val b = p and 0xFF
                val ratio = if (gy == 0) 256 else (yNew * 256) / gy
                out[y * width + x] = (p and 0xFF000000.toInt()) or
                        (((r * ratio) shr 8).coerceIn(0, 255) shl 16) or
                        (((g * ratio) shr 8).coerceIn(0, 255) shl 8) or
                        (((b * ratio) shr 8).coerceIn(0, 255))
            }
        }
        return out
    }

    // ══════════════════════════ 4. 形态学 ══════════════════════════

    /**
     * 二值图腐蚀（3×3 结构元素）：邻域全 1 才输出 1。
     * 效果：白色区域缩小一圈——孤立噪点（面积 < 3×3）直接消失。
     */
    fun binaryErodeRange(src: IntArray, dst: IntArray, width: Int, height: Int, y0: Int, y1: Int) {
        for (y in y0 until y1) {
            for (x in 0 until width) {
                var keep = 255
                loop@ for (dy in -1..1) for (dx in -1..1) {
                    val yy = (y + dy).coerceIn(0, height - 1)
                    val xx = (x + dx).coerceIn(0, width - 1)
                    if ((src[yy * width + xx] shr 16 and 0xFF) < 128) { keep = 0; break@loop }
                }
                dst[y * width + x] = binaryPixel(src[y * width + x], keep)
            }
        }
    }

    /** 二值图膨胀：邻域有 1 就输出 1。效果：白色区域涨回一圈，和腐蚀配对使用 */
    fun binaryDilateRange(src: IntArray, dst: IntArray, width: Int, height: Int, y0: Int, y1: Int) {
        for (y in y0 until y1) {
            for (x in 0 until width) {
                var v = 0
                loop@ for (dy in -1..1) for (dx in -1..1) {
                    val yy = (y + dy).coerceIn(0, height - 1)
                    val xx = (x + dx).coerceIn(0, width - 1)
                    if ((src[yy * width + xx] shr 16 and 0xFF) >= 128) { v = 255; break@loop }
                }
                dst[y * width + x] = binaryPixel(src[y * width + x], v)
            }
        }
    }

    /** 单次完整腐蚀/膨胀（全图版，串行） */
    private fun binaryMorphOnce(src: IntArray, width: Int, height: Int, dilate: Boolean): IntArray {
        val out = IntArray(src.size)
        if (dilate) binaryDilateRange(src, out, width, height, 0, height)
        else binaryErodeRange(src, out, width, height, 0, height)
        return out
    }

    /**
     * 开运算 = 先腐蚀再膨胀：去掉小白噪点、断开细连接，物体整体形状基本不变。
     * 演示链：Otsu 二值化 → 开运算，二值图上的椒点被清掉。
     */
    fun morphOpen(bin: IntArray, width: Int, height: Int, iterations: Int = 1): IntArray {
        var cur = bin
        repeat(iterations) {
            cur = binaryMorphOnce(binaryMorphOnce(cur, width, height, dilate = false), width, height, dilate = true)
        }
        return cur
    }

    /**
     * 闭运算 = 先膨胀再腐蚀：填平物体内部小黑洞、连接近邻区域，整体形状基本不变。
     * 演示链：Otsu 二值化 → 闭运算，黑色小孔被补上。
     */
    fun morphClose(bin: IntArray, width: Int, height: Int, iterations: Int = 1): IntArray {
        var cur = bin
        repeat(iterations) {
            cur = binaryMorphOnce(binaryMorphOnce(cur, width, height, dilate = true), width, height, dilate = false)
        }
        return cur
    }

    /** 二值像素重组：灰度取 v，alpha 沿用原像素 */
    private fun binaryPixel(orig: Int, v: Int): Int {
        val vv = v.coerceIn(0, 255)
        return (orig and 0xFF000000.toInt()) or (vv shl 16) or (vv shl 8) or vv
    }

    // ══════════════════════════ 5. USM 锐化 ══════════════════════════

    /**
     * USM（Unsharp Masking）锐化：out = 原图 + (原图 − 高斯模糊) × amount。
     * "原图 − 高斯模糊"恰好是高频成分（细节/边缘），加回去就是把细节放大——
     * 复用高斯模糊实现，十几行换一个专业滤镜。
     */
    fun usm(pixels: IntArray, width: Int, height: Int, amount: Float = 0.8f, blurRadius: Int = 2): IntArray {
        val blurred = convolver.gaussianBlur(pixels, width, height, blurRadius)
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val bp = blurred[i]
            val r = channelUsm(p shr 16 and 0xFF, bp shr 16 and 0xFF, amount)
            val g = channelUsm(p shr 8 and 0xFF, bp shr 8 and 0xFF, amount)
            val b = channelUsm(p and 0xFF, bp and 0xFF, amount)
            out[i] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    private fun channelUsm(orig: Int, blurred: Int, amount: Float): Int {
        val detail = orig - blurred
        return (orig + detail * amount).roundToInt().coerceIn(0, 255)
    }

    // ══════════════════════════ 6. 插值缩放 ══════════════════════════

    /** 缩放方法枚举：三套插值同一接口，方便"同一张图放大 4 倍看锯齿"对比 */
    enum class Resample { NEAREST, BILINEAR, BICUBIC }

    /** 2× 放大（行范围版暂不需要——输出坐标天然按块均匀，并行收益小），返回新像素数组 */
    fun resize(pixels: IntArray, width: Int, height: Int, scale: Int, method: Resample): Triple<IntArray, Int, Int> {
        val nw = width * scale
        val nh = height * scale
        val out = IntArray(nw * nh)
        when (method) {
            Resample.NEAREST -> for (y in 0 until nh) {
                val sy = (y / scale).coerceIn(0, height - 1)
                for (x in 0 until nw) {
                    val sx = (x / scale).coerceIn(0, width - 1)
                    out[y * nw + x] = pixels[sy * width + sx]
                }
            }
            Resample.BILINEAR -> for (y in 0 until nh) {
                val fy = y / scale.toFloat() - 0.5f       // -0.5 让采样点对齐像素中心
                val sy0 = fy.toInt().coerceIn(0, height - 1)
                val sy1 = (sy0 + 1).coerceIn(0, height - 1)
                val wy = if (sy0 == fy.toInt()) 0f else fy - sy0
                for (x in 0 until nw) {
                    val fx = x / scale.toFloat() - 0.5f
                    val sx0 = fx.toInt().coerceIn(0, width - 1)
                    val sx1 = (sx0 + 1).coerceIn(0, width - 1)
                    val wx = if (sx0 == fx.toInt()) 0f else fx - sx0
                    out[y * nw + x] = bilinearSample(pixels, width, sx0, sx1, sy0, sy1, wx, wy)
                }
            }
            Resample.BICUBIC -> for (y in 0 until nh) {
                val fy = y / scale.toFloat() - 0.5f
                for (x in 0 until nw) {
                    val fx = x / scale.toFloat() - 0.5f
                    out[y * nw + x] = bicubicSample(pixels, width, height, fx, fy)
                }
            }
        }
        return Triple(out, nw, nh)
    }

    /** 双线性：4 邻居加权，权重 = 到采样点的距离比例。平滑但略糊（高频被平均掉） */
    private fun bilinearSample(
        pixels: IntArray, width: Int, sx0: Int, sx1: Int, sy0: Int, sy1: Int, wx: Float, wy: Float
    ): Int {
        val p00 = pixels[sy0 * width + sx0]; val p10 = pixels[sy0 * width + sx1]
        val p01 = pixels[sy1 * width + sx0]; val p11 = pixels[sy1 * width + sx1]
        val r = mix4(p00, p10, p01, p11, wx, wy) { it shr 16 and 0xFF }
        val g = mix4(p00, p10, p01, p11, wx, wy) { it shr 8 and 0xFF }
        val b = mix4(p00, p10, p01, p11, wx, wy) { it and 0xFF }
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    private inline fun mix4(
        p00: Int, p10: Int, p01: Int, p11: Int, wx: Float, wy: Float, extract: (Int) -> Int
    ): Int {
        val top = extract(p00) * (1 - wx) + extract(p10) * wx
        val bot = extract(p01) * (1 - wx) + extract(p11) * wx
        return (top * (1 - wy) + bot * wy).roundToInt().coerceIn(0, 255)
    }

    /** Catmull-Rom 参数 a = -0.75 的双三次核 */
    private fun cubicWeight(t: Float): Float {
        val at = abs(t)
        return when {
            at <= 1f -> 1.5f * at * at * at - 2.5f * at * at + 1f
            at < 2f -> -0.5f * at * at * at + 2.5f * at * at - 4f * at + 2f
            else -> 0f
        }
    }

    /** 双三次：16 邻居按三次核加权。细节保留最好、计算最贵，边缘更锐利 */
    private fun bicubicSample(pixels: IntArray, width: Int, height: Int, fx: Float, fy: Float): Int {
        val x0 = fx.toInt(); val y0 = fy.toInt()
        var r = 0f; var g = 0f; var b = 0f
        for (m in -1..2) {
            val wy = cubicWeight(fy - (y0 + m))
            if (wy == 0f) continue
            val yy = (y0 + m).coerceIn(0, height - 1)
            for (n in -1..2) {
                val wx = cubicWeight(fx - (x0 + n))
                if (wx == 0f) continue
                val p = pixels[yy * width + (x0 + n).coerceIn(0, width - 1)]
                val w = wx * wy
                r += w * (p shr 16 and 0xFF)
                g += w * (p shr 8 and 0xFF)
                b += w * (p and 0xFF)
            }
        }
        return 0xFF000000.toInt() or
                (r.roundToInt().coerceIn(0, 255) shl 16) or
                (g.roundToInt().coerceIn(0, 255) shl 8) or
                b.roundToInt().coerceIn(0, 255)
    }

    // ══════════════════════════ 7. 颜色空间 ══════════════════════════

    /**
     * 色相旋转（RGB↔HSV 完整往返）。
     * HSV 里 H 是"色相角"（0~360°红黄绿青蓝紫），旋转 = 整体把颜色沿色环转 shift 度；
     * S、V 不动，所以亮度和鲜艳程度都不变。
     */
    fun adjustHue(pixels: IntArray, shiftDegrees: Int): IntArray {
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val (h, s, v) = rgbToHsv(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF)
            val h2 = (h + shiftDegrees + 360 * 720) % 360f
            val (r, g, b) = hsvToRgb(h2, s, v)
            out[i] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    /** 饱和度调整（HSV 的 S 通道乘 gain）：>1 更鲜艳、<1 褪色、0 变灰 */
    fun adjustSaturation(pixels: IntArray, gain: Float): IntArray {
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val (h, s, v) = rgbToHsv(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF)
            val (r, g, b) = hsvToRgb(h, (s * gain).coerceIn(0f, 1f), v)
            out[i] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    /** RGB → HSV：H∈[0,360)，S∈[0,1]，V∈[0,1] */
    fun rgbToHsv(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val mx = max(rf, max(gf, bf)); val mn = min(rf, min(gf, bf))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == rf -> 60f * (((gf - bf) / d) % 6f + 6f) % 360f
            mx == gf -> 60f * ((bf - rf) / d + 2f)
            else -> 60f * ((rf - gf) / d + 4f)
        }
        return Triple(h, if (mx == 0f) 0f else d / mx, mx)
    }

    /** HSV → RGB（标准六段折线公式） */
    fun hsvToRgb(h: Float, s: Float, v: Float): Triple<Int, Int, Int> {
        val c = v * s
        val hp = (h % 360f + 360f) % 360f / 60f
        val x = c * (1 - abs(hp % 2f - 1))
        val m = v - c
        val (rf, gf, bf) = when (hp.toInt() % 6) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x); 3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        return Triple(((rf + m) * 255).roundToInt().coerceIn(0, 255),
            ((gf + m) * 255).roundToInt().coerceIn(0, 255),
            ((bf + m) * 255).roundToInt().coerceIn(0, 255))
    }

    /**
     * YCbCr 色度偏移（RGB↔YCbCr 往返）：Y 是亮度，Cb/Cr 是蓝/红色度分量。
     * 只动 Cb/Cr、不动 Y → 亮度结构完全不变，只有颜色被推移（暖色/冷色调）。
     */
    fun shiftChroma(pixels: IntArray, dCb: Int, dCr: Int): IntArray {
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = p shr 16 and 0xFF; val g = p shr 8 and 0xFF; val b = p and 0xFF
            // BT.601 定义的标准转换
            val y = 0.299f * r + 0.587f * g + 0.114f * b
            var cb = -0.168736f * r - 0.331264f * g + 0.5f * b + 128f + dCb
            var cr = 0.5f * r - 0.418688f * g - 0.081312f * b + 128f + dCr
            cb = cb.coerceIn(0f, 255f); cr = cr.coerceIn(0f, 255f)
            // 逆变换
            val r2 = (y + 1.402f * (cr - 128f)).roundToInt().coerceIn(0, 255)
            val g2 = (y - 0.344136f * (cb - 128f) - 0.714136f * (cr - 128f)).roundToInt().coerceIn(0, 255)
            val b2 = (y + 1.772f * (cb - 128f)).roundToInt().coerceIn(0, 255)
            out[i] = (p and 0xFF000000.toInt()) or (r2 shl 16) or (g2 shl 8) or b2
        }
        return out
    }

    // ══════════════════════════ 8. DCT 压缩演示 ══════════════════════════

    /** JPEG 标准 8×8 亮度量化表 */
    private val quantTable = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61,
        12, 12, 14, 19, 26, 58, 60, 55,
        14, 13, 16, 24, 40, 57, 69, 56,
        14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77,
        24, 35, 55, 64, 81, 104, 113, 92,
        49, 64, 78, 87, 103, 121, 120, 101,
        72, 92, 95, 98, 112, 100, 103, 99
    )

    /** 8×8 DCT 基函数预计算：B[u][x] = c(u)/2 · cos((2x+1)uπ/16)，变换即矩阵乘 B·S·Bᵀ */
    private val basis = Array(8) { u -> FloatArray(8) { x ->
        val cu = if (u == 0) sqrt(0.5f) else 1f
        (cu / 2f) * cos((2 * x + 1) * u * PI / 16f).toFloat()
    } }

    /**
     * DCT 压缩演示（JPEG 原理）：分 8×8 块 → 正向 DCT → 量化（丢高频）→ 反变换。
     *
     * @param quality 1~100，越高量化越细、画质越好；低质量下 8×8 块边界会出现
     *                "块效应"——这正是 JPEG 在低码率下的标志性失真。
     * 只对亮度 Y 做 DCT，RGB 按新旧亮度比缩放（色彩不参与变换，计算量省 2/3）。
     */
    fun dctCompress(pixels: IntArray, width: Int, height: Int, quality: Int = 30): IntArray {
        val n = pixels.size
        val gray = IntArray(n)
        for (i in 0 until n) gray[i] = grayOf(pixels[i])
        // quality → 量化步长缩放（libjpeg 公式：quality<50 放大步长，>50 缩小）
        val scale = if (quality < 50) 5000f / quality else 200f - 2f * quality
        val q = IntArray(64) { i -> max(1, (quantTable[i] * scale / 100f).roundToInt()) }

        // 8×8 工作区（块越界部分用边缘像素填充，不做零填充避免边界伪影）
        val blk = FloatArray(64)
        val t1 = FloatArray(64)
        val freq = FloatArray(64)
        val rec = FloatArray(64)
        val out = IntArray(n)

        for (by in 0 until height step 8) {
            for (bx in 0 until width step 8) {
                // ── 取块（越界 clamp）──
                for (v in 0 until 8) for (u in 0 until 8) {
                    val yy = min(height - 1, by + v)
                    val xx = min(width - 1, bx + u)
                    blk[v * 8 + u] = gray[yy * width + xx].toFloat()
                }
                // ── 正向 DCT：F = B·S·Bᵀ（两次矩阵乘，O(8³) 每块）──
                mulLeftB(blk, t1)        // t1 = B·S
                mulRightBT(t1, freq)     // freq = t1·Bᵀ = B·S·Bᵀ
                // ── 量化：JPEG 丢信息的唯一一步，除法取整把高频小系数压成 0 ──
                for (i in 0 until 64) freq[i] = ((freq[i] / q[i]).roundToInt() * q[i]).toFloat()
                // ── 逆 DCT：S = Bᵀ·F·B（B 正交，逆变换转置即可）──
                mulLeftBT(freq, t1)      // t1 = Bᵀ·F
                mulRightB(t1, rec)       // rec = t1·B = Bᵀ·F·B
                // ── 写回 + 保色相缩放 ──
                for (v in 0 until 8) for (u in 0 until 8) {
                    val yy = min(height - 1, by + v)
                    val xx = min(width - 1, bx + u)
                    val idx = yy * width + xx
                    val yNew = rec[v * 8 + u].roundToInt().coerceIn(0, 255)
                    val p = pixels[idx]
                    val gy = gray[idx]
                    val ratio = if (gy == 0) 256 else (yNew * 256) / gy
                    val r = ((p shr 16 and 0xFF) * ratio shr 8).coerceIn(0, 255)
                    val g = ((p shr 8 and 0xFF) * ratio shr 8).coerceIn(0, 255)
                    val b = ((p and 0xFF) * ratio shr 8).coerceIn(0, 255)
                    out[idx] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return out
    }

    /** dst = B·src（行主序 8×8 矩阵左乘） */
    private fun mulLeftB(src: FloatArray, dst: FloatArray) {
        for (i in 0 until 8) for (j in 0 until 8) {
            var s = 0f
            for (k in 0 until 8) s += basis[i][k] * src[k * 8 + j]
            dst[i * 8 + j] = s
        }
    }

    /** dst = src·B（行主序 8×8 矩阵右乘） */
    private fun mulRightB(src: FloatArray, dst: FloatArray) {
        for (i in 0 until 8) for (j in 0 until 8) {
            var s = 0f
            for (k in 0 until 8) s += src[i * 8 + k] * basis[k][j]
            dst[i * 8 + j] = s
        }
    }

    /** dst = Bᵀ·src（B 正交，逆变换用转置矩阵左乘） */
    private fun mulLeftBT(src: FloatArray, dst: FloatArray) {
        for (i in 0 until 8) for (j in 0 until 8) {
            var s = 0f
            for (k in 0 until 8) s += basis[k][i] * src[k * 8 + j]
            dst[i * 8 + j] = s
        }
    }

    /** dst = src·Bᵀ（转置矩阵右乘） */
    private fun mulRightBT(src: FloatArray, dst: FloatArray) {
        for (i in 0 until 8) for (j in 0 until 8) {
            var s = 0f
            for (k in 0 until 8) s += src[i * 8 + k] * basis[j][k]
            dst[i * 8 + j] = s
        }
    }

    // ══════════════════════════ 公共小工具 ══════════════════════════

    /** BT.601 灰度（与 Histogram/Convolver 同一定义，保证统计口径一致） */
    private fun grayOf(p: Int): Int = (299 * (p shr 16 and 0xFF) + 587 * (p shr 8 and 0xFF) + 114 * (p and 0xFF)) / 1000
}
