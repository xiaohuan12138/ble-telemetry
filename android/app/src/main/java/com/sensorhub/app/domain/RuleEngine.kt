package com.sensorhub.app.domain

import com.sensorhub.app.protocol.SensorData
import kotlin.math.abs

/**
 * 规则引擎
 *
 * 职责：拿到一条新读数 → 遍历所有启用的规则 → 判断哪些命中 → 返回要执行的动作
 *
 * 三个容易被忽略但必须处理的工程细节：
 *
 * 1) 【防抖 / 去抖】临界值抖动问题
 *    假设阈值 28.0℃，实际温度在 27.9~28.1 之间来回跳。
 *    如果每次跨越阈值都报警，用户会被通知轰炸。
 *    解决方案有两类：
 *      a) 连续命中 N 次才触发（本项目用这个，字段 triggerCount）
 *      b) 滞回（施密特触发器）：触发阈值 28.0，解除阈值 27.5
 *    本项目选 a，因为规则可配置、更好演示；实际生产建议 a+b 都用。
 *
 * 2) 【冷却时间】同一条规则短时间内不重复触发
 *    连续满足 100 次也只报一次，5 秒内不重复打扰用户。
 *
 * 3) 【规则状态是有状态的】所以这个类不是 object 而是 class，
 *    每条规则要记住"我已经连续命中几次了"。
 *    这也意味着它不能在多个设备间共享——每个连接一个实例。
 */
class RuleEngine {

    // 规则 id → 连续命中次数
    private val hitCounters = mutableMapOf<Long, Int>()

    // 规则 id → 上次触发时刻（毫秒）
    private val lastTriggerAt = mutableMapOf<Long, Long>()

    /** 冷却时间：同一规则 5 秒内不重复触发 */
    var cooldownMs: Long = 5_000L

    /**
     * 对一条新读数求值
     *
     * @param data  新读数
     * @param rules 所有规则（引擎内部会过滤掉未启用的）
     * @return 本次需要执行的动作列表
     */
    fun evaluate(data: SensorData, rules: List<AutomationRule>): List<RuleAction> {
        val now = System.currentTimeMillis()
        val triggered = mutableListOf<RuleAction>()

        for (rule in rules) {
            if (!rule.enabled) {
                hitCounters[rule.id] = 0      // 停用就把计数清零，下次启用从 0 开始
                continue
            }

            val value = rule.channel.extract(data)
            val matched = rule.operator.evaluate(value, rule.threshold)

            if (!matched) {
                hitCounters[rule.id] = 0      // 一旦不满足，连续计数归零
                continue
            }

            // 连续命中 +1
            val hits = (hitCounters[rule.id] ?: 0) + 1
            hitCounters[rule.id] = hits

            if (hits < rule.triggerCount) continue          // 还没连续够 N 次

            // 冷却检查
            val last = lastTriggerAt[rule.id] ?: 0L
            if (now - last < cooldownMs) continue           // 冷却中，跳过

            lastTriggerAt[rule.id] = now
            hitCounters[rule.id] = 0                        // 触发后重新计数
            triggered.add(RuleAction(rule, value))
        }

        return triggered
    }

    /** 断开重连、切换设备时调用，清空所有内部状态 */
    fun reset() {
        hitCounters.clear()
        lastTriggerAt.clear()
    }
}

/**
 * 滞回比较器（Hysteresis Comparator），也叫施密特触发器
 *
 * 本项目规则引擎用的是"连续 N 次"方案，这个类提供另一种可选方案，
 * 放在这里是为了在答辩时展示"我知道还有别的做法以及各自的取舍"。
 *
 * 原理：上升阈值和下降阈值不同。
 *   温度升到 28.0 → 报警（ON）
 *   温度回到 27.5 才解除（OFF），27.8 不算解除
 *   中间 0.5℃ 的死区杜绝了反复横跳。
 */
class HysteresisComparator(
    private val onThreshold: Float,
    private val offThreshold: Float
) {
    private var state = false

    init {
        require(onThreshold > offThreshold) {
            "开启阈值必须大于关闭阈值，否则会同时满足两个条件导致逻辑矛盾"
        }
    }

    /** @return true 表示本次发生了状态翻转 */
    fun update(value: Float): Boolean {
        val newState = when {
            !state && value >= onThreshold -> true
            state && value <= offThreshold -> false
            else -> state                       // 落在死区里，保持原状
        }
        val changed = newState != state
        state = newState
        return changed
    }

    val isOn: Boolean get() = state
}

/**
 * 传感器健康诊断：双温度源交叉校验
 *
 * 返回一个可读的诊断结论，直接展示在 UI 上。
 * 这是"冗余设计"最直观的体现——不用额外硬件就能发现传感器异常。
 */
fun diagnoseSensorHealth(data: SensorData): String {
    return when {
        !data.ahtOk && !data.bmpOk -> "两个温度源均无响应，检查 I2C 接线"
        !data.ahtOk -> "AHT20 无响应，当前温度来自 BMP280"
        !data.bmpOk -> "BMP280 无响应，气压不可用"
        !data.tempConsistent -> {
            val diff = abs(data.tempAht - data.tempBmp)
            "温度源偏差 ${"%.1f".format(diff)}℃ 超过 2℃ 阈值，疑似某颗传感器漂移"
        }
        else -> "传感器全部正常"
    }
}
