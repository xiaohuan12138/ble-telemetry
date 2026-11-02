package com.sensorhub.app.data

import com.sensorhub.app.data.db.AppDatabase
import com.sensorhub.app.data.db.DeviceEntity
import com.sensorhub.app.data.db.RangeStats
import com.sensorhub.app.data.db.ReadingEntity
import com.sensorhub.app.data.db.RuleEntity
import com.sensorhub.app.domain.AutomationRule
import com.sensorhub.app.domain.RuleChannel
import com.sensorhub.app.domain.RuleOperator
import com.sensorhub.app.protocol.SensorData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 统一数据仓储
 *
 * Repository 模式的作用：UI 不需要知道数据来自 Room 还是网络还是内存，
 * 只知道"我要什么数据"。好处：
 *   ① 以后想把数据同步到服务器，只改这一层，UI 一行不用动
 *   ② 单元测试时可以给个假 Repository，不用起真数据库
 *   ③ 数据转换（数据库实体 ↔ 领域模型）集中在一处，不会散落各地
 */
class SensorRepository(private val db: AppDatabase) {

    // ================= 采样记录 =================

    /**
     * 保存一条采样
     * @param source "ble" 或 "mock"，用于区分真实数据
     */
    suspend fun saveReading(address: String, data: SensorData, source: String) {
        db.readingDao().insert(
            ReadingEntity(
                deviceAddress = address,
                timestamp = data.timestamp,
                temperature = data.temperature,
                humidity = data.hum,
                pressure = data.press,
                lux = data.lux,
                tempBmp = data.tempBmp,
                flags = data.flags,
                rssi = data.rssi,
                source = source
            )
        )
    }

    fun observeReadings(limit: Int = 200): Flow<List<ReadingEntity>> =
        db.readingDao().observeRecent(limit)

    suspend fun readingCount(): Int = db.readingDao().count()

    suspend fun statsSince(since: Long): RangeStats? = db.readingDao().statsSince(since)

    suspend fun clearReadings() = db.readingDao().clearAll()

    /**
     * 数据裁剪：只保留最近 5000 条
     * 1Hz 采样 = 5000 秒 ≈ 83 分钟的容量，足够演示，又不至于撑爆存储。
     * 这个方法由 WorkManager 定期任务调用（见 worker/DatabaseCleanupWorker）。
     */
    suspend fun trimReadings(keep: Int = 5000) = db.readingDao().keepLatest(keep)

    // ================= 规则 =================

    /** 数据库实体 → 领域模型 */
    fun observeRules(): Flow<List<AutomationRule>> =
        db.ruleDao().observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getEnabledRules(): List<AutomationRule> =
        db.ruleDao().getEnabled().map { it.toDomain() }

    /** 插入或更新一条规则，返回生成的 id（新规则 id=0 时由数据库分配） */
    suspend fun saveRule(rule: AutomationRule): Long =
        db.ruleDao().upsert(rule.toEntity())

    suspend fun setRuleEnabled(id: Long, enabled: Boolean) =
        db.ruleDao().setEnabled(id, enabled)

    suspend fun deleteRule(id: Long) = db.ruleDao().delete(id)

    // ================= 设备 =================

    fun observeDevices(): Flow<List<DeviceEntity>> = db.deviceDao().observeAll()

    /** 记录一次连接：存在就更新最后出现时间和信号，不存在就新建 */
    suspend fun touchDevice(address: String, name: String, rssi: Int) {
        val existing = db.deviceDao().get(address)
        db.deviceDao().upsert(
            DeviceEntity(
                address = address,
                name = name,
                lastSeenAt = System.currentTimeMillis(),
                lastRssi = rssi,
                connectCount = (existing?.connectCount ?: 0),
                isFavorite = existing?.isFavorite ?: false
            )
        )
    }

    suspend fun incrementConnectCount(address: String) =
        db.deviceDao().incrementConnectCount(address)
}

// ================= 实体 ↔ 领域模型 转换 =================

fun RuleEntity.toDomain(): AutomationRule = AutomationRule(
    id = id,
    name = name,
    channel = runCatching { RuleChannel.valueOf(channel) }.getOrDefault(RuleChannel.TEMPERATURE),
    operator = runCatching { RuleOperator.valueOf(operator) }.getOrDefault(RuleOperator.GREATER_THAN),
    threshold = threshold,
    enabled = enabled,
    notifyEnabled = notifyEnabled,
    ledEnabled = ledEnabled,
    ledBrightness = ledBrightness,
    buzzerEnabled = buzzerEnabled,
    buzzerDurationMs = buzzerDurationMs,
    triggerCount = triggerCount
)

/**
 * 领域模型 → 实体
 * 枚举存成字符串：数据库里直接能看懂（"TEMPERATURE" 而不是 0），
 * 排查问题时不用查代码对照表。代价是多几个字节存储，完全值得。
 */
fun AutomationRule.toEntity(): RuleEntity = RuleEntity(
    id = id,
    name = name,
    channel = channel.name,
    operator = operator.name,
    threshold = threshold,
    enabled = enabled,
    notifyEnabled = notifyEnabled,
    ledEnabled = ledEnabled,
    ledBrightness = ledBrightness,
    buzzerEnabled = buzzerEnabled,
    buzzerDurationMs = buzzerDurationMs,
    triggerCount = triggerCount
)
