package com.pixellab.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import com.pixellab.app.PixelLabApp
import com.pixellab.app.R
import com.pixellab.app.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * P1 桌面小组件：主屏展示"最近处理的那张图"，点按打开 App。
 *
 * AppWidgetProvider 是 BroadcastReceiver 的子类，onUpdate 跑在主线程——
 * 数据库查询和图片解码必须离开主线程。这里用 runBlocking + Dispatchers.IO
 * 包住一段"短查询"：桌面小组件场景下等几百毫秒可接受，且代码量最小。
 * （应用内页面绝不允许这么写，会卡 UI——不同场景不同取舍，答辩可展开讲。）
 */
class RecentWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = context.applicationContext as PixelLabApp
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_recent)

            // 查最新一条处理记录（阻塞式，理由见类注释）
            val latest = runBlocking {
                withContext(Dispatchers.IO) { app.db.photoDao().getLatest() }
            }
            if (latest != null) {
                val bmp = BitmapFactory.decodeFile(latest.thumbPath)
                if (bmp != null) {
                    views.setViewVisibility(R.id.widgetImage, android.view.View.VISIBLE)
                    views.setImageViewBitmap(R.id.widgetImage, bmp)
                } else {
                    views.setViewVisibility(R.id.widgetImage, android.view.View.GONE)
                }
                views.setTextViewText(
                    R.id.widgetText,
                    "最近：${latest.width}×${latest.height}"
                )
            } else {
                views.setViewVisibility(R.id.widgetImage, android.view.View.GONE)
                views.setTextViewText(R.id.widgetText, context.getString(R.string.widget_empty))
            }

            // PendingIntent：点组件任意位置打开 App 主页
            val click = android.app.PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetImage, click)

            manager.updateAppWidget(id, views)
        }
    }

    companion object {
        /**
         * 数据变化时从 App 内"主动戳"一下小组件刷新。
         * updatePeriodMillis 最快 30 分钟，靠它做不到"保存后立刻更新"，
         * 所以保存历史记录后显式发一次更新广播。
         */
        fun notifyDataChanged(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, RecentWidgetProvider::class.java)
            )
            if (ids.isNotEmpty()) {
                context.sendBroadcast(
                    Intent(context, RecentWidgetProvider::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    }
                )
            }
        }
    }
}
