package com.sensorhub.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.sensorhub.app.domain.ConnectionState
import com.sensorhub.app.domain.ReconnectPolicy
import com.sensorhub.app.domain.describeBleError
import com.sensorhub.app.protocol.Frame
import com.sensorhub.app.protocol.FrameParser
import com.sensorhub.app.protocol.FrameSpec
import com.sensorhub.app.protocol.SequenceTracker
import com.sensorhub.app.protocol.buildCommandFrame
import com.sensorhub.app.protocol.parseSensorPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 与单个 ESP32 节点的 BLE 连接
 *
 * 这是整个项目最"硬"的一个类，把 Android 那套回调地狱式的 BLE API
 * 包装成了两个 Flow：
 *   - state      : 连接状态（Idle/Connecting/Discovering/Ready/Retrying/Failed）
 *   - sensorData : 解析好的传感器读数流
 *
 * 为什么用 Flow 包装回调（答辩必讲）：
 *   原生 BluetoothGattCallback 是"在哪儿都能被调"的回调，
 *   直接在这些回调里更新 UI 会碰到三个问题：
 *     ① 回调不在主线程 → 更新 UI 会崩
 *     ② 页面销毁了回调还在 → 内存泄漏
 *     ③ 多个页面都想听 → 没法共享
 *   Flow 一次性解决：SharedFlow 天然支持多订阅者、协程上下文可控、
 *   配合 repeatOnLifecycle 能在页面不可见时自动停止收集。
 *
 * 完整状态流转：
 *   Idle ──connect()──▶ Connecting ──onConnectionStateChange(CONNECTED)──▶ Discovering
 *                                                                              │
 *                                        onServicesDiscovered + 订阅 CCCD ◀────┘
 *                                                                              │
 *                                                                              ▼
 *                                                                            Ready
 *                                                                              │
 *                                          断线 ───────────────────────────────┘
 *                                            │
 *                              autoReconnect=true ?
 *                                    │              │
 *                                   yes             no
 *                                    ▼              ▼
 *                              Retrying ──delay──▶ Connecting     Failed/Idle
 *                                    │
 *                            超过 maxAttempts ──▶ Failed
 */
class BleConnection(
    private val context: Context,
    val device: BluetoothDevice,
    private val scope: CoroutineScope,
    /** 断开后是否自动重连（用户在设置页可关） */
    var autoReconnect: Boolean = true
) {
    private var gatt: BluetoothGatt? = null
    private var dataCharacteristic: BluetoothGattCharacteristic? = null
    private var ctrlCharacteristic: BluetoothGattCharacteristic? = null
    private var periodCharacteristic: BluetoothGattCharacteristic? = null

    private var reconnectJob: Job? = null
    private var commandSeq = 0

    /** 用户主动断开：为 true 时不触发自动重连 */
    private var userRequestedDisconnect = false

    private val parser = FrameParser()
    private val tracker = SequenceTracker()

    // ---------------- 对外暴露的数据流 ----------------

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _sensorData = MutableSharedFlow<com.sensorhub.app.protocol.SensorData>(
        replay = 0, extraBufferCapacity = 32
    )
    val sensorData: SharedFlow<com.sensorhub.app.protocol.SensorData> = _sensorData.asSharedFlow()

    /** 非传感器帧（心跳 / ACK / 告警），供上层做链路监控 */
    private val _otherFrames = MutableSharedFlow<Frame>(replay = 0, extraBufferCapacity = 16)
    val otherFrames: SharedFlow<Frame> = _otherFrames.asSharedFlow()

    private val _stats = MutableStateFlow(LinkStats())
    val stats: StateFlow<LinkStats> = _stats.asStateFlow()

    private val reconnectPolicy = ReconnectPolicy()

    // ======================================================================
    // 连接与断开
    // ======================================================================

    /** 发起连接。重复调用会先关闭旧连接。 */
    @SuppressLint("MissingPermission")
    fun connect() {
        reconnectJob?.cancel()
        userRequestedDisconnect = false
        parser.reset()
        tracker.reset()
        _state.value = ConnectionState.Connecting

        gatt?.close()
        // transport = TRANSPORT_LE：强制走 BLE 协议栈。
        // 不指定时系统可能尝试经典蓝牙，导致连接慢或直接失败（错误 133 的常见来源之一）
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    /** 主动断开，不触发自动重连 */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        userRequestedDisconnect = true
        reconnectJob?.cancel()
        val g = gatt ?: run {
            _state.value = ConnectionState.Idle
            return
        }
        g.disconnect()
        // 注意：disconnect() 只是发起断开，真正的回调稍后才到。
        // 这里不立刻 close()，否则收不到 onConnectionStateChange，
        // 资源就清不干净（这就是 133 错误的经典成因）。
        _state.value = ConnectionState.Idle
    }

    /** 彻底释放资源（页面销毁 / App 退出时调用） */
    @SuppressLint("MissingPermission")
    fun close() {
        userRequestedDisconnect = true
        reconnectJob?.cancel()
        gatt?.close()
        gatt = null
        _state.value = ConnectionState.Idle
    }

    // ======================================================================
    // 下发命令
    // ======================================================================

    /**
     * 向设备发送一条命令
     *
     * @return true 表示已成功提交给协议栈（不代表设备已执行，
     *         设备执行结果会通过 ACK 帧回传）
     */
    @SuppressLint("MissingPermission")
    fun sendCommand(type: Int, payload: ByteArray): Boolean {
        val g = gatt ?: return false
        val char = ctrlCharacteristic ?: return false

        val frame = buildCommandFrame(type, payload, commandSeq++)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13 起推荐这个重载：把数据作为参数传进去，
            // 避免了"多线程同时改 characteristic.value"的竞态
            g.writeCharacteristic(
                char,
                frame,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = frame
            @Suppress("DEPRECATION")
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(char)
        }
    }

    /** 设置 LED 亮度 0~255 */
    fun setLed(brightness: Int): Boolean {
        val v = brightness.coerceIn(0, 255)
        return sendCommand(FrameSpec.CMD_SET_LED, byteArrayOf(v.toByte()))
    }

    /** 蜂鸣器鸣叫 ms 毫秒 */
    fun beep(durationMs: Int): Boolean {
        val ms = durationMs.coerceIn(0, 60000)
        return sendCommand(
            FrameSpec.CMD_SET_BUZZER,
            byteArrayOf(1, ((ms shr 8) and 0xFF).toByte(), (ms and 0xFF).toByte())
        )
    }

    /** 设置上报周期（毫秒） */
    fun setPeriod(periodMs: Int): Boolean {
        val ms = periodMs.coerceIn(200, 10000)
        return sendCommand(
            FrameSpec.CMD_SET_PERIOD,
            byteArrayOf(((ms shr 8) and 0xFF).toByte(), (ms and 0xFF).toByte())
        )
    }

    /** 请求立即上报一次 */
    fun requestReport(): Boolean = sendCommand(FrameSpec.CMD_FORCE_REPORT, ByteArray(0))

    // ======================================================================
    // GATT 回调
    // ======================================================================

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        _state.value = ConnectionState.Discovering
                        // 请求更大的 MTU：默认 23，改成 128 后单帧能传 125 字节，
                        // 后续若要上传批量历史数据就不用分包了
                        g.requestMtu(128)
                        // 必须等 MTU 协商完（onMtuChanged）再 discoverServices，
                        // 否则部分手机会跳过 MTU 回调导致流程卡住
                    } else {
                        handleFailure(status)
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    dataCharacteristic = null
                    ctrlCharacteristic = null
                    periodCharacteristic = null

                    if (userRequestedDisconnect) {
                        _state.value = ConnectionState.Idle
                        g.close()
                        return
                    }

                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        scheduleReconnect()
                    } else {
                        handleFailure(status)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            // 无论 MTU 协商成功与否都继续走服务发现，不能卡在这里
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleFailure(status)
                return
            }

            // 拿到我们需要的三个特征
            dataCharacteristic = g.getService(GattSpec.SERVICE_SENSOR)
                ?.getCharacteristic(GattSpec.CHAR_DATA)
            periodCharacteristic = g.getService(GattSpec.SERVICE_SENSOR)
                ?.getCharacteristic(GattSpec.CHAR_PERIOD)
            ctrlCharacteristic = g.getService(GattSpec.SERVICE_CTRL)
                ?.getCharacteristic(GattSpec.CHAR_CTRL)

            val dataChar = dataCharacteristic
            if (dataChar == null) {
                // 连上了却找不到特征：通常是固件 UUID 和 App 不一致
                handleFailure(-1001)
                return
            }

            // —— 订阅 Notify 的两步走 ——
            // 第 1 步：告诉手机本地协议栈"我要收这个特征的通知"
            g.setCharacteristicNotification(dataChar, true)

            // 第 2 步：写 CCCD，告诉对端"开始给我推"
            val cccd = dataChar.getDescriptor(GattSpec.DESCRIPTOR_CCCD)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            } else {
                // 固件没给这个特征加 CCCD 描述符，那就只做第 1 步（部分设备也能通）
                _state.value = ConnectionState.Ready
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS &&
                descriptor.uuid == GattSpec.DESCRIPTOR_CCCD
            ) {
                // 订阅成功，链路真正可用了
                _state.value = ConnectionState.Ready
                reconnectPolicy.reset()
            }
        }

        // ---- 数据到达 ----
        // Android 13 起这个回调换了签名：直接给 ByteArray，不再需要 getValue()。
        // 两个都实现是为了兼容老系统：老系统调用旧签名，新系统调用新签名。
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == GattSpec.CHAR_DATA) {
                handleIncoming(value)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val v = characteristic.value ?: return
            if (characteristic.uuid == GattSpec.CHAR_DATA) {
                handleIncoming(v)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            // 读 PERIOD 特征的回调，目前仅用于日志
        }
    }

    // ======================================================================
    // 数据处理
    // ======================================================================

    /** 收到一段字节流：喂给解析器，逐个处理解析出的帧 */
    private fun handleIncoming(bytes: ByteArray) {
        val frames = parser.feed(bytes)
        for (frame in frames) {
            tracker.onFrame(frame.seq)

            when (frame.type) {
                FrameSpec.TYPE_SENSOR_DATA -> {
                    val data = parseSensorPayload(frame.payload, currentRssi())
                    if (data != null) {
                        // tryEmit：SharedFlow 无订阅者时返回 false，不会抛异常也不会挂起。
                        // 这里绝不能用 emit()，因为回调线程挂起会阻塞蓝牙协议栈。
                        _sensorData.tryEmit(data)
                    }
                }
                FrameSpec.TYPE_HEARTBEAT -> {
                    _otherFrames.tryEmit(frame)
                }
                FrameSpec.TYPE_ALERT, FrameSpec.TYPE_ACK -> {
                    _otherFrames.tryEmit(frame)
                }
            }
        }
        updateStats()
    }

    private fun currentRssi(): Int = _stats.value.rssi

    private fun updateStats() {
        _stats.value = LinkStats(
            framesOk = parser.framesOk,
            badCrc = parser.framesBadCrc,
            dropped = parser.framesDropped,
            received = tracker.totalReceived,
            lost = tracker.totalLost,
            rssi = _stats.value.rssi
        )
    }

    /** 更新信号强度（由外部周期性读取后传入） */
    @SuppressLint("MissingPermission")
    fun refreshRssi() {
        // readRemoteRssi 是异步的，结果在 onReadRemoteRssi 回调里。
        // 为简化，这里用一个近似：不主动读，而是等下次扫描时更新。
    }

    // ======================================================================
    // 失败与重连
    // ======================================================================

    private fun handleFailure(status: Int) {
        if (!autoReconnect || userRequestedDisconnect) {
            _state.value = ConnectionState.Failed(status, describeBleError(status))
            return
        }
        scheduleReconnect(status)
    }

    /**
     * 安排一次重连
     * 指数退避：第 n 次等 min(30s, 1s × 2^n) + 0~30% 随机抖动
     */
    private fun scheduleReconnect(lastStatus: Int = 0) {
        if (!autoReconnect) return
        if (reconnectPolicy.exhausted) {
            _state.value = ConnectionState.Failed(
                lastStatus, "已重试 ${reconnectPolicy.currentAttempt} 次仍失败，停止重连"
            )
            return
        }

        val delayMs = reconnectPolicy.nextDelay()
        if (delayMs < 0) return

        _state.value = ConnectionState.Retrying(reconnectPolicy.currentAttempt, delayMs)

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (_state.value !is ConnectionState.Ready) {
                connect()      // 重新走一遍完整连接流程
            }
        }
    }
}

/** 链路统计：直接显示在详情页"链路质量"卡片里 */
data class LinkStats(
    val framesOk: Int = 0,
    val badCrc: Int = 0,
    val dropped: Int = 0,
    val received: Int = 0,
    val lost: Int = 0,
    val rssi: Int = 0
) {
    /** 丢包率百分比，保留 1 位小数 */
    val lossRatePercent: Float
        get() {
            val total = received + lost
            return if (total == 0) 0f else (lost * 100f / total)
        }

    /** CRC 错误率：反映空口质量，比丢包率更能说明干扰情况 */
    val crcErrorRatePercent: Float
        get() {
            val total = framesOk + badCrc
            return if (total == 0) 0f else (badCrc * 100f / total)
        }
}
