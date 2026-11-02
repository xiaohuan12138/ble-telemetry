package com.sensorhub.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Room 数据库
 *
 * @Database 注解的三个要素：
 *   entities —— 有哪些表
 *   version  ——  schema 版本号，改了表结构必须 +1
 *   exportSchema —— true 会把 schema 导出成 JSON 文件，方便做迁移测试。
 *                   课程项目开不开都行，这里开true并在 .gitignore 里忽略
 */
@Database(
    entities = [ReadingEntity::class, RuleEntity::class, DeviceEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun readingDao(): ReadingDao
    abstract fun ruleDao(): RuleDao
    abstract fun deviceDao(): DeviceDao

    companion object {
        private const val DB_NAME = "sensorhub.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * 双重检查锁定（Double-Checked Locking）单例
         *
         * 为什么必须单例？
         *   多个 Database 实例 = 多个 SQLite 连接 = 锁竞争、数据不一致、
         *   内存翻倍。这是 Room 使用中最常见的错误之一。
         *
         * @Volatile + synchronized：保证多线程下 INSTANCE 的可见性与原子性
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun build(context: Context): AppDatabase {
            return Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                // 首次创建时插入几条默认规则，让"规则"页面一打开就有东西可看
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        CoroutineScope(Dispatchers.IO).launch {
                            seedDefaultRules(getInstance(context))
                        }
                    }
                })
                // 课程项目不做迁移，版本变化直接重建（用户数据丢了但不会崩）。
                // 正式项目绝对不能这么写，必须写 Migration。
                .fallbackToDestructiveMigration()
                .build()
        }

        /** 预置规则：演示"温度过高告警"和"环境太暗开灯"两个典型场景 */
        private suspend fun seedDefaultRules(db: AppDatabase) {
            db.ruleDao().upsert(
                RuleEntity(
                    name = "高温告警",
                    channel = "TEMPERATURE",
                    operator = "GREATER_THAN",
                    threshold = 30f,
                    enabled = true,
                    notifyEnabled = true,
                    ledEnabled = true,
                    ledBrightness = 200,
                    buzzerEnabled = false,
                    buzzerDurationMs = 300,
                    triggerCount = 3
                )
            )
            db.ruleDao().upsert(
                RuleEntity(
                    name = "光线不足自动补光",
                    channel = "LIGHT",
                    operator = "LESS_THAN",
                    threshold = 50f,
                    enabled = true,
                    notifyEnabled = false,
                    ledEnabled = true,
                    ledBrightness = 255,
                    buzzerEnabled = false,
                    buzzerDurationMs = 0,
                    triggerCount = 2
                )
            )
        }
    }
}
