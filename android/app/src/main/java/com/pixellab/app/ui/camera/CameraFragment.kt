package com.pixellab.app.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.pixellab.app.R
import com.pixellab.app.databinding.FragmentCameraBinding
import java.io.File

/**
 * 相机页（CameraX）。
 *
 * 为什么用 CameraX 而不是 Camera2：CameraX 把 Camera2 的状态机、生命周期绑定、
 * 分辨率选择都替你管了——bindToLifecycle 一个调用，页面销毁自动释放相机。
 *
 * 权限降级：进页先查 CAMERA 权限，没有就请求；被拒 → 显示降级 UI，
 * 提供"去授权"按钮二次申请，绝不让 App 因权限崩溃。
 */
class CameraFragment : Fragment() {

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!
    private var imageCapture: ImageCapture? = null

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startCamera() else showDenied() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startCamera() else requestPermission.launch(Manifest.permission.CAMERA)

        binding.btnShot.setOnClickListener { takePhoto() }
        binding.btnGrant.setOnClickListener {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showDenied() {
        binding.preview.visibility = View.GONE
        binding.deniedState.visibility = View.VISIBLE
        binding.btnShot.isEnabled = false
        binding.btnGrant.visibility = View.VISIBLE
    }

    private fun startCamera() {
        // ProcessCameraProvider：框架级单例，管理相机的打开/释放
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.preview.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)   // 拍照快优先
                .build()

            try {
                // 解绑旧的再绑新的；unbindAll 保证重复进入页面不出 "camera in use"
                provider.unbindAll()
                provider.bindToLifecycle(
                    viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
                binding.deniedState.visibility = View.GONE
                binding.preview.visibility = View.VISIBLE
                binding.btnShot.isEnabled = true
            } catch (e: Exception) {
                // 模拟器摄像头可能被占用/不支持：降级提示而不是崩溃
                showDenied()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val dir = File(requireContext().filesDir, "pixellab").apply { mkdirs() }
        val file = File(dir, "cam-${System.currentTimeMillis()}.jpg")
        val opts = ImageCapture.OutputFileOptions.Builder(file).build()

        capture.takePicture(
            opts,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(out: ImageCapture.OutputFileResults) {
                    // 拍完直接进编辑页，把照片路径传过去
                    findNavController().navigate(
                        R.id.action_gallery_to_editor,
                        androidx.core.os.bundleOf("path" to file.absolutePath, "id" to -1L)
                    )
                }

                override fun onError(e: ImageCaptureException) {
                    android.widget.Toast.makeText(
                        requireContext(), "拍照失败：${e.message}", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
