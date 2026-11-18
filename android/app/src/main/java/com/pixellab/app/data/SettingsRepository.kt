package com.pixellab.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** DataStore 顶层委托：文件名 pixellab.settings，自动懒加载 */
private val Context.dataStore by preferencesDataStore(name = "pixellab.settings")

/**
 * 设置仓库（DataStore）
 *
 * 存 App 级轻量配置（默认滤镜、编辑长边），大块结构化数据走 Room，
 * 这就是"Room 管记录、DataStore 管偏好"的分层。
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        /** 编辑页默认选中的滤镜 */
        val DEFAULT_FILTER = stringPreferencesKey("default_filter")
        /** 安全解码的最长边（性能与清晰度的折中，可在设置页调） */
        val EDIT_MAX_EDGE = intPreferencesKey("edit_max_edge")
        /** 保存结果后是否自动弹出分享卡片（P4 入口开关） */
        val AUTO_SHARE = booleanPreferencesKey("auto_share")
    }

    private fun stringPreferencesKey(name: String) =
        androidx.datastore.preferences.core.stringPreferencesKey(name)

    /** 默认滤镜名，Flow 订阅：设置页改了，编辑页自动跟着变 */
    val defaultFilter: Flow<String> =
        context.dataStore.data.map { it[Keys.DEFAULT_FILTER] ?: "SHARPEN" }

    val editMaxEdge: Flow<Int> =
        context.dataStore.data.map { it[Keys.EDIT_MAX_EDGE] ?: 1280 }

    val autoShare: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.AUTO_SHARE] ?: false }

    suspend fun setDefaultFilter(name: String) {
        context.dataStore.edit { it[Keys.DEFAULT_FILTER] = name }
    }

    suspend fun setEditMaxEdge(edge: Int) {
        context.dataStore.edit { it[Keys.EDIT_MAX_EDGE] = edge.coerceIn(640, 2048) }
    }

    suspend fun setAutoShare(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_SHARE] = enabled }
    }
}
