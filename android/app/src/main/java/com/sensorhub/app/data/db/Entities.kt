package com.sensorhub.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room 实体定义（三张表）
 *
 * 为什么是这三张？
 *   ① readings —— 历史采样记录，支撑"历史"页面和统计
 *   ② rules    —— 自动化规则，支撑"规则"页面，必须持久化否则重启就丢
 *   ③ devices  —— 已知设备，记住上次连接过的板子，下次打开直接显示
 *
 * 每张表都对应课程"数据持久化"这一条最低要求，三张表比一张表更能说明
 * 你理解了一对多关系（一个设备对应多条读数）。
 */

/**
 * 采样记录表
 *
 * @param deviceAddress 外键：来自哪个设备（这里没用真正的 ForeignKey 约束，
 *                       因为设备被删时我们希望保留历史记录，所以只是逻辑关联）
 * @param source        数据来源："ble" 真实蓝牙 / "mock" 模拟数据，便于区分演示数据
 */
@Entity(
    tableName = "readings",
    indices = [Index(value = ["deviceAddress"]), Index(value = ["timestamp"])]
)
data class ReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceAddress: String,
    val timestamp: Long,
    val temperature: Float,
    val humidity: Float,
    val pressure: Float,
    val lux: Int,
    val tempBmp: Float,
    val flags: Int,
    val rssi: Int,
    val source: String
)

/** 自动化规则表 */
@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 存枚举的 name 字符串，避免 TypeConverter 的复杂度，也便于直接看数据库 */
    val channel: String,
    val operator: String,
    val threshold: Float,
    val enabled: Boolean,
    val notifyEnabled: Boolean,
    val ledEnabled: Boolean,
    val ledBrightness: Int,
    val buzzerEnabled: Boolean,
    val buzzerDurationMs: Int,
    val triggerCount: Int
)

/** 已知设备表 */
@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val address: String,
    val name: String,
    val lastSeenAt: Long,
    val lastRssi: Int,
    val connectCount: Int,
    val isFavorite: Boolean
)
