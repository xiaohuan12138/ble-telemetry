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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 统计页状态：记录列表 + 当前选中记录的前/后统计与 EXIF 文本 */
data class StatsState(
    val records: List<PhotoEntity> = emptyList(),
    val beforeStats: Histogram.Stats? = null,
    val afterStats: Histogram.Stats? = null,
    val exifText: String? = null
)

class StatsViewModel(private val app: PixelLabApp) : ViewModel() {

    private val _state = MutableStateFlow(StatsState())
    val state: StateFlow<StatsState> = _state.asStateFlow()

    /** 加载记录下拉列表 */
    fun loadRecords() {
        viewModelScope.launch {
            val list = app.db.photoDao().observeAll().first()
            _state.value = _state.value.copy(records = list)
        }
    }

    /**
     * 选中一条记录：分别统计"原图"和"结果图"。
     * 直方图是 O(N) 单遍扫描，在 Default 池跑，主线程只收结果。
     */
    fun select(photo: PhotoEntity) {
        viewModelScope.launch {
            val (before, after, exif) = withContext(Dispatchers.Default) {
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
                Triple(beforeStats, afterStats, exif)
            }
            _state.value = _state.value.copy(
                beforeStats = before, afterStats = after, exifText = exif
            )
        }
    }

    companion object {
        fun Factory(app: PixelLabApp) = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = StatsViewModel(app) as T
        }
    }
}
