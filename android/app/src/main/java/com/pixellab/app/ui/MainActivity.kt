package com.pixellab.app.ui

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import com.pixellab.app.R
import com.pixellab.app.databinding.ActivityMainBinding
import com.pixellab.app.ui.assist.AssistantSheet

/**
 * 唯一的 Activity。
 *
 * 职责：
 *   1. 承载 NavHostFragment（所有页面都是 Fragment）
 *   2. 把 BottomNavigationView 和 Navigation 绑定：tab id == nav_graph destination id，
 *      切页/回退全自动
 *   3. 托管全局 AI 助手浮窗球：可拖拽，点击弹出 AssistantSheet
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // ── 浮窗球拖拽状态 ──
    /** 按下时的触点坐标（raw 坐标，跨屏一致） */
    private var downRawX = 0f
    private var downRawY = 0f
    /** 按下时球已累计的 translation 偏移 */
    private var startTx = 0f
    private var startTy = 0f
    /** 本次手势累计位移，用于区分"拖动"与"点击"（阈值 8dp） */
    private var dragged = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHost = supportFragmentManager.findFragmentById(R.id.navHostFragment) as NavHostFragment
        NavigationUI.setupWithNavController(binding.bottomNav, navHost.navController)

        setupAssistBall()
    }

    /**
     * 浮窗球 = 全局覆盖层 View（不进任何 Fragment，切页始终可见）。
     * 拖动改 translationX/Y（在约束位置上叠加偏移，不触发重新布局），
     * 位移小于阈值视为点击 → 弹出助手弹层。
     */
    private fun setupAssistBall() {
        val slopPx = 8 * resources.displayMetrics.density
        binding.assistBall.setOnTouchListener { ball, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startTx = ball.translationX
                    startTy = ball.translationY
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (dragged || dx * dx + dy * dy > slopPx * slopPx) {
                        dragged = true
                        ball.translationX = startTx + dx
                        ball.translationY = startTy + dy
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) openAssistant()
                    true
                }
                else -> false
            }
        }
    }

    private fun openAssistant() {
        // 重复点击不叠两层
        if (supportFragmentManager.findFragmentByTag(AssistantSheet.TAG) != null) return
        supportFragmentManager.commit {
            add(AssistantSheet(), AssistantSheet.TAG)
        }
    }
}
