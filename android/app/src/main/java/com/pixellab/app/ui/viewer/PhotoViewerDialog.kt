package com.pixellab.app.ui.viewer

import android.app.Dialog
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.pixellab.app.R

/**
 * 全屏大图查看器（微信照片查看器的简化版）：
 *   黑底全屏 + 可捏合缩放的 ZoomableImageView + 底部说明文字。
 *
 * 用 DialogFragment 而不是新页面：
 *   - 生命周期/配置变更（转屏）由框架恢复；
 *   - show() / dismiss 一行接入，返回键自动关闭。
 *
 * 传入 Bitmap 而不是路径：编辑页手上就有解码好的位图，省一次磁盘 IO。
 */
class PhotoViewerDialog : DialogFragment() {

    private var bitmap: Bitmap? = null
    private var caption: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 传大 Bitmap 不走 arguments（Bundle 有 1MB 限制），用字段直传；
        // retainInstance 让转屏等配置变更不重建，位图引用不丢
        setRetainInstance(true)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.black)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.dialog_photo_viewer, container, false)
        val viewer = root.findViewById<ZoomableImageView>(R.id.viewerImage)
        val captionView = root.findViewById<TextView>(R.id.viewerCaption)
        val btnClose = root.findViewById<View>(R.id.viewerClose)

        viewer.setImageBitmap(bitmap)
        captionView.text = caption
        btnClose.setOnClickListener { dismiss() }
        // 点图片本体不关闭（缩放手势占用），点黑边区域关闭
        root.setOnClickListener { dismiss() }
        return root
    }

    override fun onStart() {
        super.onStart()
        // 撑满整个窗口（默认 Dialog 是 wrap 的）
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        dialog?.window?.setWindowAnimations(0)
    }

    companion object {
        /**
         * 工厂：bitmap + 说明文字（如"处理后 · 高斯模糊"）。
         * 位图对象直接挂在 Fragment 字段上，避开 TransactionTooLargeException。
         */
        fun newInstance(bmp: Bitmap, captionText: String): PhotoViewerDialog {
            return PhotoViewerDialog().apply {
                bitmap = bmp
                caption = captionText
            }
        }
    }
}
