package com.pixellab.app.image

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import java.io.File

/**
 * 处理流水线（Process Engine）
 *
 * 把"加载 → 处理 → 保存"三步收拢到一个对象里，编辑页、批量 Worker、
 * 分享卡片都走同一条流水线——单一数据通路，逻辑不会在各页面各写一份。
 */
object ProcessEngine {

    /** Convolver 无状态（全部局部变量），单例复用免去每次调用的对象分配 */
    private val convolver = Convolver()

    /** 图片私有目录：files/pixellab/。卸载 App 自动清理，无需存储权限 */
    fun imageDir(context: Context): File =
        File(context.filesDir, "pixellab").apply { mkdirs() }

    /** 分享卡片目录：files/share/（FileProvider 只暴露这个目录） */
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
     * 处理管线：滤镜卷积 → 强度混合 → 亮度/对比度点操作。
     *
     * @param filterKey 滤镜 key（卷积预设 / GAUSSIAN / GRAYSCALE / EQUALIZE / OTSU / MEDIAN）
     * @param strength 滤镜强度 0~1：结果是"卷积输出"与"输入"的逐像素线性插值
     *   （lerp = a·(1−t) + b·t）。强度只控制混合比例——卷积核固定不变，一次卷积 + 一次插值；
     *   k==0 时滤镜整体无效，直接跳过卷积省掉最贵的一步。
     * @param brightness 亮度偏移 -100~100（y = a·x + b 的 b）
     * @param contrast 对比度 0~100（映射为增益 0.5~1.5，围绕中灰 128 缩放）
     * @return 处理后 Bitmap + 耗时毫秒（存进历史记录，量化展示性能）
     */
    fun applyFilter(
        src: Bitmap,
        filterKey: String,
        strength: Float = 1f,
        brightness: Int = 0,
        contrast: Int = 0
    ): Pair<Bitmap, Long> {
        val start = SystemClock.elapsedRealtime()   // 单调时钟，不受系统对时影响，计时更严谨
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)   // 一次性搬进数组，避免逐像素 JNI

        // 强度混合系数：整数域 lerp，避免浮点逐像素开销
        val k = (strength.coerceIn(0f, 1f) * 255).toInt()

        // k==0 → 滤镜无效，跳过卷积（管线里最贵的一步），直接以原图为底
        var out: IntArray = pixels
        if (k > 0) {
            out = when (filterKey) {
                "GAUSSIAN" -> convolver.gaussianBlur(pixels, w, h, radius = 2)
                "GRAYSCALE" -> convolver.grayscale(pixels)
                "EQUALIZE" -> convolver.equalize(pixels)
                "OTSU" -> convolver.otsuBinarize(pixels)
                "MEDIAN" -> convolver.medianFilter(pixels, w, h)
                else -> convolver.apply3x3(pixels, w, h, FilterPreset.fromName(filterKey).kernel)
            }
            // 强度混合：k=255（100%）等价原滤镜，无需再混合
            if (k < 255) {
                out = IntArray(out.size) { i -> mixPixel(pixels[i], out[i], k) }
            }
        }

        // 亮度/对比度点操作（内部恒等映射时零成本直通）
        out = convolver.adjust(out, brightness, contrast)

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result to (SystemClock.elapsedRealtime() - start)
    }

    /** 两个 ARGB 像素的逐通道线性插值：v = (src·(255−k) + dst·k) / 255 */
    private fun mixPixel(src: Int, dst: Int, k: Int): Int {
        fun mix(shift: Int): Int =
            (((src shr shift and 0xFF) * (255 - k)) + ((dst shr shift and 0xFF) * k)) / 255
        return 0xFF000000.toInt() or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
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
