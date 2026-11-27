package com.pixellab.app.ui.demo

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import com.pixellab.app.R
import com.pixellab.app.databinding.FragmentDemoBinding

/**
 * 卷积滑窗动画演示页。
 *
 * "播放"按 500ms/格 自动扫描；"单步"逐格讲；计算式实时显示在下方——
 * 评委不用听懂术语，看着窗口滑动和算式就能明白卷积 = 加权求和。
 */
class DemoFragment : Fragment() {

    private var _binding: FragmentDemoBinding? = null
    private val binding get() = _binding!!
    private var playing = false

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            binding.demoView.step()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentDemoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        // 计算式由演示 View 每步回调
        binding.demoView.onExpressionChanged = { binding.tvExpr.text = it }

        binding.btnPlay.setOnClickListener {
            playing = !playing
            if (playing) {
                binding.btnPlay.text = getString(R.string.demo_pause)
                handler.postDelayed(tick, 500)
            } else {
                binding.btnPlay.text = getString(R.string.demo_play)
                handler.removeCallbacks(tick)
            }
        }
        binding.btnStep.setOnClickListener { binding.demoView.step() }
        binding.btnReset.setOnClickListener { binding.demoView.reset() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        playing = false
        handler.removeCallbacks(tick)
        _binding = null
    }
}
