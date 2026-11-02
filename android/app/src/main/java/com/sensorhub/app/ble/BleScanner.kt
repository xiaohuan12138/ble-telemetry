package com.sensorhub.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * BLE 扫描器
 *
 * 三个必须知道的事实：
 *
 * 1) 扫描需要权限，而且 Android 12（API 31）前后规则不同：
 *      API 31+：BLUETOOTH_SCAN
 *      API ≤30：ACCESS_FINE_LOCATION（这是历史遗留：BLE 扫描能用来定位，
 *               所以 Google 早期把它归到位置权限组）
 *    本项目在 Manifest 里给 BLUETOOTH_SCAN 加了 neverForLocation 标记，
 *    等于向系统声明"我扫蓝牙不是为了定位"，31+ 就不需要位置权限了。
 *
 * 2) 扫描不能无限进行。Android 7 起，如果 30 秒内连续开关扫描 5 次，
 *    系统会直接甩你一个 SCAN_FAILED_APPLICATION_REGISTRATION_FAILED(-1)，
 *    之后几分钟内再也扫不动。所以本项目每次扫描都有超时自动停止。
 *
 * 3) Android 8 起，没有前台服务的后台 App 扫描会被静默限制。
 *    这也是本项目要起前台服务的原因之一。
 */
class BleScanner(private val context: Context) {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    /** 蓝牙硬件是否存在（模拟器、部分平板会返回 null） */
    fun isBluetoothAvailable(): Boolean = adapter != null

    /** 蓝牙是否已开启 */
    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    /**
     * 当前是否具备扫描所需的权限
     * 注意：这里只判断权限，不判断蓝牙开关——两种情况 UI 提示不同，分开处理
     */
    fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    /** 运行时要申请的权限列表（交给 Activity 的 launcher） */
    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /**
     * 开始扫描
     *
     * 用 callbackFlow 把"基于回调的异步 API"改造成"可以被 collect 的数据流"。
     * 这是本项目协程的核心用法之一（答辩重点）：
     *   - callbackFlow 提供一个 ProducerScope，允许在非协程上下文里发射数据
     *   - awaitClose 里的代码在 Flow 被取消时执行，正好用来注销回调、停止扫描
     *   - 这样"开始/停止"的生命周期就和 Flow 的生命周期绑定了，不会泄漏
     *
     * @param timeoutMs 超时自动停止，防止触发系统的扫描限流
     * @param filterServiceUuid 只保留广播了该服务的设备（可选，留空=全部）
     */
    @SuppressLint("MissingPermission")   // 权限由调用方在启动前检查
    fun scan(
        timeoutMs: Long = 15_000L,
        filterServiceUuid: java.util.UUID? = GattSpec.SERVICE_SENSOR
    ): Flow<ScanResult> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            // 硬件不可用：直接关闭这个 Flow，下游会收到空列表
            close()
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // trySend 是"非挂起"的发射：扫描回调非常频繁（10+ 次/秒），
                // 如果用 send() 会在下游处理不过来时挂起回调线程，直接卡死蓝牙栈
                trySend(result)
            }

            override fun onBatchScanResults(results: List<ScanResult>) {
                results.forEach { trySend(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                // -1 = 扫得太频繁被系统限流，-2 = 权限不足，-6 = 蓝牙已关闭
                close(IllegalStateException("扫描失败，错误码 $errorCode"))
            }
        }

        val filters = if (filterServiceUuid != null) {
            listOf(
                ScanFilter.Builder()
                    .setServiceUuid(android.os.ParcelUuid(filterServiceUuid))
                    .build()
            )
        } else {
            emptyList()
        }

        val settings = ScanSettings.Builder()
            // LOW_LATENCY：最密集的扫描节奏，适合"用户正在盯着列表等结果"的场景
            // 代价是耗电。如果用户长时间挂着，应改用 LOW_POWER。
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)          // 0 = 有结果立刻回调，不做批量合并
            .build()

        scanner.startScan(filters, settings, callback)

        // 超时保护：到点自动停
        // 这里直接 launch 是因为 callbackFlow 的 ProducerScope 本身就是 CoroutineScope，
        // 这样超时协程的生命周期与 Flow 绑定：Flow 一取消，它也被取消，不会泄漏
        if (timeoutMs > 0) {
            launch {
                delay(timeoutMs)
                try {
                    scanner.stopScan(callback)
                } catch (e: Exception) {
                    // 蓝牙已关闭时 stopScan 会抛，忽略即可
                }
                close()   // 正常结束 Flow，下游 collect 走完
            }
        }

        // Flow 被取消时（比如用户离开页面、手动停止扫描）执行清理
        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    /** 停止一切扫描（页面销毁时兜底调用） */
    @SuppressLint("MissingPermission")
    fun stopAll() {
        adapter?.bluetoothLeScanner?.stopScan(object : ScanCallback() {})
    }
}
