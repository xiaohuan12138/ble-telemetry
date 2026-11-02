package com.sensorhub.app.ui.rules

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sensorhub.app.databinding.ItemRuleBinding
import com.sensorhub.app.domain.AutomationRule
import com.sensorhub.app.domain.describe
import com.sensorhub.app.domain.describeAction

/** 规则列表适配器 */
class RuleAdapter(
    private val onToggle: (AutomationRule, Boolean) -> Unit,
    private val onDelete: (AutomationRule) -> Unit
) : ListAdapter<AutomationRule, RuleAdapter.RuleViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RuleViewHolder {
        val binding = ItemRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RuleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RuleViewHolder, position: Int) {
        holder.bind(getItem(position), onToggle, onDelete)
    }

    class RuleViewHolder(private val binding: ItemRuleBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(
            rule: AutomationRule,
            onToggle: (AutomationRule, Boolean) -> Unit,
            onDelete: (AutomationRule) -> Unit
        ) {
            binding.tvRuleName.text = rule.name
            binding.tvRuleCondition.text = rule.describe()
            binding.tvRuleAction.text = rule.describeAction()

            // ⚠️ 关键细节：先移除监听器再设置 checked，否则设置 checked 时
            // 会触发上一轮的 onCheckedChanged，导致"滑动列表时规则被误开关"
            // 这是 RecyclerView 里 Switch 最经典的 bug
            binding.switchEnabled.setOnCheckedChangeListener(null)
            binding.switchEnabled.isChecked = rule.enabled
            binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggle(rule, isChecked)
            }

            binding.btnDelete.setOnClickListener { onDelete(rule) }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<AutomationRule>() {
        override fun areItemsTheSame(old: AutomationRule, new: AutomationRule) = old.id == new.id
        override fun areContentsTheSame(old: AutomationRule, new: AutomationRule) = old == new
    }
}
