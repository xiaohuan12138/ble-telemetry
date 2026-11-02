package com.sensorhub.app.ui.detail

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.sensorhub.app.SensorHubApp
import com.sensorhub.app.ble.BleConnection
import com.sensorhub.app.ble.BleConnectionManager
import com.sensorhub.app.ble.MockDataSource
import com.sensorhub.app.domain.ConnectionState
import com.sensorhub.app.domain.Filter
import com.sensorhub.app.domain.FilterMode
import com.sensorhub.app.domain.diagnoseSensorHealth
import com.sensorhub.app.protocol.FrameParser
import com.sensorhub.app.protocol.FrameSpec
import com.sensorhub.app.protocol.SensorData
import com.sensorhub.app.protocol.parseSensorPayload
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 设备详情 ViewModel
 *
 * 本页是这个项目技术密度最高的地方，一个 ViewModel 里同时存在：
 *   - BLE 连接状态机（来自 BleConnection）
 *   - 实时数据流（SharedFlow）
 *   - 可切换的滤波器（策略模式）
 *   - 反向控制命令下发
 *   - 模拟数据源（降级方案）
 *
 * @param savedStateHandle 由 Navigation 的 SafeArgs 自动填充，
 *                         里面直接就有 nav_graph 里声明的 address / name 参数
 */
class DeviceDetailViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    val address: String = savedStateHandle["address"] ?: ""
    val deviceName: String = savedStateHandle["name"] ?: "SensorHub"

    private val app get() = SensorHubApp.instance

    /** 当前连接（真实 BLE） */
    private var connection: BleConnection? = null

    /** 模拟数据源相关 */
    private val mockSource = MockDataSource(lossProbability = 0.02f)
    private val mockParser = FrameParser()
    private var mockJob: Job? = null

    /** 当前滤波器，可在 UI 上实时切换 */
    private var filter: Filter = FilterMode.KALMAN.create()
    private var rawTemp = 0f

    // ---------------- 对外状态 ----------------

    private val _data = MutableStateFlow<SensorData?>(null)
    val data: StateFlow<SensorData?> = _data.asStateFlow()

    private val _connectionState =
        MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _health = MutableStateFlow("尚未连接")
    val health: StateFlow<String> = _health.asStateFlow()

    private val _linkStats = MutableStateFlow(com.sensorhub.app.ble.LinkStats())
    val linkStats: StateFlow<com.sensorhub.app.ble.LinkStats> = _linkStats.asStateFlow()

    /** 当前上报周期（毫秒） */
    private val _periodMs = MutableStateFlow(1000)
    val periodMs: StateFlow<Int> = _periodMs.asStateFlow()

    /** 是否模拟模式 */
    private val _isMock = MutableStateFlow(false)
    val isMock: StateFlow<Boolean> = _isMock.asStateFlow()

    /**
     * 已连接的真实设备数（多设备管理的直观体现）
     * 每秒轮询一次：BLE 连接状态变化不频繁，轮询比维护一个全局 Flow 简单得多，
     * 这也是"合适的技术选型"——不是所有东西都要做成响应式
     */
    val connectedCount: StateFlow<Int> = kotlinx.coroutines.flow.flow {
        while (true) {
            emit(BleConnectionManager.readyCount())
            delay(1000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        viewModelScope.launch {
            app.settings.mockEnabled.collect { mock -> _isMock.value = mock }
        }
        viewModelScope.launch {
            app.settings.filterMode.collect { mode ->
                filter = runCatching { FilterMode.valueOf(mode) }
                    .getOrDefault(FilterMode.KALMAN).create()
            }
        }
        viewModelScope.launch {
            app.settings.defaultPeriodMs.collect { _periodMs.value = it }
        }
    }

    // ================= 连接 =================

    /** 连接（或重连）设备 */
    fun connect() {
        viewModelScope.launch {
            if (app.settings.mockEnabled.first()) {
                startMock()
                return@launch
            }
            startReal()
        }
    }

    private suspend fun startReal() {
        val manager = getApplication<Application>()
            .getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null) {
            _connectionState.value = ConnectionState.Failed(-1, "本设备不支持蓝牙")
            return
        }

        val device: BluetoothDevice = adapter.getRemoteDevice(address)
        val conn = BleConnectionManager.get(getApplication(), device)
        connection = conn
        // 从设置里读一次"是否自动重连"。用 first() 而非持续 collect，
        // 因为这是"连接时用一下"的一次性配置，持续监听没有意义
        conn.autoReconnect = app.settings.autoReconnect.first()
        BleConnectionManager.autoReconnect = conn.autoReconnect

        // 观察连接状态
        viewModelScope.launch {
            conn.state.collect { _connectionState.value = it }
        }
        // 观察数据
        viewModelScope.launch {
            conn.sensorData.collect { onData(it) }
        }
        // 观察链路统计
        viewModelScope.launch {
            conn.stats.collect { _linkStats.value = it }
        }

        conn.connect()
        app.notificationHelper.cancelServiceNotification()
    }

    private fun startMock() {
        if (mockJob?.isActive == true) return
        mockJob = viewModelScope.launch {
            _connectionState.value = ConnectionState.Ready
            while (isActive) {
                val frame = mockSource.nextFrame()
                // 与真实链路完全相同的解析路径
                val frames = mockParser.feed(frame)
                for (f in frames) {
                    if (f.type == FrameSpec.TYPE_SENSOR_DATA) {
                        parseSensorPayload(f.payload)?.let { onData(it) }
                    }
                }
                delay(_periodMs.value.toLong())
            }
        }
    }

    fun disconnect() {
        mockJob?.cancel()
        mockJob = null
        connection?.disconnect()
        _connectionState.value = ConnectionState.Idle
    }

    // ================= 数据处理 =================

    private fun onData(raw: SensorData) {
        rawTemp = raw.temperature
        // 应用当前滤波器：只滤主温度，其它通道保持原值
        val smoothedTemp = filter.filter(raw.temperature)
        val processed = raw.copy(tempAht = smoothedTemp)
        _data.value = processed
        _health.value = diagnoseSensorHealth(raw)

        // 存库（异步，不阻塞 UI）
        viewModelScope.launch {
            app.repository.saveReading(address, processed, if (_isMock.value) "mock" else "ble")
        }
    }

    // ================= 反向控制 =================

    fun setLed(brightness: Int) {
        connection?.setLed(brightness)
    }

    fun beep(ms: Int = 300) {
        connection?.beep(ms)
    }

    fun setPeriod(ms: Int) {
        _periodMs.value = ms
        connection?.setPeriod(ms)
        viewModelScope.launch { app.settings.setDefaultPeriod(ms) }
    }

    /** 切换滤波方式 */
    fun setFilterMode(mode: FilterMode) {
        filter = mode.create()
        viewModelScope.launch { app.settings.setFilterMode(mode.name) }
    }

    /** 当前卡尔曼增益（仅卡尔曼模式有意义），用于 UI 展示收敛过程 */
    val kalmanGain: Float
        get() = (filter as? com.sensorhub.app.domain.KalmanFilter)?.lastGain ?: 0f

    override fun onCleared() {
        mockJob?.cancel()
        super.onCleared()
    }
}
