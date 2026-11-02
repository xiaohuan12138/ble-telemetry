package com.sensorhub.app.ui.common

/**
 * 统一的 UI 状态包装
 *
 * 为什么要有这个？
 *   "加载中 / 空 / 错误 / 有内容" 这四种状态几乎每个页面都有。
 *   如果不统一，每个 ViewModel 都会自己发明一套（isLoading + error + data
 *   三个变量），结果就是状态组合爆炸：isLoading=true 且 error 非空，
 *   该显示哪个？说不清。
 *
 *   用密封类把它建模成"四选一"，编译器保证你不会漏处理某种情况，
 *   也从根源上消灭了非法状态组合。这是 Kotlin 相对 Java 的一个实际优势。
 */
sealed interface UiState<out T> {

    /** 正在加载 */
    data object Loading : UiState<Nothing>

    /** 加载完成但没数据 */
    data object Empty : UiState<Nothing>

    /** 出错了，携带可读消息和可选的原始异常 */
    data class Error(val message: String, val cause: Throwable? = null) : UiState<Nothing>

    /** 有数据 */
    data class Content<out T>(val data: T) : UiState<T>
}

/** 便捷构造：把普通列表转成 UiState */
fun <T> List<T>.toUiState(): UiState<List<T>> =
    if (isEmpty()) UiState.Empty else UiState.Content(this)
