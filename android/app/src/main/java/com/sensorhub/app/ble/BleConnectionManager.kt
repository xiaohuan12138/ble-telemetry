package com.sensorhub.app.ble

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.ConcurrentHashMap

/**
 * 多设备连接管理器
 *
 * 为什么需要一个管理器，而不是让每个页面自己 new 一个 BleConnection？
 *
 *   ① 页面会被销毁重建（旋转屏幕、切换 Tab），如果连接对象跟着页面走，
 *      每次重建都要重新连接一次，用户会看到反复断线。
 *      连接对象应该活在 Application/Service 作用域，页面只是"观察者"。
 *
 *   ② 本项目要同时管理 2 块 ESP32。如果两块板子同时断开，
 *      它们的重连时刻会撞在一起（这就是前面说的惊群效应）。
 *      统一由管理器调度，才能保证各自的退避节奏独立。
 *
 *   ③ 资源回收有统一出口：App 退出时 disconnectAll()，
 *      不会出现"某个页面的连接忘了关"导致的 133 错误。
 *
 * 用 ConcurrentHashMap：BLE 回调发生在 Binder 线程，UI 在主线程，
 * 两端都可能读写这个 Map，必须线程安全。
 */
object BleConnectionManager {

    private val connections = ConcurrentHashMap<String, BleConnection>()

    /** 管理器自己持有的作用域：SupervisorJob 保证一个连接出错不影响其它连接 */
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    /** 是否开启自动重连（设置页开关） */
    var autoReconnect: Boolean = true

    /**
     * 获取（或创建）一个设备的连接
     * 重复调用返回同一个实例 —— 这是"连接不随页面重建"的关键
     */
    fun get(context: android.content.Context, device: BluetoothDevice): BleConnection {
        val key = device.address
        val existing = connections[key]
        if (existing != null) {
            existing.autoReconnect = autoReconnect
            return existing
        }
        val created = BleConnection(context.applicationContext, device, scope, autoReconnect)
        connections[key] = created
        return created
    }

    fun get(address: String): BleConnection? = connections[address]

    /** 当前所有连接 */
    fun all(): List<BleConnection> = connections.values.toList()

    /** 已就绪（真正能收数据）的连接数 */
    fun readyCount(): Int = connections.values.count {
        it.state.value is com.sensorhub.app.domain.ConnectionState.Ready
    }

    /** 断开并移除一个连接 */
    fun remove(address: String) {
        connections.remove(address)?.close()
    }

    /** 断开所有连接（App 退出 / 关闭蓝牙时调用） */
    fun disconnectAll() {
        connections.values.forEach { it.close() }
        connections.clear()
    }

    /** 释放协程作用域（Application 销毁时） */
    fun shutdown() {
        disconnectAll()
        scope.cancel()
    }
}
