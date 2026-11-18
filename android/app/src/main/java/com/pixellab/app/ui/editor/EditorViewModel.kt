package com.pixellab.app.ui.editor

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixellab.app.PixelLabApp
import com.pixellab.app.data.db.PhotoEntity
import com.pixellab.app.image.FilterPreset
import com.pixellab.app.image.ProcessEngine
import com.pixellab.app.image.Transformer
import com.pixellab.app.widget.RecentWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await

/**
 * 编辑页状态。
 * Bitmap 放在 ViewModel 里：旋转屏幕重建 Activity/Fragment 时图片不重新解码——
 * 这正是"ViewModel 处理异步与生命周期"要求的核心收益（大 Bitmap 加载要几百 ms）。
 */
data class EditorState(
    val source: Bitmap? = null,      // 处理前（还原基准）
    val current: Bitmap? = null,     // 处理后（当前工作副本）
    val filterKey: String = "SHARPEN",
    val costMs: Long = 0,            // 最近一次处理耗时
    val busy: Boolean = false,       // 卷积/识别进行中
    val ocrText: String? = null,     // ML Kit 识别结果
    val savedId: Long? = null        // 保存成功后的历史记录 id
)

class EditorViewModel(private val app: PixelLabApp) : ViewModel() {

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    /** 编辑最长边：设置页可调，默认 1280 */
    private var maxEdge = 1280

    /** 打开一张源图（路径来自图库跳转 / 相机拍摄） */
    fun load(path: String) {
        if (_state.value.source != null) return   // 已加载（旋转屏幕恢复），不重复解码
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val bmp = withContext(Dispatchers.IO) {
                maxEdge = kotlinx.coroutines.flow.first(app.settings.editMaxEdge)
                ProcessEngine.loadForEdit(path, maxEdge)
            }
            _state.value = EditorState(source = bmp, current = bmp)
        }
    }

    /** 切换选中滤镜（只改选中态，点"应用"才真算） */
    fun selectFilter(key: String) {
        _state.value = _state.value.copy(filterKey = key)
    }

    /**
     * 应用当前滤镜。
     * Dispatchers.Default：CPU 密集的卷积放到默认并行池（线程数 = CPU 核数），
     * 主线程只收结果更新 UI——图片再大界面也不卡。
     */
    fun applyFilter() {
        val src = _state.value.current ?: return
        val key = _state.value.filterKey
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val (bmp, cost) = withContext(Dispatchers.Default) {
                ProcessEngine.applyFilter(src, key)
            }
            _state.value = _state.value.copy(current = bmp, costMs = cost, busy = false)
        }
    }

    /** 旋转 90°：Matrix 变换本身就是池里的轻量操作，直接 Default 池跑 */
    fun rotate() {
        val src = _state.value.current ?: return
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.Default) { ProcessEngine.rotate(src) }
            _state.value = _state.value.copy(current = bmp, ocrText = null)
        }
    }

    /** 居中裁剪 */
    fun crop(ratio: String) {
        val src = _state.value.current ?: return
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.Default) { ProcessEngine.crop(src, ratio) }
            _state.value = _state.value.copy(current = bmp, ocrText = null)
        }
    }

    /** 还原到最初打开的样子（丢弃所有未保存的编辑） */
    fun reset() {
        _state.value = _state.value.copy(current = _state.value.source, ocrText = null, costMs = 0)
    }

    /**
     * ML Kit 中文离线识别。
     * InputImage.fromBitmap 直接喂 Bitmap（不需要再走文件），
     * 模型是 bundled 的，无网可用。
     */
    fun runOcr() {
        val bmp = _state.value.current ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val text = withContext(Dispatchers.Default) {
                val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0)
                val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                    com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build()
                )
                // Task.await()（来自 coroutines-play-services）把回调转 suspend，避免手写嵌套
                val result = recognizer.process(image).await()
                recognizer.close()
                result.text
            }
            _state.value = _state.value.copy(busy = false, ocrText = text.ifBlank { null })
        }
    }

    /**
     * 保存：结果图 + 缩略图落盘 → 写 Room → 通知 P1 小组件刷新。
     * photoId >= 0 表示"在已有记录上继续编辑"，这里统一新增一条记录
     * （处理链的每一步都可追溯，也符合"历史记录"语义）。
     */
    fun save() {
        val s = _state.value
        val bmp = s.current ?: return
        viewModelScope.launch {
            val sourcePath = withContext(Dispatchers.IO) {
                // 源图如果还没落盘（相机新拍的），先存一份
                ProcessEngine.saveBitmap(app, s.source!!, "src")
            }
            val resultPath = withContext(Dispatchers.IO) {
                ProcessEngine.saveBitmap(app, bmp, "result")
            }
            val thumbPath = withContext(Dispatchers.IO) {
                val thumb = Transformer.makeThumbnail(bmp)
                ProcessEngine.saveBitmap(app, thumb, "thumb", jpeg = true)
            }
            val id = app.db.photoDao().insert(
                PhotoEntity(
                    sourcePath = sourcePath,
                    resultPath = resultPath,
                    thumbPath = thumbPath,
                    filterName = s.filterKey,
                    opsJson = ProcessEngine.buildOpsJson("filter" to s.filterKey),
                    ocrText = s.ocrText,
                    width = bmp.width,
                    height = bmp.height,
                    costMs = s.costMs,
                    createdAt = System.currentTimeMillis()
                )
            )
            RecentWidgetProvider.notifyDataChanged(app)   // 让桌面小组件立刻拿到新图
            _state.value = s.copy(savedId = id)
        }
    }

    /** 保存提示展示后清掉标记，避免旋转屏幕重复弹 */
    fun clearSaved() {
        _state.value = _state.value.copy(savedId = null)
    }

    /** 手工依赖注入的工厂：ViewModelProvider 通过它拿带参构造的 ViewModel */
    companion object {
        fun Factory(app: PixelLabApp) = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                EditorViewModel(app) as T
        }
    }
}
