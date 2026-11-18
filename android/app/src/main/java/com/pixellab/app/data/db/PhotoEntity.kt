package com.pixellab.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 处理历史记录（Room 表）
 *
 * 每做一次"打开图片 → 处理 → 保存"，就落一条记录。
 * 列表页（图库）读它做"最近处理"，详情页按 id 进来，P1 小组件读最近一条，
 * P4 分享卡片读"处理参数"拼卡片文案。
 *
 * 设计说明：
 *  - 不存 Bitmap 本身（数据库存大对象会让 SQLite 膨胀、查询变慢），
 *    只存文件路径；原图和结果图都放在应用私有目录 files/ 下。
 *  - 用 JSON 字符串存"操作序列"，将来支持"多步处理"时不用改表结构。
 */
@Entity(tableName = "photos")
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 原图（解码采样后）在本地的绝对路径 */
    val sourcePath: String,
    /** 最近一次处理结果的绝对路径（图库列表直接显示它） */
    val resultPath: String,
    /** 缩略图绝对路径（列表/小组件加载它，避免列表页加载大图卡顿） */
    val thumbPath: String,
    /** 应用的滤镜名（FilterPreset.name），空串表示只做几何变换 */
    val filterName: String,
    /** 操作序列 JSON，例如 [{"op":"filter","v":"SHARPEN"},{"op":"rotate","v":90}] */
    val opsJson: String,
    /** 文字识别结果（ML Kit），未识别为 null */
    val ocrText: String?,
    /** 图片宽度（采样后），列表页显示尺寸信息用 */
    val width: Int,
    /** 图片高度（采样后） */
    val height: Int,
    /** 处理耗时（毫秒），用于"性能优化"的量化展示 */
    val costMs: Long,
    /** 记录时间（epoch 毫秒） */
    val createdAt: Long
)
