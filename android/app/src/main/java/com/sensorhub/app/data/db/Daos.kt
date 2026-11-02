package com.sensorhub.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * DAO（Data Access Object）
 *
 * 所有方法都标了 suspend 或返回 Flow —— 这是 Room 的现代用法：
 *   - suspend：主线程调用会直接崩（Room 有严苛的线程检查），
 *              强制你在协程里执行，天然避免 ANR
 *   - Flow   ：数据库内容变化时自动重新发射，UI 完全不用手动刷新。
 *              底层是 SQLite 的 trigger + Room 的 InvalidationTracker 实现的
 */

@Dao
interface ReadingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reading: ReadingEntity): Long

    /** 最近 N 条（倒序），返回 Flow：新增一条时列表自动刷新 */
    @Query("SELECT * FROM readings ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<ReadingEntity>>

    @Query("SELECT * FROM readings WHERE deviceAddress = :address ORDER BY timestamp DESC LIMIT :limit")
    fun observeByDevice(address: String, limit: Int = 200): Flow<List<ReadingEntity>>

    @Query("SELECT COUNT(*) FROM readings")
    suspend fun count(): Int

    /**
     * 统计一段时间内的极值
     * SQLite 的聚合函数直接在数据库里算，比把几千条读进内存再遍历快得多，
     * 也省内存 —— 这是移动端数据处理的正确做法
     */
    @Query(
        """SELECT MIN(temperature) as minT, MAX(temperature) as maxT,
                  MIN(humidity) as minH, MAX(humidity) as maxH
           FROM readings WHERE timestamp >= :since"""
    )
    suspend fun statsSince(since: Long): RangeStats?

    /**
     * 保留最近 N 条，删掉更早的
     * 为什么要做数据裁剪？采样 1Hz 跑一天就是 86400 条，
     * 不裁剪数据库会无限膨胀，最终拖慢查询、占满用户存储。
     */
    @Query(
        """DELETE FROM readings WHERE id NOT IN (
               SELECT id FROM readings ORDER BY timestamp DESC LIMIT :keep)"""
    )
    suspend fun keepLatest(keep: Int)

    @Query("DELETE FROM readings")
    suspend fun clearAll()
}

/** 聚合查询的返回结构。Room 要求字段名与 SQL 别名一致 */
data class RangeStats(
    val minT: Float?,
    val maxT: Float?,
    val minH: Float?,
    val maxH: Float?
)

@Dao
interface RuleDao {

    @Upsert
    suspend fun upsert(rule: RuleEntity): Long

    @Query("SELECT * FROM rules ORDER BY id DESC")
    fun observeAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules WHERE enabled = 1")
    suspend fun getEnabled(): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE id = :id")
    suspend fun getById(id: Long): RuleEntity?

    @Query("UPDATE rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM rules")
    suspend fun clearAll()
}

@Dao
interface DeviceDao {

    /** upsert = 存在就更新，不存在就插入（Room 2.5+ 提供，省得手写冲突处理） */
    @Upsert
    suspend fun upsert(device: DeviceEntity)

    @Query("SELECT * FROM devices ORDER BY lastSeenAt DESC")
    fun observeAll(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE address = :address")
    suspend fun get(address: String): DeviceEntity?

    @Query("UPDATE devices SET connectCount = connectCount + 1 WHERE address = :address")
    suspend fun incrementConnectCount(address: String)

    @Query("DELETE FROM devices WHERE address = :address")
    suspend fun delete(address: String)
}
