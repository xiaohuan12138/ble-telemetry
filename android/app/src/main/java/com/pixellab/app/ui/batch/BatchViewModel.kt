package com.pixellab.app.ui.batch

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.pixellab.app.PixelLabApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 批量页状态：选中项的文件名 + 进度 */
data class BatchState(
    val picked: List<Uri> = emptyList(),     // 相册多选返回的 Uri
    val filterKey: String = "SHARPEN",
    val workId: String? = null,              // 当前 WorkManager 任务的 id
    val done: Int = 0,
    val total: Int = 0,
    val finished: Boolean = false,
    val okCount: Int = 0
)

class BatchViewModel(private val app: PixelLabApp) : ViewModel() {

    private val _state = MutableStateFlow(BatchState())
    val state: StateFlow<BatchState> = _state.asStateFlow()

    fun setFilter(key: String) {
        _state.value = _state.value.copy(filterKey = key)
    }

    fun setPicked(uris: List<Uri>) {
        _state.value = _state.value.copy(picked = uris, finished = false, okCount = 0, done = 0, total = 0)
    }

    /**
     * 提交批处理任务到 WorkManager。
     * Uri 在 Fragment 层先转成本地文件路径（授权过期问题），
     * inputData 只能放小数据，图片内容绝不塞进 WorkData。
     */
    fun start(paths: List<String>) {
        val request = androidx.work.OneTimeWorkRequestBuilder<com.pixellab.app.worker.BatchWorker>()
            .setInputData(
                androidx.work.workDataOf(
                    com.pixellab.app.worker.BatchWorker.KEY_PATHS to paths.toTypedArray(),
                    com.pixellab.app.worker.BatchWorker.KEY_FILTER to _state.value.filterKey
                )
            )
            .build()
        WorkManager.getInstance(app).enqueue(request)
        _state.value = _state.value.copy(
            workId = request.id.toString(),
            total = paths.size, done = 0, finished = false
        )
        observeWork()
    }

    /** 观察 Worker 的进度与完成态 */
    private fun observeWork() {
        val id = _state.value.workId ?: return
        val uuid = java.util.UUID.fromString(id)
        viewModelScope.launch {
            WorkManager.getInstance(app)
                .getWorkInfoByIdFlow(uuid)
                .collect { info ->
                    when (info?.state) {
                        WorkInfo.State.RUNNING -> {
                            val d = info.progress.getInt(com.pixellab.app.worker.BatchWorker.KEY_DONE, 0)
                            _state.value = _state.value.copy(done = d)
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            val ok = info.outputData.getInt(com.pixellab.app.worker.BatchWorker.KEY_OK, 0)
                            _state.value = _state.value.copy(finished = true, okCount = ok)
                        }
                        WorkInfo.State.FAILED -> {
                            _state.value = _state.value.copy(finished = true, okCount = 0)
                        }
                        else -> Unit
                    }
                }
        }
    }

    companion object {
        fun Factory(app: PixelLabApp) = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = BatchViewModel(app) as T
        }
    }
}
