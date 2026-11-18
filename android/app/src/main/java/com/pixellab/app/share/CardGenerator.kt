package com.pixellab.app.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.pixellab.app.R
import com.pixellab.app.image.Histogram
import com.pixellab.app.image.FilterPreset
import com.pixellab.app.image.ProcessEngine
import java.io.File

/**
 * P4 伴生模块：分享 / 导出卡片。
 *
 * 功能：把"处理前 + 处理后 + 参数 + 量化指标 + 学号水印"拼成一张
 * 1080 宽的 PNG 卡片，通过 FileProvider + ACTION_SEND 分享到微信/QQ 等。
 *
 * 为什么这是独立可验收的伴生单元：
 *   1. 输出物是标准 PNG 文件，独立存在于 files/share/；
 *   2. 走系统分享面板，不依赖任何第三方 SDK；
 *   3. FileProvider 白名单只暴露 share/ 目录（见 file_paths.xml）。
 */
object CardGenerator {

    private const val CARD_W = 1080

    /** 生成卡片并调起分享面板 */
    fun shareResultCard(
        context: Context,
        before: Bitmap,
        after: Bitmap,
        filterKey: String,
        costMs: Long
    ) {
        val file = generate(context, before, after, filterKey, costMs)
        val uri = FileProvider.getUriForFile(
            context, "com.pixellab.app.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "我用像素工坊处理了一张图（${FilterPreset.fromName(filterKey).displayName}），看看效果")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)   // 授权目标 App 读这张图
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_via)))
    }

    /** 生成卡片 PNG，返回文件（页面可以单独调用做"导出"） */
    fun generate(
        context: Context,
        before: Bitmap,
        after: Bitmap,
        filterKey: String,
        costMs: Long
    ): File {
        // 对比图统一缩到 480 宽，保证两张并排放得下
        val thumbBefore = scale(before, 480)
        val thumbAfter = scale(after, 480)
        val imgH = thumbBefore.height

        // 卡片高度 = 边距 + 标题 + 对比图 + 指标区 + 水印
        val pad = 48
        val titleH = 120
        val metricsH = 200
        val wmH = 80
        val cardH = pad + titleH + imgH + metricsH + wmH

        val card = Bitmap.createBitmap(CARD_W, cardH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(card)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 标题
        paint.color = Color.parseColor("#303F9F")
        paint.textSize = 52f
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(context.getString(R.string.card_title), pad.toFloat(), (pad + 60).toFloat(), paint)

        // 前后对比图（水平铺排，留 16px 间隔）
        val x1 = pad
        val x2 = pad + thumbBefore.width + 16
        val imgTop = pad + titleH
        canvas.drawBitmap(thumbBefore, x1.toFloat(), imgTop.toFloat(), null)
        canvas.drawBitmap(thumbAfter, x2.toFloat(), imgTop.toFloat(), null)
        paint.textSize = 34f
        paint.color = Color.parseColor("#757575")
        canvas.drawText("处理前", x1.toFloat(), (imgTop + 40).toFloat(), paint)
        paint.color = Color.parseColor("#303F9F")
        canvas.drawText("处理后", x2.toFloat(), (imgTop + 40).toFloat(), paint)

        // 量化指标：处理后图的灰度均值/标准差（直方图统计）
        val stats = Histogram().compute(pixelsOf(thumbAfter))
        paint.color = Color.parseColor("#333333")
        paint.textSize = 36f
        val baseY = imgTop + imgH + 70
        canvas.drawText(
            "滤镜：${FilterPreset.fromName(filterKey).displayName}   耗时：${costMs} ms",
            pad.toFloat(), baseY.toFloat(), paint
        )
        canvas.drawText(
            "灰度均值 μ=%.1f   标准差 σ=%.1f（对比度）".format(stats.mean, stats.stdDev),
            pad.toFloat(), (baseY + 60).toFloat(), paint
        )

        // 学号姓名水印（查重要求，必须可见）
        paint.color = Color.parseColor("#9E9E9E")
        paint.textSize = 30f
        canvas.drawText(
            context.getString(R.string.card_watermark),
            pad.toFloat(), (cardH - 30).toFloat(), paint
        )

        // 落盘到 FileProvider 白名单目录
        val file = File(ProcessEngine.shareDir(context), "card-${System.currentTimeMillis()}.png")
        file.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    private fun scale(src: Bitmap, width: Int): Bitmap {
        val scale = width.toFloat() / src.width
        return Bitmap.createScaledBitmap(src, width, (src.height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun pixelsOf(bmp: Bitmap): IntArray {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return px
    }

    /** 从路径读图（分享历史记录里的图时用） */
    fun loadBitmap(path: String): Bitmap? = BitmapFactory.decodeFile(path)
}
