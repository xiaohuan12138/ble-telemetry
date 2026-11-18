package com.pixellab.app.worker

import android.content.Context
import android.graphics.Bitmap
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pixellab.app.PixelLabApp
import com.pixellab.app.data.db.PhotoEntity
import com.pixellab.app.image.ProcessEngine
import com.pixellab.app.image.Transformer
import com.pixellab.app.widget.RecentWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 批量处理 Worker（C1 强制点：批量处理 + WorkManager 后台任务）
 *
 * 为什么用 WorkManager 而不是直接开协程：
 *   1. 用户中途退出页面/杀进程，任务链仍会在进程活着时继续跑；
 *   2. 自带进度上报 setProgress，UI 观察即可，不用自己写回调总线；
 *   3. 系统约束（电量/网络）是白送的能力。
 *
 * 输入输出约定：
 *   inputData.paths     —— 待处理图片的绝对路径列表
 *   inputData.filter    —— 滤镜 key
 *   progress.done/total —— 实时进度
 *   outputData.ok       —— 成功张数
 */
class BatchWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as PixelLabApp
        val paths = inputData.getStringArray(KEY_PATHS) ?: emptyArray()
        val filter = inputData.getString(KEY_FILTER) ?: "SHARPEN"
        if (paths.isEmpty()) return Result.failure()

        var ok = 0
        for ((index, path) in paths.withIndex()) {
            try {
                // 每张图：解码 → 卷积 → 保存 → 写历史，全部 IO/计算都切到 IO 池
                val done = withContext(Dispatchers.IO) {
                    val bmp = ProcessEngine.loadForEdit(path, Transformer.EDIT_MAX_EDGE)
                        ?: return@withContext false
                    val (filtered, cost) = ProcessEngine.applyFilter(bmp, filter)
                    val resultPath = ProcessEngine.saveBitmap(applicationContext, filtered, "result")
                    val thumbPath = ProcessEngine.saveBitmap(
                        applicationContext, Transformer.makeThumbnail(filtered), "thumb", jpeg = true
                    )
                    app.db.photoDao().insert(
                        PhotoEntity(
                            sourcePath = path,
                            resultPath = resultPath,
                            thumbPath = thumbPath,
                            filterName = filter,
                            opsJson = ProcessEngine.buildOpsJson("filter" to filter, "batch" to "true"),
                            ocrText = null,
                            width = filtered.width,
                            height = filtered.height,
                            costMs = cost,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                    true
                }
                if (done) ok++
            } catch (e: Exception) {
                // 单张失败不中断整批：跳过继续处理下一张
                continue
            }

            // 实时进度给 UI
            setProgress(
                androidx.work.workDataOf(
                    KEY_DONE to (index + 1),
                    KEY_TOTAL to paths.size
                )
            )
        }

        // 批量完成后刷新小组件
        RecentWidgetProvider.notifyDataChanged(applicationContext)

        return Result.success(androidx.work.workDataOf(KEY_OK to ok))
    }

    companion object {
        const val KEY_PATHS = "paths"
        const val KEY_FILTER = "filter"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_OK = "ok"
    }
}
