package com.pixellab.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * PhotoDao：对 photos 表的全部 SQL 都集中在这一个接口里。
 * Room 会在编译期检查每个方法的 SQL 和实体字段是否对得上（写错编译直接报错），
 * 返回 Flow 的查询在数据变化时自动推送新列表——列表页不用手动刷新。
 */
@Dao
interface PhotoDao {

    /** 最近处理记录，新 → 旧。列表页和 P1 小组件共用 */
    @Query("SELECT * FROM photos ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PhotoEntity>>

    /** 按 id 取单条（详情页跳转参数就是它） */
    @Query("SELECT * FROM photos WHERE id = :id")
    suspend fun getById(id: Long): PhotoEntity?

    /** 最新一条（P1 小组件展示"最近处理"） */
    @Query("SELECT * FROM photos ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatest(): PhotoEntity?

    @Insert
    suspend fun insert(photo: PhotoEntity): Long

    /** 批量处理结束后一次写入多条（WorkManager 里用） */
    @Insert
    suspend fun insertAll(photos: List<PhotoEntity>)

    /** 删除记录（只删数据库行，文件由调用方决定是否一起删） */
    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 清空历史（设置页"清空数据"入口） */
    @Query("DELETE FROM photos")
    suspend fun deleteAll()

    /** 统计总数（设置页显示） */
    @Query("SELECT COUNT(*) FROM photos")
    suspend fun count(): Int
}
