package com.pixellab.app.image

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.math.min

/**
 * 处理流水线（Process Engine）
 *
 * 把"加载 → 处理 → 保存"三步收拢到一个对象里，编辑页、批量 Worker、
 * 分享卡片都走同一条流水线——单一数据通路，逻辑不会在各页面各写一份。
 *
 * v2 新增：
 *  - A 组硬核算法接入（Canny/双边/CLAHE/形态学/USM/插值/颜色空间/DCT）；
 *  - 多线程分块：逐行独立的滤波（3×3 卷积/中值/双边）按行切 N 块并行，
 *    结果逐块拼回，算法语义零改动、纯工程提速；
 *  - 自定义卷积核：编辑页 9 宫格输入的核从参数传入，与预设走同一条卷积通路。
 */
object ProcessEngine {

    /** Convolver 无状态（全部局部变量），单例复用免去每次调用的对象分配 */
    private val convolver = Convolver()

    /** A 组高级算法引擎（复用同一个 convolver：USM 要调高斯、形态学要接 Otsu） */
    private val advanced = AdvancedFilters(convolver)

    /** 图片私有目录：files/pixellab/。卸载 App 自动清理，无需存储权限 */
    fun imageDir(context: Context): File =
        File(context.filesDir, "pixellab").apply { mkdirs() }

    /** 分享卡片/PDF 报告目录：files/share/（FileProvider 只暴露这个目录） */
    fun shareDir(context: Context): File =
        File(context.filesDir, "share").apply { mkdirs() }

    /**
     * 从磁盘加载"编辑尺寸"的 Bitmap：
     * 两次解码限尺寸（Transformer.decodeScaled）→ 按 EXIF orientation 转正。
     * @param maxEdge 最长边上限，来自设置页
     */
    fun loadForEdit(path: String, maxEdge: Int): Bitmap? {
        val bmp = Transformer.decodeScaled(path, maxEdge) ?: return null
        val rotated = runCatching {
            val exif = Transformer.readExif(java.io.FileInputStream(path))
            Transformer.applyExifOrientation(path, bmp, exif)
        }.getOrDefault(bmp)   // EXIF 读取失败（如截图）就用原始方向，不阻塞主流程
        return rotated
    }

    /**
     * 多线程分块（行切分并行）：把整图按行均分成 N 段交给 N 个工作线程。
     * 用裸线程 + CountDownLatch 而不是协程：本函数会被 ViewModel 的
     * Dispatchers.Default 协程调用，runBlocking 再起 Default 协程有线程饥饿风险；
     * 裸线程在任何调用线程上都安全。
     * 适用前提：worker 只写自己负责的行（apply3x3/median/bilateral 都满足），
     * 分块不改变任何计算顺序，结果与串行版本逐像素一致——"不改算法语义的纯工程优化"。
     */
    private fun parallelRows(height: Int, worker: (y0: Int, y1: Int) -> Unit) {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        if (height < 128) { worker(0, height); return }   // 小图并行调度开销反而占大头，直接串行
        val chunk = (height + threads - 1) / threads
        val ranges = (0 until height step chunk).map { y0 -> y0 to min(y0 + chunk, height) }
        val latch = CountDownLatch(ranges.size)
        for ((y0, y1) in ranges) {
            Thread {
                try { worker(y0, y1) } finally { latch.countDown() }
            }.apply {
                priority = Thread.NORM_PRIORITY - 1   // 不抢 UI 线程的调度优先级
                start()
            }
        }
        latch.await()
    }

    /**
     * 处理管线：滤镜卷积 → 强度混合 → 亮度/对比度点操作。
     *
     * @param filterKey 滤镜 key（卷积预设 / Convolver 专门实现 / FilterCatalog 高级算法 / CUSTOM）
     * @param strength 滤镜强度 0~1：结果是"滤镜输出"与"输入"的逐像素线性插值
     *   （lerp = a·(1−t) + b·t）。强度只控制混合比例，一次滤镜 + 一次插值；
     *   k==0 时滤镜整体无效，直接跳过最贵的一步。
     * @param brightness 亮度偏移 -100~100（y = a·x + b 的 b）
     * @param contrast 对比度 0~100（映射为增益 0.5~1.5，围绕中灰 128 缩放）
     * @param customKernel filterKey == CUSTOM 时的 3×3 核（行优先 9 个数）
     * @return 处理后 Bitmap + 耗时毫秒（存进历史记录，量化展示性能）
     */
    fun applyFilter(
        src: Bitmap,
        filterKey: String,
        strength: Float = 1f,
        brightness: Int = 0,
        contrast: Int = 0,
        customKernel: FloatArray? = null
    ): Pair<Bitmap, Long> {
        val start = SystemClock.elapsedRealtime()   // 单调时钟，不受系统对时影响，计时更严谨
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)   // 一次性搬进数组，避免逐像素 JNI

        // 强度混合系数：整数域 lerp，避免浮点逐像素开销
        val k = (strength.coerceIn(0f, 1f) * 255).toInt()

        // k==0 → 滤镜无效，跳过滤镜计算（管线里最贵的一步），直接以原图为底
        var out: IntArray = pixels
        if (k > 0) {
            out = when (filterKey) {
                // —— 经典实现（Convolver）——
                "GAUSSIAN" -> convolver.gaussianBlur(pixels, w, h, radius = 2)
                "GRAYSCALE" -> convolver.grayscale(pixels)
                "EQUALIZE" -> convolver.equalize(pixels)
                "OTSU" -> convolver.otsuBinarize(pixels)
                "MEDIAN" -> {
                    val dst = IntArray(pixels.size)
                    // 中值滤波逐行独立 → 多线程分块
                    parallelRows(h) { y0, y1 ->
                        convolver.medianFilterRange(pixels, dst, w, h, y0, y1)
                    }
                    dst
                }
                // —— A 组硬核算法（AdvancedFilters）——
                "CANNY" -> advanced.canny(pixels, w, h)
                "BILATERAL" -> {
                    val dst = IntArray(pixels.size)
                    // 双边滤波是全部滤镜里最贵的一个（每像素 25 次值域权重计算），
                    // 多线程分块收益最大
                    parallelRows(h) { y0, y1 ->
                        advanced.bilateralRange(pixels, dst, w, h, y0, y1)
                    }
                    dst
                }
                "CLAHE" -> advanced.clahe(pixels, w, h)
                "OTSU_OPEN" -> advanced.morphOpen(convolver.otsuBinarize(pixels), w, h)
                "OTSU_CLOSE" -> advanced.morphClose(convolver.otsuBinarize(pixels), w, h)
                "USM" -> advanced.usm(pixels, w, h)
                "HUE_ROT" -> advanced.adjustHue(pixels, 45)
                "SAT_BOOST" -> advanced.adjustSaturation(pixels, 1.5f)
                "CHROMA_SHIFT" -> advanced.shiftChroma(pixels, dCb = 25, dCr = -18)
                "DCT" -> advanced.dctCompress(pixels, w, h, quality = 30)
                // —— 插值对比：输出尺寸变 2×（三套插值同一张图放大看锯齿）——
                "NEAREST2X" -> {
                    val (px2, nw, nh) = advanced.resize(pixels, w, h, 2, AdvancedFilters.Resample.NEAREST)
                    return finishFilter(px2, nw, nh, pixels, k, brightness, contrast, start)
                }
                "BILINEAR2X" -> {
                    val (px2, nw, nh) = advanced.resize(pixels, w, h, 2, AdvancedFilters.Resample.BILINEAR)
                    return finishFilter(px2, nw, nh, pixels, k, brightness, contrast, start)
                }
                "BICUBIC2X" -> {
                    val (px2, nw, nh) = advanced.resize(pixels, w, h, 2, AdvancedFilters.Resample.BICUBIC)
                    return finishFilter(px2, nw, nh, pixels, k, brightness, contrast, start)
                }
                // —— 自定义卷积核（编辑页 9 宫格输入）——
                "CUSTOM" -> {
                    val kernel = customKernel ?: FilterPreset.SHARPEN.kernel
                    val dst = IntArray(pixels.size)
                    parallelRows(h) { y0, y1 ->
                        convolver.apply3x3Range(pixels, dst, w, h, y0, y1, kernel)
                    }
                    dst
                }
                // —— 经典卷积预设：也走多线程分块（与串行语义完全一致）——
                else -> {
                    val dst = IntArray(pixels.size)
                    val kernel = FilterPreset.fromName(filterKey).kernel
                    parallelRows(h) { y0, y1 ->
                        convolver.apply3x3Range(pixels, dst, w, h, y0, y1, kernel)
                    }
                    dst
                }
            }
            // 强度混合：k=255（100%）等价原滤镜，无需再混合
            if (k < 255) {
                out = IntArray(out.size) { i -> mixPixel(pixels[i], out[i], k) }
            }
        }

        // 亮度/对比度点操作（内部恒等映射时零成本直通）
        out = convolver.adjust(out, brightness, contrast)

        val result = Bitmap.createBitmap(out.size / h, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, result.width, 0, 0, result.width, h)
        return result to (SystemClock.elapsedRealtime() - start)
    }

    /**
     * 插值缩放滤镜的收尾：输出尺寸是 2×，与输入数组长度不同，
     * 强度混合与点操作独立做一遍（原图 pixels 长度对不上 out，逐像素混合按缩小坐标对齐）。
     */
    private fun finishFilter(
        out: IntArray, nw: Int, nh: Int, pixels: IntArray,
        k: Int, brightness: Int, contrast: Int, start: Long
    ): Pair<Bitmap, Long> {
        var final = out
        if (k < 255) {
            // 2× 放大后再缩回原尺寸做混合，混合完维持 2× 输出（预览对比锯齿更直观）
            val sw = nw / 2
            val sh = nh / 2
            val mixed = IntArray(nw * nh)
            for (y in 0 until nh) {
                val sy = (y / 2).coerceIn(0, sh - 1)
                for (x in 0 until nw) {
                    val sx = (x / 2).coerceIn(0, sw - 1)
                    mixed[y * nw + x] = mixPixel(pixels[sy * sw + sx], out[y * nw + x], k)
                }
            }
            final = mixed
        }
        final = convolver.adjust(final, brightness, contrast)
        val result = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
        result.setPixels(final, 0, nw, 0, 0, nw, nh)
        return result to (SystemClock.elapsedRealtime() - start)
    }

    /** 两个 ARGB 像素的逐通道线性插值：v = (src·(255−k) + dst·k) / 255 */
    private fun mixPixel(src: Int, dst: Int, k: Int): Int {
        fun mix(shift: Int): Int =
            (((src shr shift and 0xFF) * (255 - k)) + ((dst shr shift and 0xFF) * k)) / 255
        return 0xFF000000.toInt() or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /**
     * 算法横向对比模式：同一张图跑四个滤波，返回 "滤镜 key → (结果, 耗时)"。
     * 全部在调用方给的协程上下文里跑（编辑页在 Dispatchers.Default 调用），
     * 每个滤镜独立计时——"同一输入、同一管线、只换算法"的公平对比。
     */
    fun compareFilters(
        src: Bitmap,
        keys: List<String> = FilterCatalog.compareKeys
    ): List<Triple<String, Bitmap, Long>> = keys.map { key ->
        val (bmp, cost) = applyFilter(src, key)
        Triple(key, bmp, cost)
    }

    /** 旋转 90°（顺时针），返回新 Bitmap */
    fun rotate(src: Bitmap): Bitmap = Transformer.rotate(src, 90f)

    /** 按比例字符串裁剪（"1:1"/"4:3"/"16:9"） */
    fun crop(src: Bitmap, ratio: String): Bitmap = Transformer.cropToRatio(src, ratio)

    /**
     * 保存 Bitmap 到私有目录，返回绝对路径。
     * PNG 无损但文件大；缩略图用 JPEG（体积小几十倍，列表加载快）。
     */
    fun saveBitmap(context: Context, bitmap: Bitmap, prefix: String, jpeg: Boolean = false): String {
        val dir = imageDir(context)
        val file = File(dir, "$prefix-${System.currentTimeMillis()}.${if (jpeg) "jpg" else "png"}")
        file.outputStream().use { out ->
            bitmap.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, if (jpeg) 85 else 100, out)
        }
        return file.absolutePath
    }

    /** 操作序列 JSON（简单转义就够：值都是枚举名和数字，不含特殊字符） */
    fun buildOpsJson(vararg ops: Pair<String, String>): String =
        ops.joinToString(",", "[", "]") { (op, v) -> """{"op":"$op","v":"$v"}""" }
}
