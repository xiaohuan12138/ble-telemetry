package com.pixellab.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room 数据库入口。
 *
 * 单例写法的原因：RoomDatabase 实例创建要初始化 SQLite 连接和校验 schema，
 * 开销不小，且多个实例会互相看不到对方的写入缓存——全 App 共享一个实例。
 *
 * fallbackToDestructiveMigration：课程项目 schema 还在演进，
 * 表结构改了直接清库重建（正式产品要用 Migration 保数据，这里为了简单选清库）。
 */
@Database(entities = [PhotoEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun photoDao(): PhotoDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** 双重检查锁单例：第一次调用才真正建库，之后所有调用拿同一个实例 */
        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pixellab.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
