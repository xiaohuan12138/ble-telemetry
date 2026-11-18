package com.pixellab.app.ui

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import com.pixellab.app.R
import com.pixellab.app.databinding.ActivityMainBinding
import com.pixellab.app.ui.assist.AssistantBrain

/**
 * 唯一的 Activity。
 *
 * 职责：
 *   1. 承载 NavHostFragment（所有页面都是 Fragment）
 *   2. 把 BottomNavigationView 和 Navigation 绑定：tab id == nav_graph destination id，
 *      切页/回退全自动
 *   3. 托管全局 AI 助手：浮窗球（可拖拽）+ 悬浮聊天窗（可拖拽、边操作边对话）
 *
 * 助手聊天窗是覆盖层 View 而不是 Dialog/BottomSheet：
 *   - Dialog 会加遮罩、暂停下层交互；覆盖层只占 320×420dp，其余页面照常点按，
 *     真正做到"边调滤镜边提问"；
 *   - 位置靠 translationX/Y 偏移（不触发重新布局），两个手势互不干扰：
 *     浮窗球自己可拖，聊天窗靠标题栏拖。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // ── 浮窗球拖拽状态 ──
    private var ballDownX = 0f
    private var ballDownY = 0f
    private var ballStartTx = 0f
    private var ballStartTy = 0f
    private var ballDragged = false

    // ── 聊天窗拖拽状态（手柄 = 标题栏） ──
    private var panelDownX = 0f
    private var panelDownY = 0f
    private var panelStartTx = 0f
    private var panelStartTy = 0f

    /** 会话历史：面板收起再展开不清空 */
    private val chatLog = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHost = supportFragmentManager.findFragmentById(R.id.navHostFragment) as NavHostFragment
        NavigationUI.setupWithNavController(binding.bottomNav, navHost.navController)

        setupAssistBall()
        setupAssistPanel()
    }

    /**
     * 浮窗球：拖动改 translationX/Y，位移小于阈值视为点击 → 展开聊天窗。
     */
    private fun setupAssistBall() {
        val slopPx = 8 * resources.displayMetrics.density
        binding.assistBall.setOnTouchListener { ball, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ballDownX = event.rawX
                    ballDownY = event.rawY
                    ballStartTx = ball.translationX
                    ballStartTy = ball.translationY
                    ballDragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - ballDownX
                    val dy = event.rawY - ballDownY
                    if (ballDragged || dx * dx + dy * dy > slopPx * slopPx) {
                        ballDragged = true
                        ball.translationX = ballStartTx + dx
                        ball.translationY = ballStartTy + dy
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!ballDragged) showAssistPanel()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 聊天窗：标题栏当拖动手柄（继续拖不拦截子 View 点击），
     * × 收起，发送按钮/键盘回车都触发提问。
     */
    private fun setupAssistPanel() {
        // 消息区可滚动
        binding.tvAssistLog.movementMethod = ScrollingMovementMethod()
        if (chatLog.isEmpty()) {
            chatLog.append("助手：${getString(R.string.assist_greeting)}\n\n")
            binding.tvAssistLog.text = chatLog
        }

        // 标题栏拖动手柄：按下/移动改面板 translation
        binding.assistHeader.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    panelDownX = event.rawX
                    panelDownY = event.rawY
                    panelStartTx = binding.assistPanel.translationX
                    panelStartTy = binding.assistPanel.translationY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    binding.assistPanel.translationX = panelStartTx + (event.rawX - panelDownX)
                    binding.assistPanel.translationY = panelStartTy + (event.rawY - panelDownY)
                    true
                }
                else -> false
            }
        }

        binding.btnAssistClose.setOnClickListener { binding.assistPanel.visibility = View.GONE }

        binding.btnAssistSend.setOnClickListener { sendQuestion() }
        binding.etAssistInput.setOnEditorActionListener { _, _, _ ->
            sendQuestion(); true
        }
    }

    private fun showAssistPanel() {
        binding.assistPanel.visibility = View.VISIBLE
        scrollToLatest()
    }

    private fun sendQuestion() {
        val q = binding.etAssistInput.text?.toString()?.trim().orEmpty()
        if (q.isEmpty()) return
        binding.etAssistInput.setText("")
        chatLog.append("你：$q\n")
        chatLog.append("助手：${AssistantBrain.answer(q)}\n\n")
        binding.tvAssistLog.text = chatLog
        scrollToLatest()
    }

    /** 滚到最新一条消息 */
    private fun scrollToLatest() {
        binding.tvAssistLog.post {
            binding.assistScroll.fullScroll(View.FOCUS_DOWN)
        }
    }
}
