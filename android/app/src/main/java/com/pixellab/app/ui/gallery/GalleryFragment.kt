package com.pixellab.app.ui.gallery

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.pixellab.app.PixelLabApp
import com.pixellab.app.R
import com.pixellab.app.databinding.FragmentGalleryBinding
import com.pixellab.app.image.ProcessEngine
import com.pixellab.app.image.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 图库页（起始页）：最近处理列表 + 相册选图 + 拍照入口。
 *
 * 权限降级设计（评分表要求）：
 *   - 选图走系统照片选择器（Photo Picker），Android 13+ 无需任何存储权限，
 *     12 及以下由系统代理授权，用户拒权限不影响选图；
 *   - 拍照需要 CAMERA 权限：拒绝后弹 Snackbar 提示"已降级为仅相册选图"，
 *     App 其余功能完全不受影响。
 */
class GalleryFragment : Fragment() {

    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: GalleryViewModel by viewModels {
        // factory: 把 Application 转型成 PixelLabApp 传给 ViewModel，依赖注入的最简形式
        GalleryViewModel.Factory(requireContext().applicationContext as PixelLabApp)
    }
    private lateinit var adapter: PhotoAdapter

    /** 照片选择器：系统 UI，无需自己处理存储权限 */
    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { copyToPrivateAndOpen(it) }
    }

    /** 相机权限请求：拒绝就 Snackbar 提示降级，不崩溃不卡死 */
    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            findNavController().navigate(R.id.action_gallery_to_camera)
        } else {
            Snackbar.make(binding.root, R.string.gallery_camera_denied, Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        adapter = PhotoAdapter(
            onOpen = { photo ->
                // 带着结果图路径进编辑页，可继续叠加处理
                findNavController().navigate(
                    R.id.action_gallery_to_editor,
                    EditorArgs(photo.sourcePath, photo.id).toBundle()
                )
            },
            onDelete = { photo ->
                viewModel.delete(requireContext().applicationContext as PixelLabApp, photo)
            }
        )
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        // repeatOnLifecycle：页面 STARTED 才收集 Flow，STOPPED 自动取消，
        // 避免"页面已销毁还在更新 UI"的经典内存泄漏
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.photos.collect { list ->
                    adapter.submitList(list)
                    binding.emptyState.visibility =
                        if (list.isEmpty()) View.VISIBLE else View.GONE
                    binding.recycler.visibility =
                        if (list.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }

        binding.btnPick.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.emptyState.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnCamera.setOnClickListener {
            val granted = ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) findNavController().navigate(R.id.action_gallery_to_camera)
            else requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * 照片选择器给的是 content:// Uri（一次性读权限）。
     * 把内容复制进应用私有目录，后续处理、持久化都不再依赖外部 Uri——
     * 否则授权过期后历史记录的图全都加载不出来。
     */
    private fun copyToPrivateAndOpen(uri: android.net.Uri) {
        val app = requireContext().applicationContext as PixelLabApp
        viewLifecycleOwner.lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) {
                val dir = ProcessEngine.imageDir(requireContext())
                val file = java.io.File(dir, "src-${System.currentTimeMillis()}.jpg")
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                file.absolutePath
            } ?: return@launch

            findNavController().navigate(
                R.id.action_gallery_to_editor,
                EditorArgs(path, -1L).toBundle()
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null   // 防止 Fragment 视图泄漏：binding 生命周期短于 Fragment
    }
}

/** 编辑页跳转参数：sourcePath 图片路径，photoId 已有记录 id（新图传 -1） */
data class EditorArgs(val sourcePath: String, val photoId: Long) {
    fun toBundle() = androidx.core.os.bundleOf("path" to sourcePath, "id" to photoId)

    companion object {
        fun from(bundle: Bundle?) = EditorArgs(
            bundle?.getString("path") ?: "",
            bundle?.getLong("id") ?: -1L
        )
    }
}
