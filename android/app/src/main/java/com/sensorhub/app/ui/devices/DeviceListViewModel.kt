package com.sensorhub.app.ui.devices

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sensorhub.app.ble.BleDevice
import com.sensorhub.app.ble.BleScanner
import com.sensorhub.app.ui.common.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 设备列表 ViewModel
 *
 * 职责：把"扫描 BLE 设备"这件异步、易失败、有时序要求的事情，
 *       包装成一个可以被 UI 安全观察的状态流。
 *
 * 为什么用 ViewModel 而不是直接写在 Fragment 里？
 *   屏幕旋转时 Fragment 会重建，但 ViewModel 不会（它活在
 *   ViewModelStore 里，直到 Activity 真正销毁）。
 *   如果扫描逻辑写在 Fragment 里，转一下屏就要重新扫描一次，
 *   已扫到的设备列表也丢了。
 */
class DeviceListViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = BleScanner(application)

    /** 扫描得到的所有设备（去重后） */
    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices: StateFlow<List<BleDevice>> = _devices.asStateFlow()

    private val _uiState = MutableStateFlow<UiState<List<BleDevice>>>(UiState.Empty)
    val uiState: StateFlow<UiState<List<BleDevice>>> = _uiState.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var scanJob: Job? = null
    private val seen = LinkedHashMap<String, BleDevice>()

    /**
     * 开始扫描
     *
     * 两个细节：
     *  ① 去重：同一个设备每秒会被扫到好几次（RSSI 每次略有不同），
     *     用 address 做 key 存进 Map，只保留最新一条
     *  ② catch：把 Flow 的异常转成 Error 状态，而不是让整个协程崩掉。
     *     蓝牙相关的异常非常常见（权限被撤、蓝牙被关、系统限流），
     *     必须在这里兜住
     */
    fun startScan() {
        if (_isScanning.value) return

        if (!scanner.hasScanPermission()) {
            _uiState.value = UiState.Error("没有蓝牙扫描权限")
            return
        }
        if (!scanner.isBluetoothEnabled()) {
            _uiState.value = UiState.Error("蓝牙未开启")
            return
        }

        seen.clear()
        _isScanning.value = true
        _uiState.value = UiState.Loading

        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanner.scan(timeoutMs = 15_000L)
                .catch { e ->
                    _isScanning.value = false
                    _uiState.value = UiState.Error("扫描失败：${e.message}", e)
                }
                .collect { result ->
                    val device = result.device
                    val name = device.name ?: "未知设备"
                    seen[device.address] = BleDevice(
                        name = name,
                        address = device.address,
                        rssi = result.rssi,
                        isConnectable = result.isConnectable
                    )
                    _devices.update { seen.values.toList() }
                    _uiState.value = if (seen.isEmpty()) UiState.Empty
                    else UiState.Content(seen.values.toList())
                }
            // Flow 正常结束（超时自动停）
            _isScanning.value = false
            if (seen.isEmpty()) _uiState.value = UiState.Empty
        }
    }

    /** 手动停止扫描 */
    fun stopScan() {
        scanJob?.cancel()
        scanner.stopAll()
        _isScanning.value = false
        _uiState.value = if (seen.isEmpty()) UiState.Empty else UiState.Content(seen.values.toList())
    }

    /**
     * 模拟模式下造两个假设备，让"列表 → 详情"的跳转也能演示
     * 这是降级方案的一部分：没有硬件时 App 依然可完整走通
     */
    fun loadMockDevices() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            delay(600)     // 假装扫描需要时间，展示 Loading 态
            val mock = listOf(
                BleDevice("SensorHub-01", "AA:BB:CC:DD:EE:01", -48),
                BleDevice("SensorHub-02", "AA:BB:CC:DD:EE:02", -67)
            )
            seen.clear()
            mock.forEach { seen[it.address] = it }
            _devices.value = mock
            _uiState.value = UiState.Content(mock)
        }
    }
}
