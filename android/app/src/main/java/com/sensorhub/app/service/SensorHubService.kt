package com.sensorhub.app.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.sensorhub.app.R
import com.sensorhub.app.SensorHubApp
import com.sensorhub.app.ble.BleConnectionManager
import com.sensorhub.app.ble.MockDataSource
import com.sensorhub.app.domain.AutomationRule
import com.sensorhub.app.domain.RuleAction
import com.sensorhub.app.domain.RuleEngine
import com.sensorhub.app.domain.FilterMode
import com.sensorhub.app.domain.Filter
import com.sensorhub.app.protocol.FrameParser
import com.sensorhub.app.protocol.SensorData
import com.sensorhub.app.util.NotificationHelper
import com.sensorhub.app.widget.SensorWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台服务：让 BLE 连接在 App 退到后台后依然存活
 *
 * 为什么必须用前台服务？
 *   Android 8（API 26）起，后台应用会被限制：
 *     - 后台 Service 会在几分钟内被系统停止
 *     - 后台的 BLE 扫描会被静默丢弃结果
 *   唯一合规的解法是前台服务：在通知栏显示一个常驻图标，
 *   告诉用户"我正在干活"，系统就不会随便杀你。
 *   代价是必须显示一个通知——这是 Android 的设计取舍，不是缺陷。
 *
 * 这个服务还承担了"业务中枢"的角色：
 *   收数据 → 滤波 → 存库 → 跑规则引擎 → 发通知/控硬件 → 刷新小组件
 *   把这套流程放在 Service 而不是 Fragment，是为了让它在
 *   "用户切到别的 App" 时仍然运行（这才是"设备控制"该有的样子）。
 */
class SensorHubService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var mockJob: Job? = null

    private val app get() = SensorHubApp.instance
    private val ruleEngine = RuleEngine()
    private var filter: Filter = FilterMode.KALMAN.create()

    /** 最近一次读数，供小组件和 UI 读取 */
    private val _latest = MutableSharedFlow<SensorData>(replay = 1, extraBufferCapacity = 8)
    val latest: SharedFlow<SensorData> = _latest.asSharedFlow()

    private val _alerts = MutableSharedFlow<RuleAction>(replay = 0, extraBufferCapacity = 8)
    val alerts: SharedFlow<RuleAction> = _alerts.asSharedFlow()

    /** 没有硬件时用的模拟数据 */
    private val mockSource = MockDataSource(lossProbability = 0.02f)
    private val mockParser = FrameParser()

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): SensorHubService = this@SensorHubService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        observeSettings()
    }

    /**
     * 启动为前台服务
     *
     * Android 14（API 34）强制要求指定 foregroundServiceType，
     * 而且必须在 Manifest 里声明对应的权限，否则抛 SecurityException。
     * 类型选 connectedDevice：明确告诉系统"我在连一个外部设备"。
     */
    private fun startAsForeground() {
        val notification = app.notificationHelper.buildServiceNotification("设备", false)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NotificationHelper.NOTIFICATION_ID_SERVICE,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NotificationHelper.NOTIFICATION_ID_SERVICE, notification)
            }
        } catch (e: SecurityException) {
            // 权限缺失（例如 Manifest 漏声明 FOREGROUND_SERVICE_CONNECTED_DEVICE）
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        }
    }

    /** 跟随设置变化：切换真实/模拟数据源、切换滤波方式 */
    private fun observeSettings() {
        scope.launch {
            app.settings.mockEnabled.collect { useMock ->
                if (useMock) startMock() else stopMock()
            }
        }
        scope.launch {
            app.settings.filterMode.collect { modeName ->
                filter = runCatching { FilterMode.valueOf(modeName) }
                    .getOrDefault(FilterMode.KALMAN)
                    .create()
            }
        }
    }

    // ================= 真实设备数据源 =================

    /** 开始监听某个真实设备的 BLE 数据 */
    fun observeDevice(address: String) {
        scope.launch {
            val connection = BleConnectionManager.get(address) ?: return@launch
            connection.sensorData.collect { data ->
                onNewData(address, data, source = "ble")
            }
        }
    }

    // ================= 模拟数据源 =================

    private fun startMock() {
        if (mockJob?.isActive == true) return
        mockJob = scope.launch {
            while (isActive) {
                val frame = mockSource.nextFrame()
                // 关键：模拟数据也走一遍真实的协议解析流程，
                // 这样 Mock 模式下 CRC、SEQ、帧同步这些逻辑同样在被验证
                val frames = mockParser.feed(frame)
                for (f in frames) {
                    if (f.type == com.sensorhub.app.protocol.FrameSpec.TYPE_SENSOR_DATA) {
                        val data = com.sensorhub.app.protocol.parseSensorPayload(f.payload)
                        if (data != null) onNewData("MOCK", data, source = "mock")
                    }
                }
                delay(1000)
            }
        }
    }

    private fun stopMock() {
        mockJob?.cancel()
        mockJob = null
    }

    // ================= 统一数据处理管线 =================

    /**
     * 一条新数据的完整处理流程
     * 这就是整个 App 的"业务主干"，答辩时照着这个方法讲一遍即可
     */
    private suspend fun onNewData(address: String, raw: SensorData, source: String) {
        // ① 滤波：抑制传感器噪声
        val smoothed = raw.copy(
            tempAht = filter.filter(raw.temperature)
        )

        // ② 存库：Room 异步写入，同时裁剪老数据
        app.repository.saveReading(address, smoothed, source)

        // ③ 记住设备
        app.repository.touchDevice(address, "SensorHub", raw.rssi)

        // ④ 规则引擎求值
        val rules = app.repository.getEnabledRules()
        val actions = ruleEngine.evaluate(smoothed, rules)
        for (action in actions) {
            dispatchAction(address, action)
        }

        // ⑤ 广播给 UI（有告警就再发一条告警事件）
        _latest.tryEmit(smoothed)
        actions.firstOrNull()?.let { _alerts.tryEmit(it) }

        // ⑥ 刷新桌面小组件（伴生模块 P1）—— 无论是否告警都要刷新
        SensorWidgetProvider.updateWidgetData(this, smoothed)
    }

    /**
     * 执行规则命中的动作
     * 通知失败了要降级成 App 内提示——这就是"权限降级"在业务层的体现
     */
    private suspend fun dispatchAction(address: String, action: RuleAction) {
        val rule = action.rule

        if (rule.notifyEnabled) {
            // Flow.first()：取一次当前值。这里是"读一次配置"的场景，
            // 不需要持续监听（配置监听已在 observeSettings 里做了）
            val notifyAllowed = app.settings.notifyEnabled.first()
            if (notifyAllowed) {
                val sent = app.notificationHelper.sendAlert(
                    "告警：${rule.name}",
                    "${rule.channel.displayName} 当前 ${"%.1f".format(action.measuredValue)}" +
                            "${rule.channel.unit}，阈值 ${rule.threshold}${rule.channel.unit}"
                )
                if (!sent) {
                    // 降级：通知权限被拒，改为在 App 内发一条 Alert 事件，
                    // UI 收到后会显示 Snackbar 横幅
                    _alerts.tryEmit(action)
                }
            }
        }

        // 反向控制：直接驱动硬件
        val connection = BleConnectionManager.get(address)
        if (rule.ledEnabled) connection?.setLed(rule.ledBrightness)
        if (rule.buzzerEnabled) connection?.beep(rule.buzzerDurationMs)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY：服务被系统意外杀掉后尽量重启（BLE 场景很常见）
        return START_STICKY
    }

    override fun onDestroy() {
        stopMock()
        scope.cancel()   // SupervisorJob + cancel：一次性取消所有子协程
        super.onDestroy()
    }
}
