package com.sensorhub.app.ui.rules

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sensorhub.app.R
import com.sensorhub.app.databinding.DialogRuleBinding
import com.sensorhub.app.databinding.FragmentRulesBinding
import com.sensorhub.app.domain.AutomationRule
import com.sensorhub.app.domain.RuleChannel
import com.sensorhub.app.domain.RuleOperator
import kotlinx.coroutines.launch

/**
 * 规则页
 *
 * 演示"自定义业务逻辑模块"：规则可以被增删改查、开关，
 * 并且真的会在数据到来时被执行（执行逻辑在 SensorHubService 里）。
 */
class RulesFragment : Fragment() {

    private var _binding: FragmentRulesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: RulesViewModel by viewModels()
    private lateinit var adapter: RuleAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRulesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecycler()
        binding.fabAddRule.setOnClickListener { showAddRuleDialog() }
        observeRules()
    }

    private fun setupRecycler() {
        adapter = RuleAdapter(
            onToggle = { rule, enabled -> viewModel.toggleRule(rule.id, enabled) },
            onDelete = { rule -> viewModel.deleteRule(rule.id) }
        )
        binding.recyclerRules.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@RulesFragment.adapter
        }
    }

    private fun observeRules() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.rules.collect { list ->
                    adapter.submitList(list)
                    binding.recyclerRules.visibility =
                        if (list.isEmpty()) View.GONE else View.VISIBLE
                    binding.emptyState.visibility =
                        if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    /** 新建规则的对话框 */
    private fun showAddRuleDialog() {
        val dialogBinding = DialogRuleBinding.inflate(layoutInflater)

        // 通道下拉
        val channels = RuleChannel.entries.toTypedArray()
        dialogBinding.spChannel.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            channels.map { "${it.displayName}(${it.unit})" }
        )
        // 运算符下拉
        val operators = RuleOperator.entries.toTypedArray()
        dialogBinding.spOperator.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            operators.map { it.displayName }
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.btn_new_rule)
            .setView(dialogBinding.root)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ -> saveFromDialog(dialogBinding, channels, operators) }
            .show()
    }

    private fun saveFromDialog(
        b: DialogRuleBinding,
        channels: Array<RuleChannel>,
        operators: Array<RuleOperator>
    ) {
        val name = b.etRuleName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) return

        val threshold = b.etThreshold.text?.toString()?.toFloatOrNull() ?: return
        val triggerCount = b.etTriggerCount.text?.toString()?.toIntOrNull()?.coerceIn(1, 20) ?: 1

        val rule = AutomationRule(
            name = name,
            channel = channels[b.spChannel.selectedItemPosition],
            operator = operators[b.spOperator.selectedItemPosition],
            threshold = threshold,
            enabled = true,
            notifyEnabled = b.cbNotify.isChecked,
            ledEnabled = b.cbLed.isChecked,
            ledBrightness = 200,
            buzzerEnabled = b.cbBuzzer.isChecked,
            buzzerDurationMs = 300,
            triggerCount = triggerCount
        )
        viewModel.addRule(rule)
    }

    override fun onDestroyView() {
        binding.recyclerRules.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
