package com.pixellab.app.ui.stats

import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixellab.app.PixelLabApp
import com.pixellab.app.data.db.PhotoEntity
import com.pixellab.app.image.Histogram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 统计页状态：记录列表 + 当前选中记录的前/后统计、RGB 分通道统计与 EXIF 文本 */
data class StatsState(
    val records: List<PhotoEntity> = emptyList(),
    val beforeStats: Histogram.Stats? = null,
    val afterStats: Histogram.Stats? = null,
    val rgbStats: Histogram.RgbStats? = null,
    val exifText: String? = null,
    /** 滤镜性能排行榜：历史记录按滤镜聚合（平均耗时 + 次数），柱状图数据源 */
    val board: List<BarChartView.Row> = emptyList()
)

class StatsViewModel(private val app: PixelLabApp) : ViewModel() {

    private val _state = MutableStateFlow(StatsState())
    val state: StateFlow<StatsState> = _state.asStateFlow()

    /**
     * 持续收集历史记录流（Room Flow）：
     * 编辑页保存新记录后统计页下拉框实时刷新，不用退出重进。
     */
    fun loadRecords() {
        if (recordsJob != null) return   // 已在收集，避免重复订阅
        recordsJob = viewModelScope.launch {
            app.db.photoDao().observeAll().collect { list ->
                _state.value = _state.value.copy(records = list, board = aggregate(list))
            }
        }
    }

    /**
     * 滤镜性能聚合：历史记录按滤镜名分组 → 平均耗时 + 使用次数。
     * 只统计真实处理记录（costMs > 0），空串 filterName（纯几何变换）跳过。
     */
    private fun aggregate(list: List<PhotoEntity>): List<BarChartView.Row> {
        if (list.isEmpty()) return emptyList()
        return list.filter { it.filterName.isNotBlank() && it.costMs > 0 }
            .groupBy { it.filterName }
            .map { (name, group) ->
                BarChartView.Row(name, group.map { it.costMs }.average().toFloat(), group.size)
            }
    }

    private var recordsJob: kotlinx.coroutines.Job? = null

    /**
     * 选中一条记录：分别统计"原图"和"结果图"。
     * 直方图是 O(N) 单遍扫描，在 Default 池跑，主线程只收结果。
     */
    fun select(photo: PhotoEntity) {
        viewModelScope.launch {
            val (before, after, rgb, exif) = withContext(Dispatchers.Default) {
                val hist = Histogram()
                val beforeBmp = BitmapFactory.decodeFile(photo.sourcePath)
                val afterBmp = BitmapFactory.decodeFile(photo.resultPath)
                val beforeStats = beforeBmp?.let {
                    val px = IntArray(it.width * it.height)
                    it.getPixels(px, 0, it.width, 0, 0, it.width, it.height)
                    hist.compute(px)
                }
                val afterStats = afterBmp?.let {
                    val px = IntArray(it.width * it.height)
                    it.getPixels(px, 0, it.width, 0, 0, it.width, it.height)
                    hist.compute(px)
                }
                // RGB 分通道直方图：统计"处理后"结果图（色彩分布的变化最有看头）
                val rgb = afterBmp?.let {
                    val px = IntArray(it.width * it.height)
                    it.getPixels(px, 0, it.width, 0, 0, it.width, it.height)
                    hist.computeRgb(px)
                }
                val exif = runCatching {
                    val e = androidx.exifinterface.media.ExifInterface(photo.sourcePath)
                    buildString {
                        appendLine("拍摄时间: ${e.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME) ?: "-"}")
                        appendLine("相机型号: ${e.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL) ?: "-"}")
                        appendLine("焦距: ${e.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH) ?: "-"}")
                        appendLine("光圈: ${e.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER) ?: "-"}")
                        appendLine("ISO: ${e.getAttribute("ISOSpeedRatings") ?: "-"}")
                        appendLine("方向: ${e.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)}")
                        appendLine("尺寸: ${e.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_WIDTH)}×${e.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_LENGTH)}")
                    }.trim()
                }.getOrDefault(null)
                Quad(beforeStats, afterStats, rgb, exif)
            }
            _state.value = _state.value.copy(
                beforeStats = before, afterStats = after, rgbStats = rgb, exifText = exif
            )
        }
    }

    /** 四元组：协程标准库只有 Triple，这里自带一个局部封装 */
    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    companion object {
        fun Factory(app: PixelLabApp) = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = StatsViewModel(app) as T
        }
    }
}
