package com.pixellab.app.ui.assist

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.pixellab.app.R
import com.pixellab.app.databinding.FragmentAssistSheetBinding

/**
 * AI 助手弹层：底部滑出，消息区 + 输入框 + 发送按钮。
 *
 * 实现要点：
 *   · 用 BottomSheetDialogFragment 而不是新页面——问答是"顺手一问"，
 *     弹层不打断当前编辑上下文，下滑即可关闭；
 *   · 消息区一个 TextView 足够（问答轮次用"你：…\n我：…"分隔），
 *     配 ScrollingMovementMethod 让它自己滚动，避免引入 RecyclerView 的复杂度；
 *   · 回答是本地计算（AssistantBrain），毫秒级返回，无需异步。
 */
class AssistantSheet : BottomSheetDialogFragment() {

    private var _binding: FragmentAssistSheetBinding? = null
    private val binding get() = _binding!!

    /** 会话历史：问答按轮次拼接，输入框清空后仍可回看 */
    private val log = StringBuilder()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAssistSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvTitle.text = getString(R.string.assist_title)
        binding.tvSubtitle.text = getString(R.string.assist_subtitle)
        binding.etInput.hint = getString(R.string.assist_input_hint)
        binding.btnSend.text = getString(R.string.assist_send)

        // 消息区可滚动
        binding.tvLog.movementMethod = ScrollingMovementMethod()

        if (log.isEmpty()) {
            log.append("助手：${AssistantBrain.greeting()}\n\n")
        }
        binding.tvLog.text = log

        binding.btnSend.setOnClickListener { send() }
        // 点键盘"发送"键同样触发
        binding.etInput.setOnEditorActionListener { _, _, _ ->
            send(); true
        }
    }

    private fun send() {
        val q = binding.etInput.text?.toString()?.trim().orEmpty()
        if (q.isEmpty()) return
        binding.etInput.setText("")
        log.append("你：$q\n")
        log.append("助手：${AssistantBrain.answer(q)}\n\n")
        binding.tvLog.text = log
        // 滚到最新消息
        binding.tvLog.post {
            val layout = binding.tvLog.layout ?: return@post
            val max = layout.getLineTop(binding.tvLog.lineCount) - binding.tvLog.height
            binding.tvLog.scrollTo(0, max.toInt().coerceAtLeast(0))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AssistantSheet"
    }
}
