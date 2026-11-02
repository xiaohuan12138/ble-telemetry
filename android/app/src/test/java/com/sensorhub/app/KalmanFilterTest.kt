package com.sensorhub.app

import com.sensorhub.app.domain.KalmanFilter
import com.sensorhub.app.domain.MovingAverageFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 滤波器单元测试
 *
 * 测试点选的是"能证明确实有效"的性质，而不是简单跑一遍：
 *   ① 恒定输入下，输出应收敛到该值
 *   ② 噪声输入下，卡尔曼的抖动应显著小于原始噪声
 *   ③ 阶跃输入下，卡尔曼的响应应快于同窗口的滑动平均
 */
class KalmanFilterTest {

    @Test
    fun `恒定输入应收敛`() {
        val kf = KalmanFilter()
        repeat(50) { kf.filter(25.0f) }
        assertEquals(25.0f, kf.filter(25.0f), 0.01f)
    }

    @Test
    fun `第一次输入应原样返回`() {
        val kf = KalmanFilter()
        // 首帧没有历史可参照，直接采信
        assertEquals(30.0f, kf.filter(30.0f), 0.0001f)
    }

    @Test
    fun `噪声应被显著抑制`() {
        val kf = KalmanFilter()
        val noisy = floatArrayOf(
            25.3f, 24.7f, 25.5f, 24.6f, 25.4f, 24.8f,
            25.2f, 24.9f, 25.6f, 24.5f, 25.1f, 25.0f
        )

        // 原始数据的标准差
        val rawStd = stdDev(noisy)

        val filtered = FloatArray(noisy.size) { kf.filter(noisy[it]) }
        // 跳过前 3 个（滤波器还没收敛），看稳态抖动
        val steady = filtered.copyOfRange(3, filtered.size)
        val filteredStd = stdDev(steady)

        assertTrue(
            "滤波后抖动应明显小于原始抖动：raw=$rawStd filtered=$filteredStd",
            filteredStd < rawStd * 0.7
        )
    }

    @Test
    fun `阶跃响应应快于滑动平均`() {
        // 前 5 个周期稳定在 20℃，然后阶跃到 30℃
        val kf = KalmanFilter()
        val ma = MovingAverageFilter(5)

        repeat(5) { kf.filter(20f); ma.filter(20f) }

        var kfSteps = 0
        var maSteps = 0
        // 数一数各自需要几步才追到 29.5℃（即跟上 95%）
        for (i in 1..20) {
            if (kfSteps == 0 && kf.filter(30f) >= 29.5f) kfSteps = i
            if (maSteps == 0 && ma.filter(30f) >= 29.5f) maSteps = i
        }

        assertTrue("卡尔曼($kfSteps 步) 应不慢于滑动平均($maSteps 步)", kfSteps <= maSteps)
    }

    @Test
    fun `增益应随收敛而下降`() {
        val kf = KalmanFilter()
        kf.filter(25f)
        val earlyGain = kf.lastGain

        repeat(30) { kf.filter(25f) }
        val lateGain = kf.lastGain

        assertTrue("收敛后增益应变小：$earlyGain -> $lateGain", lateGain < earlyGain)
    }

    @Test
    fun `滑动平均结果应等于算术平均`() {
        val ma = MovingAverageFilter(3)
        ma.filter(10f); ma.filter(20f)
        val result = ma.filter(30f)
        assertEquals("(10+20+30)/3 = 20", 20f, result, 0.001f)
    }

    @Test
    fun `reset 后应重新开始`() {
        val kf = KalmanFilter()
        repeat(20) { kf.filter(25f) }
        kf.reset()
        assertEquals(40f, kf.filter(40f), 0.0001f)
    }

    /** 计算标准差 */
    private fun stdDev(values: FloatArray): Float {
        val mean = values.average().toFloat()
        return kotlin.math.sqrt(values.map { (it - mean) * (it - mean) }.average()).toFloat()
    }
}
