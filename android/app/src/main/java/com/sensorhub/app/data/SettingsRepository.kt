package com.sensorhub.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 配置存储（DataStore）
 *
 * 为什么不用 SharedPreferences（答辩常问的经典问题）？
 *   ┌────────────────┬─────────────────────┬──────────────────────┐
 *   │                │ SharedPreferences   │ DataStore            │
 *   ├────────────────┼─────────────────────┼──────────────────────┤
 *   │ 线程模型       │ 同步 API，主线程会卡 │ 全异步，基于协程      │
 *   │ 类型安全       │ 全是 Any，易出错    │ 强类型的 Preferences.Key│
 *   │ 事务           │ apply() 不保证落盘  │ 原子事务，保证一致性  │
 *   │ 错误处理       │ 崩溃就崩了          │ 有 IOException 流转   │
 *   └────────────────┴─────────────────────┴──────────────────────┘
 *   最关键的一点：SharedPreferences 的 getXxx() 是同步读磁盘，
 *   在启动时频繁调用会掉帧。DataStore 全部走 Flow，天然异步。
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sensorhub_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val MOCK_ENABLED = booleanPreferencesKey("mock_enabled")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val NOTIFY_ENABLED = booleanPreferencesKey("notify_enabled")
        val DEFAULT_PERIOD_MS = intPreferencesKey("default_period_ms")
        val MANUAL_MAC = stringPreferencesKey("manual_mac")
        val FILTER_MODE = stringPreferencesKey("filter_mode")
    }

    // ---------------- 读取（Flow，自动感知变化）----------------

    /** 是否使用模拟数据源。默认 false（有硬件时走真实蓝牙） */
    val mockEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.MOCK_ENABLED] ?: false }

    /** 断开后是否自动重连 */
    val autoReconnect: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_RECONNECT] ?: true }

    /** 规则命中时是否发系统通知 */
    val notifyEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIFY_ENABLED] ?: true }

    /** 默认上报周期 */
    val defaultPeriodMs: Flow<Int> = context.dataStore.data.map { it[Keys.DEFAULT_PERIOD_MS] ?: 1000 }

    /** 手动直连的 MAC 地址（权限被拒时的降级手段） */
    val manualMac: Flow<String> = context.dataStore.data.map { it[Keys.MANUAL_MAC] ?: "" }

    /** 滤波方式 */
    val filterMode: Flow<String> = context.dataStore.data.map { it[Keys.FILTER_MODE] ?: "KALMAN" }

    // ---------------- 写入（suspend，内部是事务）----------------

    suspend fun setMockEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MOCK_ENABLED] = enabled }
    }

    suspend fun setAutoReconnect(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_RECONNECT] = enabled }
    }

    suspend fun setNotifyEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFY_ENABLED] = enabled }
    }

    suspend fun setDefaultPeriod(ms: Int) {
        context.dataStore.edit { it[Keys.DEFAULT_PERIOD_MS] = ms.coerceIn(200, 10000) }
    }

    suspend fun setManualMac(mac: String) {
        context.dataStore.edit { it[Keys.MANUAL_MAC] = mac.trim().uppercase() }
    }

    suspend fun setFilterMode(mode: String) {
        context.dataStore.edit { it[Keys.FILTER_MODE] = mode }
    }
}
