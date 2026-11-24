package com.pixellab.app.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
 *   2. 把 BottomNavigationView 和 Navigation 绑定：tab id == nav_graph destination id
 *   3. 托管全局 AI 助手：浮窗球（可拖拽）+ 悬浮聊天窗（可拖拽、边操作边对话）
 *
 * 助手聊天窗是覆盖层 View 而不是 Dialog/BottomSheet：
 *   Dialog 会加遮罩、暂停下层交互；覆盖层只占一角，页面照常点按。
 * 两个拖拽都做了屏幕边界钳制（clampToScreen），拖不出去。
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

    /** 面板"零偏移"时的屏幕坐标（首次显示时记录），钳制计算用 */
    private var panelBaseX = Float.NaN
    private var panelBaseY = 0f

    /** 会话历史：Spannable 让"你/助手"前缀着色区分，面板收起再展开不清空 */
    private val chatLog = SpannableStringBuilder()

    /** 浮窗球呼吸动画（面板展开时 pause，收起时 resume） */
    private lateinit var pulse: AnimatorSet

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHost = supportFragmentManager.findFragmentById(R.id.navHostFragment) as NavHostFragment
        NavigationUI.setupWithNavController(binding.bottomNav, navHost.navController)

        setupAssistBall()
        setupAssistPanel()
        startBallPulse()
    }

    /**
     * 浮窗球呼吸感：1.0 → 1.06 无限往复的轻微缩放。
     * 面板展开时暂停（避免和内容抢注意力），收起时恢复。
     */
    private fun startBallPulse() {
        val sx = ObjectAnimator.ofFloat(binding.assistBall, View.SCALE_X, 1f, 1.06f)
        val sy = ObjectAnimator.ofFloat(binding.assistBall, View.SCALE_Y, 1f, 1.06f)
        listOf(sx, sy).forEach {
            it.duration = 1300
            it.repeatCount = ValueAnimator.INFINITE
            it.repeatMode = ValueAnimator.REVERSE
        }
        pulse = AnimatorSet().apply { playTogether(sx, sy); start() }
    }

    /**
     * 松手吸附：球心在屏幕左半 → 吸到左缘，否则右缘；同时把 Y 钳回屏内。
     * 布局基准位置 left/top = x − translationX，目标偏移按它反推。
     */
    private fun snapBall(ball: View) {
        val dm = resources.displayMetrics
        val m = 12 * dm.density
        val baseX = ball.x - ball.translationX
        val baseY = ball.y - ball.translationY
        val tx = if (ball.x + ball.width / 2f < dm.widthPixels / 2f)
            m - baseX
        else
            dm.widthPixels - m - ball.width - baseX
        val ty = ball.translationY.coerceIn(
            m - baseY,
            dm.heightPixels - m - ball.height - baseY
        )
        ball.animate().translationX(tx).translationY(ty).setDuration(260).start()
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
                    if (ballDragged) snapBall(ball) else showAssistPanel()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 聊天窗：标题栏当拖动手柄（拖拽时钳制在屏幕内），
     * × 收起，发送按钮/键盘回车都触发提问。
     */
    private fun setupAssistPanel() {
        binding.tvAssistLog.movementMethod = ScrollingMovementMethod()
        appendChat(getString(R.string.assist_title), getString(R.string.assist_greeting))

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
                    val dm = resources.displayMetrics
                    val dx = event.rawX - panelDownX
                    val dy = event.rawY - panelDownY
                    binding.assistPanel.translationX = clampAxis(
                        panelStartTx + dx, panelBaseX, binding.assistPanel.width, dm.widthPixels)
                    binding.assistPanel.translationY = clampAxis(
                        panelStartTy + dy, panelBaseY, binding.assistPanel.height, dm.heightPixels)
                    true
                }
                else -> false
            }
        }

        binding.btnAssistClose.setOnClickListener {
            binding.assistPanel.visibility = View.GONE
            pulse.resume()   // 收起后恢复浮窗球呼吸
        }

        binding.btnAssistSend.setOnClickListener { sendQuestion() }
        binding.etAssistInput.setOnEditorActionListener { _, _, _ ->
            sendQuestion(); true
        }
    }

    /** 把偏移量钳制在"面板完整留在屏幕内"的范围内（留 8dp 边距） */
    private fun clampAxis(offset: Float, base: Float, size: Int, screenPx: Int): Float {
        if (base.isNaN()) return offset
        val m = 8 * resources.displayMetrics.density
        val min = m - base                          // 左/上边界：面板前缘不越屏
        val max = screenPx - m - base - size        // 右/下边界
        return offset.coerceIn(min, max)
    }

    private fun showAssistPanel() {
        binding.assistPanel.visibility = View.VISIBLE
        pulse.pause()   // 面板展开时暂停呼吸，不抢注意力
        // 首次显示后记录"零偏移基准位置"（post 确保布局完成）
        if (panelBaseX.isNaN()) {
            binding.assistPanel.post {
                val loc = IntArray(2)
                binding.assistPanel.getLocationOnScreen(loc)
                panelBaseX = loc[0].toFloat() - binding.assistPanel.translationX
                panelBaseY = loc[1].toFloat() - binding.assistPanel.translationY
            }
        }
        scrollToLatest()
    }

    /** 一问一答追加到消息区，说话人前缀着色 + 加粗，一眼区分双方 */
    private fun appendChat(who: String, text: String) {
        val start = chatLog.length
        chatLog.append("$who：$text\n\n")
        val color = ContextCompat.getColor(
            this, if (who == getString(R.string.assist_title)) R.color.primary else R.color.secondary
        )
        chatLog.setSpan(ForegroundColorSpan(color), start, start + who.length + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        chatLog.setSpan(StyleSpan(Typeface.BOLD), start, start + who.length + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        binding.tvAssistLog.text = chatLog
    }

    private fun sendQuestion() {
        val q = binding.etAssistInput.text?.toString()?.trim().orEmpty()
        if (q.isEmpty()) return
        binding.etAssistInput.setText("")
        appendChat("你", q)
        appendChat(getString(R.string.assist_title), AssistantBrain.answer(q))
        scrollToLatest()
    }

    /** 滚到最新一条消息 */
    private fun scrollToLatest() {
        binding.tvAssistLog.post {
            binding.assistScroll.fullScroll(View.FOCUS_DOWN)
        }
    }
}
