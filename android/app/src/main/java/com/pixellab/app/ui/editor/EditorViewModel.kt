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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await

/**
 * 编辑页状态（参数快照模型）。
 *
 * 关键设计：滤镜效果由 (filterKey, strength, brightness, contrast) 这组参数
 * **完整决定**，每次都基于 base 重算而不是在 current 上叠加——
 *   1. 强度 50% 连按两次 = 一次 100%（幂等，不会越叠越深）；
 *   2. 换滤镜/调参数天然覆盖旧效果，无"不可逆"的叠加历史；
 *   3. 旋转/裁剪后滤镜自动重算，不会因为几何变换而丢失。
 *
 * 三层图像：
 *   source —— 最初打开的原图，永远不动（还原的基准）
 *   base   —— 几何变换（旋转/裁剪）后的底图，滤镜作用对象
 *   current—— base + 滤镜参数重算的结果，展示用
 *
 * Bitmap 放在 ViewModel 里：旋转屏幕重建 Activity/Fragment 时图片不重新解码。
 */
data class EditorState(
    val source: Bitmap? = null,      // 处理前（还原基准）
    val base: Bitmap? = null,        // 几何变换后的底图（滤镜作用对象）
    val current: Bitmap? = null,     // 处理后（base + 参数重算的结果）
    val filterKey: String = "SHARPEN",
    val strength: Float = 1f,        // 滤镜强度 0~1（lerp 混合系数）
    val brightness: Int = 0,         // 亮度偏移 -100~100
    val contrast: Int = 0,           // 对比度 0~100（增益 0.5~1.5）
    val costMs: Long = 0,            // 最近一次处理耗时
    val busy: Boolean = false,       // 卷积/识别进行中
    val ocrText: String? = null,     // ML Kit 识别结果
    val savedId: Long? = null,       // 保存成功后的历史记录 id
    val canUndo: Boolean = false     // 撤销栈非空
)

class EditorViewModel(private val app: PixelLabApp) : ViewModel() {

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    /** 编辑最长边：设置页可调，默认 1280 */
    private var maxEdge = 1280

    /**
     * 撤销栈：每次修改性操作（应用滤镜/旋转/裁剪/还原）前压入完整快照。
     * 只存 Bitmap 引用和参数副本，不拷贝像素，压栈本身零开销。
     */
    private data class Snapshot(
        val base: Bitmap?,
        val current: Bitmap?,
        val filterKey: String,
        val strength: Float,
        val brightness: Int,
        val contrast: Int
    )
    private val undoStack = ArrayDeque<Snapshot>(MAX_UNDO)

    /** 最近一次成功"应用"的参数快照（current 置 null）：同参数重复点击直接跳过，幂等零开销 */
    private var lastApplied: Snapshot? = null

    /** 修改操作前压栈；栈满丢最旧的一步 */
    private fun pushUndo(s: EditorState) {
        if (undoStack.size >= MAX_UNDO) undoStack.removeFirst()
        undoStack.addLast(
            Snapshot(s.base, s.current, s.filterKey, s.strength, s.brightness, s.contrast)
        )
    }

    /** 撤销一步：弹出最近的快照恢复（滤镜参数 + 底图 + 结果图一起回退） */
    fun undo() {
        val snap = undoStack.removeLastOrNull() ?: return
        lastApplied = null   // 回退后允许以相同参数重新应用
        _state.value = _state.value.copy(
            base = snap.base,
            current = snap.current,
            filterKey = snap.filterKey,
            strength = snap.strength,
            brightness = snap.brightness,
            contrast = snap.contrast,
            ocrText = null,
            canUndo = undoStack.isNotEmpty()
        )
    }

    /** 打开一张源图（路径来自图库跳转 / 相机拍摄） */
    fun load(path: String) {
        if (_state.value.source != null) return   // 已加载（旋转屏幕恢复），不重复解码
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val bmp = withContext(Dispatchers.IO) {
                maxEdge = app.settings.editMaxEdge.first()
                ProcessEngine.loadForEdit(path, maxEdge)
            }
            undoStack.clear()
            lastApplied = null
            _state.value = EditorState(source = bmp, base = bmp, current = bmp)
        }
    }

    /** 切换选中滤镜（只改选中态，点"应用"才真算） */
    fun selectFilter(key: String) {
        _state.value = _state.value.copy(filterKey = key)
    }

    /** 更新滤镜强度（只记值，松手后由 Fragment 触发重算） */
    fun setStrength(v: Float) {
        _state.value = _state.value.copy(strength = v)
    }

    /** 更新亮度偏移（-100~100，松手后由 Fragment 触发重算） */
    fun setBrightness(v: Int) {
        _state.value = _state.value.copy(brightness = v)
    }

    /** 更新对比度（0~100，松手后由 Fragment 触发重算） */
    fun setContrast(v: Int) {
        _state.value = _state.value.copy(contrast = v)
    }

    /**
     * 按"base + 完整参数快照"重算结果。
     * Dispatchers.Default：CPU 密集的卷积放到默认并行池（线程数 = CPU 核数），
     * 主线程只收结果更新 UI——图片再大界面也不卡。
     */
    fun applyFilter() {
        val s = _state.value
        val base = s.base ?: return
        // 幂等判重：参数快照与上次应用完全相同且已有结果 → 直接跳过
        // （"强度 50% 连按两次"既不叠加、也不重复卷积/压栈）
        val target = Snapshot(base, null, s.filterKey, s.strength, s.brightness, s.contrast)
        if (lastApplied == target && s.current != null && s.current !== s.base) return
        pushUndo(s)
        lastApplied = target
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val (bmp, cost) = withContext(Dispatchers.Default) {
                ProcessEngine.applyFilter(base, s.filterKey, s.strength, s.brightness, s.contrast)
            }
            _state.value = _state.value.copy(
                current = bmp, costMs = cost, busy = false, canUndo = true
            )
        }
    }

    /**
     * 旋转 90°：先压栈，再更新 base，然后基于新 base 自动重算滤镜结果——
     * 几何变换不会丢掉已调好的滤镜效果（参数快照模型的直接收益）。
     */
    fun rotate() {
        val s = _state.value
        val src = s.base ?: return
        pushUndo(s)
        viewModelScope.launch {
            val base = withContext(Dispatchers.Default) { ProcessEngine.rotate(src) }
            val (bmp, cost) = withContext(Dispatchers.Default) {
                ProcessEngine.applyFilter(base, s.filterKey, s.strength, s.brightness, s.contrast)
            }
            _state.value = _state.value.copy(
                base = base, current = bmp, costMs = cost,
                ocrText = null, canUndo = true
            )
        }
    }

    /** 居中裁剪：同 rotate，压栈 → 更新 base → 重算 */
    fun crop(ratio: String) {
        val s = _state.value
        val src = s.base ?: return
        pushUndo(s)
        viewModelScope.launch {
            val base = withContext(Dispatchers.Default) { ProcessEngine.crop(src, ratio) }
            val (bmp, cost) = withContext(Dispatchers.Default) {
                ProcessEngine.applyFilter(base, s.filterKey, s.strength, s.brightness, s.contrast)
            }
            _state.value = _state.value.copy(
                base = base, current = bmp, costMs = cost,
                ocrText = null, canUndo = true
            )
        }
    }

    /** 还原到最初打开的样子（压栈后清空几何与全部参数，还原本身也可撤销） */
    fun reset() {
        val s = _state.value
        pushUndo(s)
        lastApplied = null
        _state.value = s.copy(
            base = s.source, current = s.source,
            filterKey = "SHARPEN", strength = 1f, brightness = 0, contrast = 0,
            ocrText = null, costMs = 0, canUndo = true
        )
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
                    opsJson = ProcessEngine.buildOpsJson(
                        "filter" to s.filterKey,
                        "strength" to s.strength.toString(),
                        "brightness" to s.brightness.toString(),
                        "contrast" to s.contrast.toString()
                    ),
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
        /** 撤销深度上限，防 Bitmap 引用堆积占内存 */
        private const val MAX_UNDO = 10

        fun Factory(app: PixelLabApp) = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                EditorViewModel(app) as T
        }
    }
}
