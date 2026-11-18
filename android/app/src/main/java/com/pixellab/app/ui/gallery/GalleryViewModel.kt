package com.pixellab.app.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixellab.app.data.db.PhotoEntity
import com.pixellab.app.PixelLabApp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 图库页 ViewModel：观察 Room 的 Flow。
 *
 * stateIn(WhileSubscribed(5000))：页面可见才收集，离开 5 秒后停止，
 * 回来重新订阅——旋转屏幕数据不丢，后台不空耗电，这是协程 + ViewModel
 * 生命周期处理的教科书写法。
 */
class GalleryViewModel(app: PixelLabApp) : ViewModel() {

    private val dao = app.db.photoDao()

    val photos: StateFlow<List<PhotoEntity>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 删除记录 + 磁盘文件（缩略图/结果图，原图不动——可能还在相册里） */
    fun delete(app: PixelLabApp, photo: PhotoEntity) {
        viewModelScope.launch {
            dao.deleteById(photo.id)
            runCatching {
                java.io.File(photo.resultPath).delete()
                java.io.File(photo.thumbPath).delete()
            }
        }
    }

    companion object {
        fun Factory(app: PixelLabApp) = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = GalleryViewModel(app) as T
        }
    }
}
