package com.pixellab.app.image

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/**
 * 处理流水线（Process Engine）
 *
 * 把"加载 → 处理 → 保存"三步收拢到一个对象里，编辑页、批量 Worker、
 * 分享卡片都走同一条流水线——单一数据通路，逻辑不会在各页面各写一份。
 */
object ProcessEngine {

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
     * 应用滤镜（含"灰度"这个特殊项）。
     * @return 处理后 Bitmap + 耗时毫秒（存进历史记录，量化展示性能）
     */
    fun applyFilter(src: Bitmap, filterKey: String): Pair<Bitmap, Long> {
        val start = System.currentTimeMillis()
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)   // 一次性搬进数组，避免逐像素 JNI

        val convolver = Convolver()
        val out: IntArray = when (filterKey) {
            "GAUSSIAN" -> convolver.gaussianBlur(pixels, w, h, radius = 2)
            "GRAYSCALE" -> convolver.grayscale(pixels)
            else -> convolver.apply3x3(pixels, w, h, FilterPreset.fromName(filterKey).kernel)
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result to (System.currentTimeMillis() - start)
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
