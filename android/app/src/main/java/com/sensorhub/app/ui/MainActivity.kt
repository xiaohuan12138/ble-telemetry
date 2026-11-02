package com.sensorhub.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.snackbar.Snackbar
import com.sensorhub.app.R
import com.sensorhub.app.ble.BleScanner
import com.sensorhub.app.databinding.ActivityMainBinding
import com.sensorhub.app.service.SensorHubService

/**
 * 主 Activity（唯一的 Activity）
 *
 * 职责：
 *   1) 组装 Navigation + BottomNavigationView
 *   2) 启动时申请蓝牙权限（权限降级方案的第一次拦截在这里）
 *   3) 绑定前台服务，让 BLE 连接活在 Activity 生命周期之外
 *
 * 为什么 Activity 要尽量"薄"？
 *   Activity 会在旋转屏幕、切后台被回收时重建，任何放在这里的业务状态
 *   都可能丢失。真正的状态在 ViewModel（配置变化不丢）和 Service（进程级）里。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var scanner: BleScanner

    /** 服务绑定后的引用，供各 Fragment 通过 requireActivity() 取用 */
    var sensorService: SensorHubService? = null
        private set

    private var serviceBound = false

    /**
     * 权限申请 launcher
     * Activity Result API 取代了老的 onRequestPermissionsResult：
     *   回调和申请写在一起，不用记 requestCode，也不会串味
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (!allGranted) {
            // ==== 降级方案第 1 级：权限被拒 ====
            // 不弹"请去设置开启"然后卡死，而是告诉用户还有别的路可走
            showSnackbar(getString(R.string.permission_denied_hint), Snackbar.LENGTH_LONG)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? SensorHubService.LocalBinder ?: return
            sensorService = binder.getService()
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            sensorService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        scanner = BleScanner(this)

        setupNavigation()
        checkAndRequestPermissions()
        startAndBindService()
    }

    private fun setupNavigation() {
        // 从 FragmentContainerView 里拿到 NavController
        val navHost = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        val navController = navHost.navController

        // 一行代码把底部导航和导航图绑定：
        //   点击 Tab → 切换 Fragment，且自动维护回退栈
        binding.bottomNav.setupWithNavController(navController)
    }

    /**
     * 权限检查与申请
     *
     * Android 12 前后差异：
     *   API 31+ → BLUETOOTH_SCAN / BLUETOOTH_CONNECT（不再需要位置权限）
     *   API ≤30 → ACCESS_FINE_LOCATION
     */
    private fun checkAndRequestPermissions() {
        if (!scanner.isBluetoothAvailable()) {
            showSnackbar("本设备不支持蓝牙，App 将以模拟数据模式运行", Snackbar.LENGTH_LONG)
            return
        }

        if (!scanner.isBluetoothEnabled()) {
            // 蓝牙没开：老系统可以直接代码打开（需要 BLUETOOTH_ADMIN），
            // 新系统只能引导用户去设置页，这是 Google 收紧权限的结果
            showSnackbar("请先开启蓝牙", Snackbar.LENGTH_LONG)
        }

        val needed = scanner.requiredPermissions()
        if (!scanner.hasScanPermission()) {
            permissionLauncher.launch(needed)
        }

        // Android 13 的通知权限单独申请（和蓝牙权限无关，是另一个维度）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
    }

    /**
     * 启动并绑定前台服务
     *
     * startForegroundService 必须在 5 秒内调用 startForeground()，
     * 否则系统会抛 ANR 级别的异常。所以 Service.onCreate 里第一件事
     * 就是 startAsForeground()。
     */
    private fun startAndBindService() {
        val intent = Intent(this, SensorHubService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        // BIND_AUTO_CREATE：服务没启动就先启动
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun showSnackbar(text: String, duration: Int = Snackbar.LENGTH_SHORT) {
        Snackbar.make(binding.rootLayout, text, duration).show()
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }
}
