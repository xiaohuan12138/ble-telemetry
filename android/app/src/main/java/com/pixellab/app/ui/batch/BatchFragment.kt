package com.pixellab.app.ui.batch

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pixellab.app.PixelLabApp
import com.pixellab.app.R
import com.pixellab.app.databinding.FragmentBatchBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 批量页：照片选择器多选 → 选滤镜 → WorkManager 后台整批套用。
 *
 * Uri → 文件路径的复制放在提交前一次性完成（IO 池），
 * Worker 拿到的永远是稳定的本地路径。
 */
class BatchFragment : Fragment() {

    private var _binding: FragmentBatchBinding? = null
    private val binding get() = _binding!!
    private val viewModel: BatchViewModel by viewModels {
        BatchViewModel.Factory(requireContext().applicationContext as PixelLabApp)
    }

    /** 多选照片：上限 9 张，防止一次性把内存队列塞爆 */
    private val pickMany = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.setPicked(uris)
            binding.btnStart.isEnabled = true
        }
    }

    /** 简单行适配器：文件名 + 状态 */
    private val rowAdapter = object : RecyclerView.Adapter<RowVH>() {
        private val items = mutableListOf<Pair<String, String>>()   // 名称 to 状态
        fun replace(list: List<Pair<String, String>>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH =
            RowVH(LayoutInflater.from(parent.context).inflate(R.layout.item_batch_row, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: RowVH, position: Int) {
            holder.name.text = items[position].first
            holder.status.text = items[position].second
        }
    }

    class RowVH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvName)
        val status: TextView = view.findViewById(R.id.tvStatus)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentBatchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        binding.batchRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.batchRecycler.adapter = rowAdapter

        // 滤镜 toggle group：tag 里存 key
        binding.filterGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val key = binding.filterGroup.findViewById<View>(checkedId).tag as? String
                if (key != null) viewModel.setFilter(key)
            }
        }

        binding.btnPickBatch.setOnClickListener {
            pickMany.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        binding.btnStart.setOnClickListener {
            // 提交前把 Uri 复制成本地文件（IO 池），然后交给 WorkManager
            val uris = viewModel.state.value.picked
            viewLifecycleOwner.lifecycleScope.launch {
                val paths = withContext(Dispatchers.IO) { copyUris(uris) }
                rowAdapter.replace(paths.map {
                    it.substringAfterLast('/') to getString(R.string.action_apply)
                })
                viewModel.start(paths)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { s ->
                    binding.batchProgress.visibility =
                        if (s.total > 0 && !s.finished) View.VISIBLE else View.GONE
                    binding.tvBatchStatus.visibility =
                        if (s.total > 0) View.VISIBLE else View.GONE

                    // 空态：既没有任务也没有结果行时，显示引导并隐藏进度区
                    val empty = s.total == 0 && rowAdapter.itemCount == 0
                    binding.emptyBatch.visibility = if (empty) View.VISIBLE else View.GONE
                    binding.tvBatchStatus.visibility =
                        if (empty) View.GONE else binding.tvBatchStatus.visibility
                    binding.batchProgress.visibility =
                        if (empty) View.GONE else binding.batchProgress.visibility

                    if (s.total > 0) {
                        binding.batchProgress.max = s.total
                        binding.batchProgress.progress = s.done
                        binding.tvBatchStatus.text =
                            if (s.finished) getString(R.string.batch_done, s.okCount)
                            else getString(R.string.batch_running, s.done, s.total)
                    }
                }
            }
        }
    }

    private fun copyUris(uris: List<Uri>): List<String> {
        val dir = com.pixellab.app.image.ProcessEngine.imageDir(requireContext())
        return uris.mapIndexedNotNull { index, uri ->
            runCatching {
                val file = java.io.File(dir, "batch-${System.currentTimeMillis()}-$index.jpg")
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                file.absolutePath
            }.getOrNull()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
