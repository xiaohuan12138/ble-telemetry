package com.sensorhub.app.util

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sensorhub.app.R
import com.sensorhub.app.ui.MainActivity

/**
 * 通知封装
 *
 * 两个要点：
 *  1) Android 13（API 33）起，POST_NOTIFICATIONS 变成了危险权限，
 *     没授权就发通知会被静默丢弃（不报错，就是不显示）。
 *     所以每次发通知前都要检查一下，本项目在检查失败时会降级成 App 内横幅。
 *  2) PendingIntent 的 FLAG_IMMUTABLE 从 API 31 起是强制的，
 *     不加会直接抛异常崩溃。
 */
class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_SERVICE = "sensorhub_service"
        const val CHANNEL_ALERT = "sensorhub_alert"
        const val NOTIFICATION_ID_SERVICE = 1001
        private const val REQUEST_CODE_OPEN = 2001
        var alertNotificationId = 3000
            private set
    }

    /** 是否具备发送通知的权限（Android 13+ 必须检查） */
    fun canPostNotifications(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // 8.0 以下无需权限；8.0~12 由"通知总开关"控制，这里读取该开关
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    /** 打开主界面的 PendingIntent（点击通知时跳转） */
    private fun contentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(context, REQUEST_CODE_OPEN, intent, flags)
    }

    /** 前台服务的常驻通知（必须要有，否则服务会被系统杀掉） */
    fun buildServiceNotification(deviceName: String, connected: Boolean): Notification {
        val text = if (connected) "已连接，正在接收数据" else "等待连接…"
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setContentTitle("SensorHub · $deviceName")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentIntent(contentIntent())
            .setOngoing(true)          // 常驻，用户不能手动划掉
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * 发一条告警通知
     * @return 是否真的发出去了（false = 没权限，调用方应降级为 App 内提示）
     */
    fun sendAlert(title: String, content: String): Boolean {
        if (!canPostNotifications()) return false

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_warning)
            .setContentIntent(contentIntent())
            .setAutoCancel(true)       // 点击后自动消失
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()

        NotificationManagerCompat.from(context).notify(alertNotificationId++, notification)
        return true
    }

    fun cancelServiceNotification() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_SERVICE)
    }
}
