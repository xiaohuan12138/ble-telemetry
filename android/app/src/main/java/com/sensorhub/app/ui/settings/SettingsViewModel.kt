package com.sensorhub.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sensorhub.app.SensorHubApp
import com.sensorhub.app.ble.BleConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 设置页 ViewModel
 *
 * 所有开关都直接写入 DataStore，并由 Flow 回流到 UI。
 * 这是"单向数据流"的典型形态：
 *   UI 事件 → ViewModel 改数据 → 数据变化 → UI 重新渲染
 * 而不是"UI 直接改 UI 状态"，后者在多页面同步时会出乱子。
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settings get() = SensorHubApp.instance.settings

    private val _mockEnabled = MutableStateFlow(false)
    val mockEnabled: StateFlow<Boolean> = _mockEnabled.asStateFlow()

    private val _autoReconnect = MutableStateFlow(true)
    val autoReconnect: StateFlow<Boolean> = _autoReconnect.asStateFlow()

    private val _notifyEnabled = MutableStateFlow(true)
    val notifyEnabled: StateFlow<Boolean> = _notifyEnabled.asStateFlow()

    private val _manualMac = MutableStateFlow("")
    val manualMac: StateFlow<String> = _manualMac.asStateFlow()

    init {
        viewModelScope.launch {
            settings.mockEnabled.collect {
                _mockEnabled.value = it
                // 切换数据源时，把已有的真实连接全部断开，避免两套数据混在一起
                if (it) BleConnectionManager.disconnectAll()
            }
        }
        viewModelScope.launch {
            settings.autoReconnect.collect {
                _autoReconnect.value = it
                // 同步给连接管理器：所有连接的重连策略一起变
                BleConnectionManager.autoReconnect = it
            }
        }
        viewModelScope.launch { settings.notifyEnabled.collect { _notifyEnabled.value = it } }
        viewModelScope.launch { settings.manualMac.collect { _manualMac.value = it } }
    }

    fun setMockEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setMockEnabled(enabled) }
    }

    fun setAutoReconnect(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoReconnect(enabled) }
    }

    fun setNotifyEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setNotifyEnabled(enabled) }
    }

    fun applyManualMac(mac: String) {
        viewModelScope.launch { settings.setManualMac(mac) }
    }

    /** 关于页面的文案：把关键技术选型写在这里，答辩时可以直接翻给老师看 */
    val aboutText: String
        get() = """
            传感中枢 SensorHub v1.0.0
            
            【下位机】ESP32-WROOM-32
              · AHT20 温湿度 / BMP280 气压 / BH1750 光照
              · NimBLE GATT Server，自定义二进制协议（CRC16 校验）
            
            【上位机】原生 Android（Kotlin）
              · Jetpack：Navigation / Room / ViewModel / Lifecycle / WorkManager
              · 协程 + Flow 贯穿全链路
              · 自定义 View 绘制实时曲线
            
            【可靠性设计】
              · 指数退避 + 随机抖动的多设备重连
              · CRC16 帧校验 + SEQ 丢包检测
              · 三级权限降级：拒蓝牙权限→手动 MAC 直连；
                拒通知权限→App 内横幅；无硬件→内置模拟数据源
        """.trimIndent()
}
