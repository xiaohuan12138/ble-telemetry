package com.pixellab.app.ui.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.slider.Slider
import com.pixellab.app.PixelLabApp
import com.pixellab.app.R
import com.pixellab.app.databinding.FragmentEditorBinding
import com.pixellab.app.image.FilterCatalog
import com.pixellab.app.share.CardGenerator
import com.pixellab.app.ui.gallery.EditorArgs
import com.pixellab.app.ui.viewer.PhotoViewerDialog
import kotlinx.coroutines.launch

/**
 * 编辑页：滤镜/几何变换/OCR/保存/分享。
 * 全部状态来自 EditorViewModel 的 StateFlow，Fragment 只做"渲染 + 转发点击"。
 */
class EditorFragment : Fragment(), CustomKernelDialog.KernelApplier {

    private var _binding: FragmentEditorBinding? = null
    private val binding get() = _binding!!
    private val viewModel: EditorViewModel by viewModels {
        EditorViewModel.Factory(requireContext().applicationContext as PixelLabApp)
    }
    /** "自定义核"芯片不走 selectFilter，而是弹出 9 宫格输入对话框 */
    private val chipAdapter = FilterChipAdapter { chip ->
        if (chip.key == "CUSTOM") {
            CustomKernelDialog().show(childFragmentManager, "kernel")
        } else {
            viewModel.selectFilter(chip.key)
        }
    }

    /** 最近一次已展示的"处理后"位图（crossfade 判重用） */
    private var lastAfterShown: android.graphics.Bitmap? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        // 从跳转参数拿图片路径并加载（ViewModel 里做了"已加载不重复"保护）
        val args = EditorArgs.from(arguments)
        viewModel.load(args.sourcePath)

        binding.chipRow.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.chipRow.adapter = chipAdapter
        chipAdapter.submitList(FilterChipAdapter.DEFAULT)

        binding.btnApply.setOnClickListener { viewModel.applyFilter() }
        binding.btnReset.setOnClickListener { viewModel.reset() }
        binding.btnUndo.setOnClickListener { viewModel.undo() }
        binding.btnRotate.setOnClickListener { viewModel.rotate() }
        binding.btnCrop11.setOnClickListener { viewModel.crop("1:1") }
        binding.btnCrop43.setOnClickListener { viewModel.crop("4:3") }
        binding.btnCrop169.setOnClickListener { viewModel.crop("16:9") }
        binding.btnOcr.setOnClickListener { viewModel.runOcr() }
        binding.btnSave.setOnClickListener { viewModel.save() }
        binding.btnCompare.setOnClickListener {
            val base = viewModel.state.value.base ?: return@setOnClickListener
            CompareDialog.newInstance(base).show(childFragmentManager, "compare")
        }
        binding.btnPdf.setOnClickListener { exportPdf() }

        // 滤镜强度：拖动实时显示百分比，松手才触发一次重算（避免连续卷积掉帧）
        binding.sliderStrength.addOnChangeListener { _, v, fromUser ->
            if (fromUser) binding.tvStrengthVal.text = "${(v * 100).toInt()}%"
        }
        binding.sliderStrength.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                viewModel.setStrength(slider.value)
                viewModel.applyFilter()
            }
        })

        // 亮度 / 对比度：线性点操作 y = a·x + b，与滤镜一起在重算管线里生效
        binding.sliderBrightness.addOnChangeListener { _, v, fromUser ->
            if (fromUser) binding.tvBrightnessVal.text = "${v.toInt()}"
        }
        binding.sliderBrightness.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                viewModel.setBrightness(slider.value.toInt())
                viewModel.applyFilter()
            }
        })
        binding.sliderContrast.addOnChangeListener { _, v, fromUser ->
            if (fromUser) binding.tvContrastVal.text = "${v.toInt()}"
        }
        binding.sliderContrast.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                viewModel.setContrast(slider.value.toInt())
                viewModel.applyFilter()
            }
        })

        // 点击前后图 → 全屏微信式查看器（捏合缩放 / 双击放大）
        binding.imgAfter.setOnClickListener {
            val s = viewModel.state.value
            val bmp = s.current ?: return@setOnClickListener
            PhotoViewerDialog.newInstance(
                bmp, getString(R.string.editor_viewer_caption,
                    getString(R.string.label_after), filterDisplayName(s.filterKey))
            ).show(childFragmentManager, "viewer_after")
        }
        binding.imgBefore.setOnClickListener {
            val s = viewModel.state.value
            val bmp = s.base ?: return@setOnClickListener
            PhotoViewerDialog.newInstance(
                bmp, getString(R.string.editor_viewer_caption,
                    getString(R.string.label_before), getString(R.string.editor_cost_idle))
            ).show(childFragmentManager, "viewer_before")
        }

        // P4：用"处理前 + 处理后 + 参数"生成对比卡片并调起系统分享
        binding.btnShare.setOnClickListener {
            val s = viewModel.state.value
            val source = s.base ?: return@setOnClickListener
            val result = s.current ?: return@setOnClickListener
            Snackbar.make(binding.root, R.string.editor_share_ready, Snackbar.LENGTH_SHORT).show()
            CardGenerator.shareResultCard(
                requireContext(), source, result, s.filterKey, s.costMs
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { s ->
                    // "处理前"对比基准 = base（几何变换后的底图），
                    // 对比滑块左右才是同一张图、同一个坐标系下的前后差异
                    binding.imgBefore.setImageBitmap(s.base)

                    // 结果图 crossfade：只在 Bitmap 真的换了时淡入，重复 collect 不闪
                    if (s.current !== lastAfterShown) {
                        lastAfterShown = s.current
                        binding.imgAfter.setImageBitmap(s.current)
                        binding.imgAfter.alpha = 0f
                        binding.imgAfter.animate().alpha(1f).setDuration(240).start()
                    }

                    // 滑动对比卡：两层像素级对齐，分割线由手势控制
                    binding.compareSlider.setImages(s.base, s.current)

                    binding.progress.visibility = if (s.busy) View.VISIBLE else View.GONE
                    binding.btnUndo.isEnabled = s.canUndo

                    // 横幅参数徽章：尺寸 / 耗时 / 滤镜名
                    val w = s.current?.width ?: 0
                    val h = s.current?.height ?: 0
                    binding.badgeSize.text = if (w > 0) "$w×$h" else "--×--"
                    binding.badgeCost.text =
                        if (s.costMs > 0) getString(R.string.editor_cost_format, s.costMs)
                        else getString(R.string.editor_cost_idle)
                    binding.badgeFilter.text = filterDisplayName(s.filterKey)

                    chipAdapter.selectedKey = s.filterKey

                    // 撤销/还原后把三个滑块与状态快照同步（fromUser=false 不触发重算，避免回环）
                    if (binding.sliderStrength.value != s.strength) binding.sliderStrength.value = s.strength
                    if (binding.sliderBrightness.value != s.brightness.toFloat())
                        binding.sliderBrightness.value = s.brightness.toFloat()
                    if (binding.sliderContrast.value != s.contrast.toFloat())
                        binding.sliderContrast.value = s.contrast.toFloat()
                    binding.tvStrengthVal.text = "${(s.strength * 100).toInt()}%"
                    binding.tvBrightnessVal.text = "${s.brightness}"
                    binding.tvContrastVal.text = "${s.contrast}"

                    // OCR 结果卡：有文字就展开，识别过但没文字显示"空"
                    if (s.ocrText != null) {
                        binding.ocrCard.visibility = View.VISIBLE
                        binding.tvOcr.text = s.ocrText
                    } else {
                        binding.ocrCard.visibility = View.GONE
                    }

                    s.savedId?.let {
                        Snackbar.make(binding.root, R.string.editor_saved, Snackbar.LENGTH_SHORT).show()
                        // 保存后回图库（历史上的"编辑已完成"闭环）
                        viewModel.clearSaved()
                        findNavController().popBackStack()
                    }
                }
            }
        }
    }

    /** 自定义核对话框回调：拿到 9 个权重直接进管线重算 */
    override fun applyKernel(kernel: FloatArray) {
        viewModel.applyCustomKernel(kernel)
    }

    /**
     * 导出 PDF 实验报告：处理前后对比图 + 参数 + 耗时 + 直方图 + 全套指标一键成文。
     * 统计和 PDF 绘制都在 IO/Default 池跑，主线程只收文件句柄调起分享。
     */
    private fun exportPdf() {
        val s = viewModel.state.value
        val before = s.base ?: return
        val after = s.current ?: return
        binding.progress.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.pixellab.app.share.ReportGenerator.generate(
                    requireContext(), before, after,
                    s.filterKey, s.strength, s.brightness, s.contrast, s.costMs
                )
            }
            binding.progress.visibility = View.GONE
            Snackbar.make(binding.root, R.string.pdf_ready, Snackbar.LENGTH_SHORT).show()
            com.pixellab.app.share.ReportGenerator.sharePdfReport(
                requireContext(), before, after,
                s.filterKey, s.strength, s.brightness, s.contrast, s.costMs
            )
        }
    }

    /** 滤镜显示名：统一走 FilterCatalog（芯片/徽章/历史/PDF 一处维护） */
    private fun filterDisplayName(key: String?): String = FilterCatalog.displayName(key)

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
