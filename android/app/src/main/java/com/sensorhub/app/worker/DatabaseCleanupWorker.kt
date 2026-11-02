package com.sensorhub.app.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sensorhub.app.SensorHubApp
import java.util.concurrent.TimeUnit

/**
 * 定期清理历史数据的后台任务（WorkManager）
 *
 * 为什么用 WorkManager 而不是自己起个 Timer / 常驻 Service？
 *   ┌──────────────┬────────────────────────────────────┐
 *   │ Timer/Thread │ 进程被杀就没了；不感知电量和网络状态 │
 *   │ Service      │ 常驻耗电，Android 8+ 会被系统限制   │
 *   │ WorkManager  │ 系统统一调度，进程死了会重新排队；  │
 *   │              │ 支持"充电时才执行""空闲时才执行"约束│
 *   └──────────────┴────────────────────────────────────┘
 *   WorkManager 底层会根据系统版本自动选择 JobScheduler / AlarmManager / GCM，
 *   我们只管声明"要做什么"，不用管怎么调度。
 *
 * 本任务的作用：传感器 1Hz 采样，一天就是 86400 条。
 * 定期把超过 5000 条的旧数据删掉，避免数据库无限膨胀。
 */
class DatabaseCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            SensorHubApp.instance.repository.trimReadings(keep = 5000)
            Result.success()
        } catch (e: Exception) {
            // 返回 retry 而不是 failure：清理失败不影响主功能，下次再试即可
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "sensorhub_db_cleanup"

        /**
         * 注册周期性任务
         * 最小周期是 15 分钟（WorkManager 的硬性下限），这里设成每天一次
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                // 只在设备空闲时跑，避免影响用户使用时的性能
                .setRequiresDeviceIdle(true)
                .build()

            val request = PeriodicWorkRequestBuilder<DatabaseCleanupWorker>(
                1, TimeUnit.DAYS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,   // 已存在就不重复注册
                request
            )
        }
    }
}
