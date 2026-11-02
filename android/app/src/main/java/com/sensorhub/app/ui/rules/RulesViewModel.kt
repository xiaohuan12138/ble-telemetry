package com.sensorhub.app.ui.rules

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sensorhub.app.SensorHubApp
import com.sensorhub.app.domain.AutomationRule
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 规则列表 ViewModel
 *
 * 这一页几乎没逻辑，但演示了一个重要模式：
 *   Room 返回的 Flow<List> 用 stateIn 转成 StateFlow，
 *   这样 UI 可以直接在 xml / Compose 里观察，而且多个收集者共享
 *   同一个数据源（数据库只被查询一次）。
 *
 * stateIn 的三个参数：
 *   scope         —— 协程作用域，这里用 viewModelScope
 *   started       —— SharingStarted.WhileSubscribed(5000)：
 *                    有人订阅才开始，最后一个订阅者消失后
 *                    再等 5 秒才停止（避免屏幕旋转造成的一停一启）
 *   initialValue  —— 首帧默认值，避免 UI 出现"无值"状态
 */
class RulesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository get() = SensorHubApp.instance.repository

    val rules: StateFlow<List<AutomationRule>> =
        repository.observeRules()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addRule(rule: AutomationRule) {
        viewModelScope.launch { repository.saveRule(rule) }
    }

    fun deleteRule(id: Long) {
        viewModelScope.launch { repository.deleteRule(id) }
    }

    fun toggleRule(id: Long, enabled: Boolean) {
        viewModelScope.launch { repository.setRuleEnabled(id, enabled) }
    }
}
