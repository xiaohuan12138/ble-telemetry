package com.pixellab.app.ui.editor

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pixellab.app.R
import com.pixellab.app.image.FilterCatalog
import com.pixellab.app.image.ProcessEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 算法横向对比模式：同一张图 × 均值/高斯/中值/双边 四个滤波，
 * 2×2 网格展示结果，每个格子里标注"滤镜名 · 耗时 ms"。
 * "同一输入、同一管线、只换算法"——效果差异和性能差异一屏看完。
 */
class CompareDialog : DialogFragment() {

    companion object {
        /** 传入待对比的底图（编辑页的 base） */
        fun newInstance(source: Bitmap) = CompareDialog().apply {
            this.source = source
        }
    }

    private var source: Bitmap? = null

    override fun onCreateDialog(saved: Bundle?): Dialog {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
        }
        root.addView(TextView(ctx).apply {
            text = getString(R.string.compare_subtitle)
            textSize = 12f
            setTextColor(Color.GRAY)
        })

        val grid = GridLayout(ctx).apply {
            columnCount = 2
            rowCount = 2
            setPadding(0, (10 * dp).toInt(), 0, 0)
        }
        val cells = FilterCatalog.compareKeys.map { key ->
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(6, 6, 6, 6)
                }
                val iv = ImageView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (150 * dp).toInt()
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(Color.parseColor("#F5F5F5"))
                }
                val tv = TextView(ctx).apply {
                    textSize = 11f
                    gravity = Gravity.CENTER
                    text = FilterCatalog.displayName(key)
                    setPadding(0, (4 * dp).toInt(), 0, 0)
                }
                addView(iv); addView(tv)
                tag = Pair(iv, tv)
            }
        }
        cells.forEach { grid.addView(it) }
        root.addView(grid)

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.compare_title)
            .setView(root)
            .setPositiveButton(android.R.string.ok, null)
            .create()

        // 依次跑四个滤波：逐个回填（双边最贵，放最后，先出图先看效果）
        val bmp = source
        if (bmp != null) {
            lifecycleScope.launch {
                var done = 0
                for ((idx, key) in FilterCatalog.compareKeys.withIndex()) {
                    val (result, cost) = withContext(Dispatchers.Default) {
                        ProcessEngine.applyFilter(bmp, key)
                    }
                    val (iv, tv) = cells[idx].tag as Pair<ImageView, TextView>
                    iv.setImageBitmap(result)
                    tv.text = getString(R.string.compare_cost_format, FilterCatalog.displayName(key), cost)
                    done++
                    dialog.setTitle(getString(R.string.compare_running, done))
                }
                dialog.setTitle(getString(R.string.compare_title))
            }
        }
        return dialog
    }
}
