package com.pixellab.app

import android.app.Application
import com.pixellab.app.data.SettingsRepository
import com.pixellab.app.data.db.AppDatabase

/**
 * Application 入口：持有全 App 级的单例依赖。
 *
 * 项目规模小，不引 Hilt/Dagger，用"App 里存单例 + ViewModel 拿 Application 转型"
 * 的手工依赖注入——依赖关系一目了然，答辩时也更好解释"这些东西在哪创建的"。
 */
class PixelLabApp : Application() {

    lateinit var db: AppDatabase
        private set
    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.get(this)
        settings = SettingsRepository(this)
    }
}
