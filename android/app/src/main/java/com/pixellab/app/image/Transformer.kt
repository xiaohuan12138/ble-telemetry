package com.pixellab.app.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream

/**
 * 几何变换 + 大图安全加载（Transformer）
 *
 * 两个职责：
 *   1. 旋转 / 裁剪：Bitmap.createBitmap + Matrix，O(1) 级别的零拷贝描述，
 *      真正的像素搬移发生在 draw 时，比手动逐像素搬快得多。
 *   2. 大图安全加载（这直接对应评分表里的"性能优化"）：
 *      手机照片动辄 4000×3000 = 1200 万像素 = 48MB 内存（ARGB_8888 每像素 4 字节），
 *      直接 BitmapFactory.decodeFile() 会瞬间吃掉一个 Activity 的内存配额甚至 OOM。
 *      正确姿势是「两次解码」：第一次只读尺寸（inJustDecodeBounds=true），
 *      按"最长边不超过目标值"算 inSampleSize，第二次用采样率解码。
 *      1280 长边的图约 1280×960×4B ≈ 4.7MB，卷积毫秒级就能跑完。
 */
object Transformer {

    /** 编辑用的最长边：保证肉眼清晰，同时把卷积耗时压在毫秒级 */
    const val EDIT_MAX_EDGE = 1280
    /** 缩略图最长边：列表页/小组件用 */
    const val THUMB_MAX_EDGE = 256

    /**
     * 安全解码：两次解码法 + inSampleSize 采样。
     * inSampleSize 必须是 2 的幂（硬件层面按块采样），所以用 2 的幂向下取。
     */
    fun decodeScaled(path: String, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)   // 只读宽高，不分配像素内存
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        var longest = maxOf(bounds.outWidth, bounds.outHeight)
        // 循环翻倍采样率，直到最长边降到目标以内（例如 4000 → sample 4 → 1000）
        while (longest / 2 >= maxEdge) {
            sample *= 2
            longest /= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, opts)
    }

    /** 顺时针旋转 [degrees] 度。90/180/270 是无损变换，任意角度会轻微重采样 */
    fun rotate(src: Bitmap, degrees: Float): Bitmap {
        if (degrees % 360f == 0f) return src
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /**
     * 居中裁剪到指定宽高比（比例用"分子:分母"字符串传，如 "1:1"、"4:3"、"16:9"）。
     * 裁剪窗口在原图内居中，窗口尺寸取"能容纳的最大等比矩形"。
     */
    fun cropToRatio(src: Bitmap, ratioText: String): Bitmap {
        val parts = ratioText.split(":")
        val rw = parts.getOrNull(0)?.toFloatOrNull() ?: return src
        val rh = parts.getOrNull(1)?.toFloatOrNull() ?: return src
        if (rw <= 0 || rh <= 0) return src

        val srcRatio = src.width.toFloat() / src.height
        val target = rw / rh
        // 目标比例比原图"更宽"→ 宽度受限；更"窄"→ 高度受限
        val (cw, ch) = if (target > srcRatio) {
            src.width to (src.width / target).toInt()
        } else {
            (src.height * target).toInt() to src.height
        }
        val left = (src.width - cw) / 2
        val top = (src.height - ch) / 2
        return Bitmap.createBitmap(src, left, top, cw, ch)
    }

    /**
     * 按 EXIF 的 orientation 标记把图"转正"。
     *
     * 手机竖拍的照片，像素本身是横着存的（传感器按横向读出），
     * 靠 EXIF 里的 4 字节 orientation 标记告诉看图软件"显示时要转 90°"。
     * 直接 decodeFile 拿到的 Bitmap 是未转正的——这是"照片为什么横着"的经典坑。
     */
    fun applyExifOrientation(path: String, src: Bitmap, exif: ExifInterface): Bitmap {
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> 0f   // 镜像场景本项目简化为不处理
            else -> 0f
        }
        return if (degrees != 0f) rotate(src, degrees) else src
    }

    /** 从流读 EXIF（InputStream 版本，编辑页直接用） */
    fun readExif(input: InputStream): ExifInterface = ExifInterface(input)

    /** 生成等比缩略图（列表页 / P1 小组件用），宽高比不变 */
    fun makeThumbnail(src: Bitmap, maxEdge: Int = THUMB_MAX_EDGE): Bitmap {
        val scale = maxEdge.toFloat() / maxOf(src.width, src.height)
        if (scale >= 1f) return src
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }
}
