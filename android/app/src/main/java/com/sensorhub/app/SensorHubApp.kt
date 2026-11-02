package com.sensorhub.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.sensorhub.app.data.SensorRepository
import com.sensorhub.app.data.SettingsRepository
import com.sensorhub.app.data.db.AppDatabase
import com.sensorhub.app.util.NotificationHelper

/**
 * Application 类：整个 App 的入口，负责"只做一次"的初始化
 *
 * 为什么把依赖放在这里而不是用 Hilt？
 *
 *   诚实的理由：本项目只有 3 个全局单例（数据库、设置、通知），
 *   用 Hilt 要引入 @HiltAndroidApp / @AndroidEntryPoint / @Inject /
 *   @Module / @Provides 等一整套注解，代码量反而增加，
 *   而且"注解处理器到底生成了什么"这个问题在答辩时很难三言两语讲清。
 *
 *   手动管理依赖在这里是更合适的选择：
 *     - 依赖图一目了然，一张纸画得完
 *     - 没有编译期魔法，出问题好排查
 *     - 老师问"你的依赖是怎么注入的"，能一句话说清
 *
 *   如果项目有 20+ 个 ViewModel、多层嵌套依赖，那就该上 Hilt 了。
 *   这个取舍本身就是一个可以讲的工程判断。
 */
class SensorHubApp : Application() {

    /** 懒加载：真正用到时才建数据库，避免拖慢启动 */
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    val repository: SensorRepository by lazy { SensorRepository(database) }

    val settings: SettingsRepository by lazy { SettingsRepository(this) }

    val notificationHelper: NotificationHelper by lazy { NotificationHelper(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    /**
     * 创建通知渠道
     * Android 8（API 26）起，所有通知必须归属某个渠道，否则直接不显示。
     * 渠道一旦创建，除了名字和描述，其它属性（如重要性）用户改了就改不回来了，
     * 所以初始设置要慎重。
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)

        // 渠道 1：前台服务（必须是 IMPORTANCE_LOW，否则会一直响/震动）
        val serviceChannel = NotificationChannel(
            NotificationHelper.CHANNEL_SERVICE,
            getString(R.string.channel_service_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_service_desc)
            setShowBadge(false)
        }

        // 渠道 2：告警（必须 IMPORTANCE_HIGH，否则不会弹 heads-up）
        val alertChannel = NotificationChannel(
            NotificationHelper.CHANNEL_ALERT,
            getString(R.string.channel_alert_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_alert_desc)
            enableVibration(true)
        }

        manager.createNotificationChannels(listOf(serviceChannel, alertChannel))
    }

    companion object {
        lateinit var instance: SensorHubApp
            private set
    }
}
