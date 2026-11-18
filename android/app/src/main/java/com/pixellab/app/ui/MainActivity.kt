package com.pixellab.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import com.pixellab.app.R
import com.pixellab.app.databinding.ActivityMainBinding

/**
 * 唯一的 Activity。
 *
 * 职责只有两个：
 *   1. 承载 NavHostFragment（所有页面都是 Fragment）
 *   2. 把 BottomNavigationView 和 Navigation 绑定：
 *      setupWithNavController 之后 tab id == nav_graph 里的 destination id，
 *      点 tab 自动切页，系统返回键自动在 tab 之间回退，零手写逻辑。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHost = supportFragmentManager.findFragmentById(R.id.navHostFragment) as NavHostFragment
        NavigationUI.setupWithNavController(binding.bottomNav, navHost.navController)
    }
}
