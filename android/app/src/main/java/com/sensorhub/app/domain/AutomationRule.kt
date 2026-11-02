package com.sensorhub.app.domain

/**
 * 自动化规则的领域模型
 *
 * 规则 = "当某个通道的测量值满足某个条件时，执行某些动作"
 * 例：当 温度 > 28℃ 时 → 发通知 + 点亮 LED(128) + 蜂鸣器响 300ms
 *
 * 为什么这是"自定义业务逻辑模块"？
 *   课程要求"至少一项自定义业务逻辑"。规则引擎不是 CRUD，它有自己的
 *   数据结构（条件树）、求值逻辑（阈值比较 + 防抖）、执行逻辑（动作派发），
 *   可以脱离 UI 单独测试——这就是一个完整的业务模块。
 */

/** 可参与判断的数据通道 */
enum class RuleChannel(val displayName: String, val unit: String) {
    TEMPERATURE("温度", "℃"),
    HUMIDITY("湿度", "%"),
    PRESSURE("气压", "hPa"),
    LIGHT("光照", "lux");

    /** 从一条读数里取出本通道的值 */
    fun extract(data: com.sensorhub.app.protocol.SensorData): Float = when (this) {
        TEMPERATURE -> data.temperature
        HUMIDITY -> data.hum
        PRESSURE -> data.press
        LIGHT -> data.lux.toFloat()
    }
}

/** 比较运算符 */
enum class RuleOperator(val symbol: String, val displayName: String) {
    GREATER_THAN(">", "大于"),
    LESS_THAN("<", "小于");

    fun evaluate(value: Float, threshold: Float): Boolean = when (this) {
        GREATER_THAN -> value > threshold
        LESS_THAN -> value < threshold
    }
}

/** 一条规则 */
data class AutomationRule(
    val id: Long = 0,
    val name: String,
    val channel: RuleChannel,
    val operator: RuleOperator,
    val threshold: Float,
    val enabled: Boolean = true,

    // ---- 动作 ----
    val notifyEnabled: Boolean = true,          // 发系统通知
    val ledEnabled: Boolean = false,            // 点亮 LED
    val ledBrightness: Int = 128,               // LED 亮度 0~255
    val buzzerEnabled: Boolean = false,         // 蜂鸣器
    val buzzerDurationMs: Int = 300,

    /** 连续满足多少次才真正触发（防抖，避免临界值反复报警） */
    val triggerCount: Int = 1
)

/** 规则命中后要执行的动作集合 */
data class RuleAction(
    val rule: AutomationRule,
    val measuredValue: Float
)

/** 规则的自然语言描述，直接显示在列表里 */
fun AutomationRule.describe(): String {
    return "当 ${channel.displayName} ${operator.displayName} $threshold${channel.unit}"
}

fun AutomationRule.describeAction(): String {
    val actions = mutableListOf<String>()
    if (notifyEnabled) actions.add("通知")
    if (ledEnabled) actions.add("LED=$ledBrightness")
    if (buzzerEnabled) actions.add("蜂鸣${buzzerDurationMs}ms")
    return if (actions.isEmpty()) "无动作" else actions.joinToString(" / ")
}
