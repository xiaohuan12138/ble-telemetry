package com.sensorhub.app

import com.sensorhub.app.domain.AutomationRule
import com.sensorhub.app.domain.RuleAction
import com.sensorhub.app.domain.RuleChannel
import com.sensorhub.app.domain.RuleEngine
import com.sensorhub.app.domain.RuleOperator
import com.sensorhub.app.domain.HysteresisComparator
import com.sensorhub.app.domain.diagnoseSensorHealth
import com.sensorhub.app.protocol.SensorData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则引擎单元测试
 *
 * 规则引擎是本项目"自定义业务逻辑"的核心，必须有测试证明它的
 * 防抖、冷却、停用等语义是对的——这些逻辑靠肉眼看代码很难保证正确。
 */
class RuleEngineTest {

    private fun data(temp: Float = 25f, hum: Float = 50f, lux: Int = 300, press: Float = 1013f) =
        SensorData(
            tempAht = temp, hum = hum, press = press, lux = lux,
            tempBmp = temp, flags = 0x07
        )

    private fun tempRule(threshold: Float = 28f, triggerCount: Int = 1) = AutomationRule(
        id = 1,
        name = "高温告警",
        channel = RuleChannel.TEMPERATURE,
        operator = RuleOperator.GREATER_THAN,
        threshold = threshold,
        enabled = true,
        triggerCount = triggerCount
    )

    @Test
    fun `超过阈值应触发`() {
        val engine = RuleEngine()
        val actions = engine.evaluate(data(temp = 30f), listOf(tempRule()))
        assertEquals(1, actions.size)
        assertEquals(30f, actions[0].measuredValue, 0.01f)
    }

    @Test
    fun `未超过阈值不应触发`() {
        val engine = RuleEngine()
        assertTrue(engine.evaluate(data(temp = 25f), listOf(tempRule())).isEmpty())
    }

    @Test
    fun `小于运算符应正确工作`() {
        val rule = tempRule().copy(operator = RuleOperator.LESS_THAN, threshold = 10f)
        val engine = RuleEngine()
        assertTrue("5℃ < 10℃ 应触发", engine.evaluate(data(temp = 5f), listOf(rule)).isNotEmpty())
        assertTrue("20℃ 不小于 10℃，不应触发", engine.evaluate(data(temp = 20f), listOf(rule)).isEmpty())
    }

    @Test
    fun `连续命中次数不足时不应触发`() {
        val engine = RuleEngine()
        val rule = tempRule(triggerCount = 3)

        // 第 1、2 次命中但不触发
        assertTrue(engine.evaluate(data(temp = 30f), listOf(rule)).isEmpty())
        assertTrue(engine.evaluate(data(temp = 30f), listOf(rule)).isEmpty())
        // 第 3 次才触发
        assertEquals(1, engine.evaluate(data(temp = 30f), listOf(rule)).size)
    }

    @Test
    fun `中途不满足应重置连续计数`() {
        val engine = RuleEngine()
        val rule = tempRule(triggerCount = 3)

        engine.evaluate(data(temp = 30f), listOf(rule))   // 连续命中 1
        engine.evaluate(data(temp = 30f), listOf(rule))   // 连续命中 2
        engine.evaluate(data(temp = 20f), listOf(rule))   // 不满足 → 计数归零
        engine.evaluate(data(temp = 30f), listOf(rule))   // 重新累积到 1
        engine.evaluate(data(temp = 30f), listOf(rule))   // 重新累积到 2

        // 若"归零"逻辑没生效，这里会累计到 5 次而触发；
        // 归零生效的话只累积了 2 次，不应触发
        assertTrue(
            "中途不满足必须让连续计数归零",
            engine.evaluate(data(temp = 30f), listOf(rule)).isEmpty()
        )

        // 再给一次，此时才凑够 3 次
        assertEquals(1, engine.evaluate(data(temp = 30f), listOf(rule)).size)
    }

    @Test
    fun `冷却时间内不应重复触发`() {
        val engine = RuleEngine()
        engine.cooldownMs = 10_000L
        val rule = tempRule()

        assertEquals(1, engine.evaluate(data(temp = 30f), listOf(rule)).size)
        // 立刻再来一次，处于冷却中
        assertTrue(engine.evaluate(data(temp = 30f), listOf(rule)).isEmpty())
    }

    @Test
    fun `停用的规则不应触发`() {
        val engine = RuleEngine()
        val rule = tempRule().copy(enabled = false)
        assertTrue(engine.evaluate(data(temp = 40f), listOf(rule)).isEmpty())
    }

    @Test
    fun `多规则可同时命中`() {
        val engine = RuleEngine()
        val highTemp = tempRule()
        val dark = AutomationRule(
            id = 2, name = "光线不足",
            channel = RuleChannel.LIGHT, operator = RuleOperator.LESS_THAN,
            threshold = 50f, enabled = true
        )
        val actions = engine.evaluate(data(temp = 35f, lux = 10), listOf(highTemp, dark))
        assertEquals(2, actions.size)
    }

    @Test
    fun `reset 应清空内部状态`() {
        val engine = RuleEngine()
        val rule = tempRule(triggerCount = 3)
        engine.evaluate(data(temp = 30f), listOf(rule))
        engine.reset()
        // 重置后重新计数，两个周期不足以触发
        engine.evaluate(data(temp = 30f), listOf(rule))
        assertTrue(engine.evaluate(data(temp = 30f), listOf(rule)).isEmpty())
    }

    // ---------------- 滞回比较器 ----------------

    @Test
    fun `滞回比较器应在死区内保持稳定`() {
        val comp = HysteresisComparator(onThreshold = 28f, offThreshold = 27f)

        assertFalse(comp.update(27.9f))          // 未达开启阈值
        assertTrue(comp.update(28.1f))           // 开启，状态翻转
        assertFalse(comp.update(27.9f))          // 落在死区，不翻转
        assertTrue(comp.isOn)
        org.junit.Assert.assertFalse(comp.update(26.9f))  // 低于关闭阈值 → 关闭
        assertFalse(comp.isOn)
    }

    // ---------------- 健康诊断 ----------------

    @Test
    fun `温度源偏差过大应报异常`() {
        val bad = SensorData(
            tempAht = 30f, hum = 50f, press = 1013f, lux = 100,
            tempBmp = 25f, flags = 0x07
        )
        assertTrue(diagnoseSensorHealth(bad).contains("偏差"))
    }

    @Test
    fun `单一传感器缺失应给出降级说明`() {
        val onlyBmp = SensorData(
            tempAht = 0f, hum = 0f, press = 1013f, lux = 0,
            tempBmp = 25f, flags = 0x02     // 只有 BMP280
        )
        assertTrue(diagnoseSensorHealth(onlyBmp).contains("BMP280"))
    }
}
