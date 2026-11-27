package com.pixellab.app.ui.editor

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pixellab.app.R

/**
 * 自定义卷积核编辑器（9 宫格输入）。
 *
 * 用户输入 9 个权重（行优先）→ 生成"专属滤镜"：
 *   中心 9、邻居 -1 = 深度锐化；全 1/9 = 均值模糊；某个角落 1 其余 0 = 平移复制……
 * 锐化/边缘/浮雕/模糊本质都是同一个卷积的不同权重组合，
 * 这个对话框把"通用 3×3 引擎"的通用性真正暴露出来。
 */
class CustomKernelDialog : DialogFragment() {

    companion object {
        /** 默认核：USM 风格锐化（比 SHARPEN 更柔和，适合当编辑起点） */
        val DEFAULT_KERNEL = floatArrayOf(
            0f, -1f, 0f,
            -1f, 5f, -1f,
            0f, -1f, 0f
        )
    }

    private lateinit var inputs: List<EditText>

    override fun onCreateDialog(saved: Bundle?): Dialog {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
        }

        root.addView(TextView(ctx).apply {
            text = getString(R.string.kernel_subtitle)
            textSize = 12f
            setTextColor(Color.GRAY)
        })

        // 3×3 输入宫格
        val grid = GridLayout(ctx).apply {
            columnCount = 3
            rowCount = 3
            val pad = (10 * dp).toInt()
            setPadding(0, pad, 0, 0)
        }
        inputs = (0 until 9).map {
            EditText(ctx).apply {
                hint = "0"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                gravity = Gravity.CENTER
                setText(formatFloat(DEFAULT_KERNEL[it]))
                layoutParams = GridLayout.LayoutParams().apply {
                    width = (92 * dp).toInt()
                    height = (64 * dp).toInt()
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(4, 4, 4, 4)
                }
            }
        }
        inputs.forEach { grid.addView(it) }
        root.addView(grid)

        root.addView(TextView(ctx).apply {
            text = getString(R.string.kernel_hint)
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, (10 * dp).toInt(), 0, 0)
        })

        return MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.kernel_title)
            .setView(root)
            .setPositiveButton(R.string.kernel_apply) { _, _ ->
                val kernel = FloatArray(9)
                for (i in 0 until 9) {
                    val v = inputs[i].text.toString().toFloatOrNull()
                    if (v == null) {
                        Toast.makeText(ctx, R.string.kernel_invalid, Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    kernel[i] = v
                }
                (parentFragment as? KernelApplier)?.applyKernel(kernel)
                    ?: (activity as? KernelApplier)?.applyKernel(kernel)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun formatFloat(v: Float): String =
        if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()

    /** 宿主（编辑页 Fragment）实现这个接口接收核 */
    interface KernelApplier {
        fun applyKernel(kernel: FloatArray)
    }
}
