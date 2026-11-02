package com.sensorhub.app.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sensorhub.app.SensorHubApp
import com.sensorhub.app.data.db.ReadingEntity
import com.sensorhub.app.data.db.RangeStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 历史页 ViewModel
 *
 * 这里演示一个简单的工程决策：历史数据不实时跟随数据库变化。
 * 理由：采样是 1Hz 的，如果数据库 Flow 每来一条就刷新列表，
 * 列表会疯狂重绘。所以这里用 suspend + 手动加载（loadStats），
 * 只在进入页面时查一次。
 */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository get() = SensorHubApp.instance.repository

    private val _readings = MutableStateFlow<List<ReadingEntity>>(emptyList())
    val readings: StateFlow<List<ReadingEntity>> = _readings.asStateFlow()

    private val _stats = MutableStateFlow<RangeStats?>(null)
    val stats: StateFlow<RangeStats?> = _stats.asStateFlow()

    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    init {
        observe()
        loadStats()
    }

    private fun observe() {
        viewModelScope.launch {
            repository.observeReadings(200).collect { _readings.value = it }
        }
    }

    /** 统计最近 24 小时的极值 */
    fun loadStats() {
        viewModelScope.launch {
            val since = System.currentTimeMillis() - 24 * 60 * 60 * 1000
            _stats.value = repository.statsSince(since)
            _count.value = repository.readingCount()
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            repository.clearReadings()
            loadStats()
        }
    }

    /**
     * 裁剪老数据
     * 通常由 WorkManager 定期调用，这里也提供手动入口方便演示
     */
    fun trim() {
        viewModelScope.launch {
            repository.trimReadings(5000)
            loadStats()
        }
    }
}
