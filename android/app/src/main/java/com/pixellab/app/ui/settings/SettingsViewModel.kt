package com.pixellab.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixellab.app.PixelLabApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置页状态：历史条数 + 各项配置当前值 */
data class SettingsState(
    val count: Int = 0,
    val defaultFilter: String = "SHARPEN",
    val editEdge: Int = 1280,
    val autoShare: Boolean = false
)

class SettingsViewModel(private val app: PixelLabApp) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    /** 进页面加载当前配置 + 历史条数 */
    fun load() {
        viewModelScope.launch {
            val filter = app.settings.defaultFilter.first()
            val edge = app.settings.editMaxEdge.first()
            val auto = app.settings.autoShare.first()
            val count = withContext(Dispatchers.IO) { app.db.photoDao().count() }
            _state.value = SettingsState(count, filter, edge, auto)
        }
    }

    fun setDefaultFilter(name: String) = viewModelScope.launch {
        app.settings.setDefaultFilter(name)
        _state.value = _state.value.copy(defaultFilter = name)
    }

    fun setEditEdge(edge: Int) = viewModelScope.launch {
        app.settings.setEditMaxEdge(edge)
        _state.value = _state.value.copy(editEdge = edge)
    }

    fun setAutoShare(on: Boolean) = viewModelScope.launch {
        app.settings.setAutoShare(on)
        _state.value = _state.value.copy(autoShare = on)
    }

    /**
     * 清空历史：删 Room 全表 + 删私有目录下所有图片文件。
     * 返回删除条数给 UI 弹确认（先查再删，两步都走 IO）。
     */
    fun clearAll(onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                val n = app.db.photoDao().count()
                app.db.photoDao().deleteAll()
                com.pixellab.app.image.ProcessEngine.imageDir(app).listFiles()?.forEach { it.delete() }
                n
            }
            _state.value = _state.value.copy(count = 0)
            onDone(count)
        }
    }

    companion object {
        fun Factory(app: PixelLabApp) = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(app) as T
        }
    }
}
