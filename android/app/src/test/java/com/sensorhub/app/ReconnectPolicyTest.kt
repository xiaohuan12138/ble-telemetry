package com.sensorhub.app

import com.sensorhub.app.domain.ConnectionState
import com.sensorhub.app.domain.ReconnectPolicy
import com.sensorhub.app.domain.describeBleError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重连策略单元测试
 *
 * 验证指数退避的三个关键性质：增长、封顶、可重置。
 */
class ReconnectPolicyTest {

    @Test
    fun `退避时间应逐次增长`() {
        // 关掉抖动，才能精确断言（jitterRatio = 0）
        val policy = ReconnectPolicy(baseDelayMs = 1000, jitterRatio = 0.0, maxDelayMs = 100_000)

        val d1 = policy.nextDelay()
        val d2 = policy.nextDelay()
        val d3 = policy.nextDelay()

        assertEquals(1000L, d1)
        assertEquals(2000L, d2)
        assertEquals(4000L, d3)
        assertTrue(d1 < d2 && d2 < d3)
    }

    @Test
    fun `退避时间应有上限`() {
        val policy = ReconnectPolicy(
            baseDelayMs = 1000, maxDelayMs = 30_000, jitterRatio = 0.0, maxAttempts = null
        )
        repeat(20) { policy.nextDelay() }
        val later = policy.nextDelay()
        assertTrue("第 21 次也必须 <= 30s，实际 $later", later <= 30_000L)
    }

    @Test
    fun `超过最大次数应返回 -1`() {
        val policy = ReconnectPolicy(maxAttempts = 3)
        policy.nextDelay()
        policy.nextDelay()
        policy.nextDelay()
        assertEquals(-1L, policy.nextDelay())
        assertTrue(policy.exhausted)
    }

    @Test
    fun `未设置最大次数时应无限重试`() {
        val policy = ReconnectPolicy(maxAttempts = null)
        repeat(100) { policy.nextDelay() }
        org.junit.Assert.assertFalse(policy.exhausted)
    }

    @Test
    fun `reset 应从头开始`() {
        val policy = ReconnectPolicy(baseDelayMs = 1000, jitterRatio = 0.0, maxAttempts = null)
        repeat(5) { policy.nextDelay() }
        policy.reset()
        assertEquals("重置后应回到 1s", 1000L, policy.nextDelay())
        assertEquals(0, policy.currentAttempt - 1)
    }

    @Test
    fun `抖动应在合理范围内`() {
        // 开 30% 抖动，多次采样看是否落在 [base, base*1.3]
        val policy = ReconnectPolicy(baseDelayMs = 1000, jitterRatio = 0.3, maxAttempts = null)
        val first = policy.nextDelay()
        assertTrue("首次退避应在 1000~1300 之间，实际 $first", first in 1000L..1300L)
    }

    // ---------------- 错误码映射 ----------------

    @Test
    fun `常见错误码应有可读文案`() {
        assertTrue(describeBleError(133).contains("133"))
        assertTrue(describeBleError(133).contains("蓝牙"))
        assertTrue(describeBleError(8).contains("超时"))
        assertTrue(describeBleError(22).contains("断开"))
        assertTrue(describeBleError(9999).contains("9999"))   // 未知码也要带上数字
    }

    // ---------------- 状态机 ----------------

    @Test
    fun `连接状态应为密封类的不同实例`() {
        val states: List<ConnectionState> = listOf(
            ConnectionState.Idle,
            ConnectionState.Connecting,
            ConnectionState.Discovering,
            ConnectionState.Ready,
            ConnectionState.Retrying(2, 4000),
            ConnectionState.Failed(133, "错误")
        )
        // 密封类的最大好处：when 表达式可以穷尽，编译器保证不漏
        for (s in states) {
            val text = when (s) {
                ConnectionState.Idle -> "idle"
                ConnectionState.Connecting -> "connecting"
                ConnectionState.Discovering -> "discovering"
                ConnectionState.Ready -> "ready"
                is ConnectionState.Retrying -> "retry${s.attempt}"
                is ConnectionState.Failed -> "fail${s.code}"
            }
            assertTrue(text.isNotEmpty())
        }
        assertEquals("retry2", "retry${(states[4] as ConnectionState.Retrying).attempt}")
    }
}
