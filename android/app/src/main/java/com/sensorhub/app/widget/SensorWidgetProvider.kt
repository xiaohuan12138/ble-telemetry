package com.sensorhub.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.sensorhub.app.R
import com.sensorhub.app.protocol.SensorData
import com.sensorhub.app.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 桌面小组件（伴生模块 P1）
 *
 * 小组件运行在**桌面进程**（Launcher），不是本 App 进程。
 * 这意味着：
 *   - 你不能拿到自己的 View 对象，只能通过 RemoteViews "远程描述"界面
 *   - 所有数据必须序列化（Intent extra / 静态变量 / 数据库）
 *   - 跨进程通信靠 AppWidgetManager.updateAppWidget()
 *
 * 为什么刷新要用"主动调用"而不是等系统的 updatePeriodMillis？
 *   系统规定的最小自动刷新间隔是 30 分钟，对实时数据毫无意义。
 *   所以本项目由 Service 在收到 BLE 数据时主动调用 updateWidgetData()，
 *   实现"秒级"刷新。这是小组件做实时数据的标准做法。
 */
class SensorWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // 系统触发的更新：从缓存里取最后一次数据
        for (id in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, id, lastData)
        }
    }

    override fun onEnabled(context: Context) {
        // 第一个小组件被添加到桌面
    }

    override fun onDisabled(context: Context) {
        // 最后一个小组件被移除
        lastData = null
    }

    companion object {
        /**
         * 最后一次数据的进程内缓存
         * ⚠️ 局限说明（答辩要主动说）：这是进程内静态变量，
         *    App 进程被杀后就没了，小组件会显示"暂无数据"直到下次 App 启动。
         *    更健壮的做法是存进 Room，由小组件自己查询——
         *    这里为了演示简洁用了静态缓存，属于明确的技术取舍。
         */
        @Volatile
        private var lastData: SensorData? = null

        /**
         * 由 Service 调用：更新所有小组件
         */
        fun updateWidgetData(context: Context, data: SensorData) {
            lastData = data
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, SensorWidgetProvider::class.java)
            )
            for (id in ids) {
                updateAppWidget(context, manager, id, data)
            }
        }

        private fun updateAppWidget(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
            data: SensorData?
        ) {
            // RemoteViews：一个"可跨进程传输的界面描述"，不是真正的 View
            val views = RemoteViews(context.packageName, R.layout.widget_sensor)

            if (data == null) {
                views.setTextViewText(R.id.tvWidgetTemp, "--")
                views.setTextViewText(R.id.tvWidgetHum, "--")
                views.setTextViewText(R.id.tvWidgetLux, "--")
                views.setTextViewText(R.id.tvWidgetTime, "暂无数据")
            } else {
                views.setTextViewText(R.id.tvWidgetTemp, "%.1f".format(data.temperature))
                views.setTextViewText(R.id.tvWidgetHum, "%.0f".format(data.hum))
                views.setTextViewText(R.id.tvWidgetLux, "${data.lux}")

                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(data.timestamp))
                views.setTextViewText(R.id.tvWidgetTime, time)

                // 温度异常时把标题染红（RemoteViews 也支持部分属性设置）
                if (data.temperature > 30f) {
                    views.setTextColor(R.id.tvWidgetTemp, context.getColor(R.color.danger))
                } else {
                    views.setTextColor(R.id.tvWidgetTemp, context.getColor(R.color.chart_temp))
                }
            }

            // 点击小组件打开 App
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pending = PendingIntent.getActivity(context, 0, intent, flags)
            views.setOnClickPendingIntent(R.id.widgetRoot, pending)

            manager.updateAppWidget(appWidgetId, views)
        }
    }
}
