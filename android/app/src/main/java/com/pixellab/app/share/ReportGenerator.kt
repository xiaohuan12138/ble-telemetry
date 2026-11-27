package com.pixellab.app.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import android.content.Intent
import com.pixellab.app.R
import com.pixellab.app.image.FilterCatalog
import com.pixellab.app.image.Histogram
import com.pixellab.app.image.ProcessEngine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln

/**
 * PDF 实验报告导出（Report Generator）。
 *
 * 答辩材料现成：处理前后对比图 + 参数 + 耗时 + 前后直方图曲线 +
 * RGB 三通道分布 + 全套量化指标表，一键成 A4 双页 PDF。
 * 用 android.graphics.pdf.PdfDocument（框架自带，零第三方依赖），
 * 输出经 FileProvider 走系统分享/另存。
 *
 * A4 = 595×842 pt（PDF 点单位，72dpi）。
 */
object ReportGenerator {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private val margin = 40f
    private val indigo = Color.parseColor("#303F9F")
    private val grayText = Color.parseColor("#555555")
    private val beforeColor = Color.parseColor("#EF5350")   // 处理前（红），与统计页同色系
    private val afterColor = Color.parseColor("#303F9F")    // 处理后（主色）

    /** 生成 PDF 并调起分享面板（查看 / 保存到文件管理器都可以） */
    fun sharePdfReport(
        context: Context,
        before: Bitmap,
        after: Bitmap,
        filterKey: String,
        strength: Float,
        brightness: Int,
        contrast: Int,
        costMs: Long
    ) {
        val file = generate(context, before, after, filterKey, strength, brightness, contrast, costMs)
        val uri = FileProvider.getUriForFile(context, "com.pixellab.app.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_via)))
    }

    /** 生成 PDF 文件，返回文件句柄 */
    fun generate(
        context: Context,
        before: Bitmap,
        after: Bitmap,
        filterKey: String,
        strength: Float,
        brightness: Int,
        contrast: Int,
        costMs: Long
    ): File {
        val doc = PdfDocument()

        // 统计（缩到 480 宽再算，指标与全图基本一致，速度快一个量级）
        val b480 = scale(before, 480)
        val a480 = scale(after, 480)
        val hist = Histogram()
        val beforeStats = hist.compute(pixelsOf(b480))
        val afterStats = hist.compute(pixelsOf(a480))
        val rgb = hist.computeRgb(pixelsOf(a480))

        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())

        // ═══ 第 1 页：标题 + 参数 + 前后对比图 + 前后直方图 ═══
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
        var c = page.canvas
        var y = drawHeader(c, time)

        c.drawText("滤镜：${FilterCatalog.displayName(filterKey)}    强度：${(strength * 100).toInt()}%", margin, y, paint(11f, grayText)); y += 18f
        c.drawText("亮度：$brightness    对比度：$contrast    处理耗时：$costMs ms（多线程分块卷积）", margin, y, paint(11f, grayText)); y += 24f

        // 前后对比图（各 240pt 宽并排，等比缩放）
        val imgW = 240f
        val bScaled = scale(before, imgW.toInt())
        val aScaled = scale(after, imgW.toInt())
        val imgH = minOf(bScaled.height.toFloat(), aScaled.height.toFloat(), 240f)
        val leftX = margin
        val rightX = PAGE_W - margin - imgW
        c.drawBitmap(Bitmap.createScaledBitmap(b480, imgW.toInt(), imgH.toInt(), true), leftX, y, null)
        c.drawBitmap(Bitmap.createScaledBitmap(a480, imgW.toInt(), imgH.toInt(), true), rightX, y, null)
        c.drawText("处理前", leftX, y - 6f, paint(9f, beforeColor))
        c.drawText("处理后", rightX, y - 6f, paint(9f, afterColor))
        y += imgH + 26f

        c.drawText("处理前 / 处理后 灰度直方图（对数刻度）", margin, y, paint(11f, indigo, bold = true)); y += 10f
        val histH = 130f
        val halfW = (PAGE_W - 2 * margin - 20f) / 2
        drawHistCurve(c, beforeStats.bins, margin, y, halfW, histH, beforeColor)
        drawHistCurve(c, afterStats.bins, margin + halfW + 20f, y, halfW, histH, afterColor)
        c.drawText("处理前", margin, y + histH + 14f, paint(9f, grayText))
        c.drawText("处理后", margin + halfW + 20f, y + histH + 14f, paint(9f, grayText))
        y += histH + 30f

        c.drawText("RGB 三通道分布（处理后，对数刻度）", margin, y, paint(11f, indigo, bold = true)); y += 10f
        drawHistCurve(c, rgb.rBins, margin, y, halfW, histH * 0.8f, Color.parseColor("#EF4444"))
        drawHistCurve(c, rgb.gBins, margin, y, halfW, histH * 0.8f, Color.parseColor("#22C55E"))
        drawHistCurve(c, rgb.bBins, margin, y, halfW, histH * 0.8f, Color.parseColor("#3B82F6"))
        c.drawText("R / G / B 叠画", margin, y + histH * 0.8f + 14f, paint(9f, grayText))

        doc.finishPage(page)

        // ═══ 第 2 页：量化指标对比表 ═══
        page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 2).create())
        c = page.canvas
        y = drawHeader(c, time)
        c.drawText("量化指标对比（处理前 → 处理后）", margin, y, paint(12f, indigo, bold = true)); y += 24f

        val rows = listOf(
            "灰度均值 μ" to fmt(beforeStats.mean, afterStats.mean),
            "标准差 σ（对比度）" to fmt(beforeStats.stdDev, afterStats.stdDev),
            "最暗 / 最亮" to "${beforeStats.minGray}/${beforeStats.maxGray} → ${afterStats.minGray}/${afterStats.maxGray}",
            "中位数" to "${beforeStats.median} → ${afterStats.median}",
            "众数" to "${beforeStats.mode} → ${afterStats.mode}",
            "信息熵 bit/像素" to fmt(beforeStats.entropy, afterStats.entropy),
            "过曝率 >245" to pct(beforeStats.blowoutRatio, afterStats.blowoutRatio),
            "欠曝率 <10" to pct(beforeStats.darkRatio, afterStats.darkRatio),
            "R 均值" to fmt(beforeStats.meanR, afterStats.meanR),
            "G 均值" to fmt(beforeStats.meanG, afterStats.meanG),
            "B 均值" to fmt(beforeStats.meanB, afterStats.meanB)
        )
        // 表头
        c.drawText("指标", margin, y, paint(10f, grayText, bold = true))
        c.drawText("处理前", margin + 200f, y, paint(10f, beforeColor, bold = true))
        c.drawText("处理后", margin + 320f, y, paint(10f, afterColor, bold = true))
        y += 8f
        c.drawLine(margin, y, PAGE_W - margin, y, Paint().apply { color = Color.parseColor("#CCCCCC"); strokeWidth = 1f })
        y += 16f
        for ((label, value) in rows) {
            c.drawText(label, margin, y, paint(10f, grayText))
            c.drawText(value, margin + 200f, y, paint(10f, Color.parseColor("#333333")))
            y += 22f
        }

        y += 16f
        c.drawText("结论", margin, y, paint(11f, indigo, bold = true)); y += 18f
        val dMu = afterStats.mean - beforeStats.mean
        val dSigma = afterStats.stdDev - beforeStats.stdDev
        val conclusion = buildString {
            append("本次处理平均亮度变化 %+.1f，对比度（σ）变化 %+.1f。".format(dMu, dSigma))
            append(if (dSigma > 0) "分布整体拉开，对比度增强；" else "分布整体收窄，图像趋于平滑/均衡；")
            append("信息熵由 %.2f 变为 %.2f，".format(beforeStats.entropy, afterStats.entropy))
            append(if (afterStats.entropy >= beforeStats.entropy) "图像信息量保持/增加。" else "高频细节有所损失。")
        }
        for (line in wrap(conclusion, 46)) {
            c.drawText(line, margin, y, paint(10f, grayText)); y += 16f
        }

        y += 24f
        c.drawText(
            context.getString(R.string.card_watermark) + "  ·  PixelLab 自动生成",
            margin, y, paint(9f, Color.parseColor("#9E9E9E"))
        )
        doc.finishPage(page)

        val file = File(ProcessEngine.shareDir(context), "report-${System.currentTimeMillis()}.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    // ────────────────────────── 绘制辅助 ──────────────────────────

    /** 页眉：标题 + 时间戳，返回正文起始 y */
    private fun drawHeader(c: Canvas, time: String): Float {
        var y = margin + 20f
        c.drawText("PixelLab 图像处理实验报告", margin, y, paint(18f, indigo, bold = true))
        y += 18f
        c.drawText("生成时间：$time", margin, y, paint(9f, grayText))
        y += 26f
        c.drawLine(margin, y, PAGE_W - margin, y, Paint().apply { color = Color.parseColor("#CCCCCC"); strokeWidth = 1f })
        return y + 20f
    }

    /**
     * 直方图曲线（对数归一，与统计页 HistogramView 同一套算法）：
     * 256 bin → 桶数 = 宽度，h = ln(1+count)/ln(1+max)，画渐变面积 + 顶部轮廓。
     */
    private fun drawHistCurve(
        c: Canvas, bins: IntArray, left: Float, top: Float, w: Float, h: Float, color: Int
    ) {
        val bucketCount = 64
        val buckets = IntArray(bucketCount)
        var maxVal = 1L
        for (i in 0 until 256) {
            val b = i * bucketCount / 256
            buckets[b] += bins[i]
            if (buckets[b] > maxVal) maxVal = buckets[b].toLong()
        }
        val logMax = ln(maxVal.toDouble() + 1.0).toFloat()
        val barW = w / bucketCount

        // 网格（注意参数名叫 color，Paint 属性必须用 this.color 显式消歧）
        val grid = Paint().apply { this.color = Color.parseColor("#E0E0E0"); strokeWidth = 1f }
        for (i in 1..3) c.drawLine(left, top + h * i / 4, left + w, top + h * i / 4, grid)

        val area = Path()
        val line = Path()
        area.moveTo(left, top + h)
        var started = false
        for (b in 0 until bucketCount) {
            if (buckets[b] == 0 && !started) continue
            val norm = ln(buckets[b].toDouble() + 1.0).toFloat() / logMax
            val yy = top + h - norm * h * 0.92f
            if (!started) { line.moveTo(left + b * barW, yy); started = true }
            else line.lineTo(left + b * barW, yy)
            area.lineTo(left + b * barW, yy)
        }
        area.lineTo(left + w, top + h)
        area.close()
        c.drawPath(area, Paint().apply {
            this.color = color; alpha = 60; style = Paint.Style.FILL; isAntiAlias = true
        })
        c.drawPath(line, Paint().apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true
        })
        c.drawText("0", left, top + h + 12f, paint(7f, grayText))
        c.drawText("255", left + w - 14f, top + h + 12f, paint(7f, grayText))
    }

    private fun paint(size: Float, color: Int, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun fmt(a: Float, b: Float) = "%.1f → %.1f".format(Locale.CHINA, a, b)
    private fun pct(a: Float, b: Float) = "%.1f%% → %.1f%%".format(Locale.CHINA, a * 100, b * 100)

    /** 简易按字符宽度换行（中文按 46 字符/行近似） */
    private fun wrap(text: String, width: Int): List<String> {
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        for (ch in text) {
            cur.append(ch)
            if (cur.length >= width) { out.add(cur.toString()); cur = StringBuilder() }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun scale(src: Bitmap, width: Int): Bitmap {
        val s = width.toFloat() / src.width
        return Bitmap.createScaledBitmap(src, width, (src.height * s).toInt().coerceAtLeast(1), true)
    }

    private fun pixelsOf(bmp: Bitmap): IntArray {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return px
    }
}
