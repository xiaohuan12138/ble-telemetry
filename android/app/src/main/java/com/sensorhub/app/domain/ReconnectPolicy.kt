package com.sensorhub.app.domain

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * 指数退避重连策略（Exponential Backoff with Jitter）
 *
 * 问题背景：BLE 连接会因为走出房间、设备重启、手机蓝牙被系统回收等原因断开。
 * 最朴素的写法是"断了立刻重连，失败再重连"，这有两个致命问题：
 *
 *   ① 惊群效应（Thundering Herd）
 *      如果同时管理多个设备（本项目管理 2 块 ESP32），它们会在同一时刻
 *      同时重试，形成周期性的流量尖峰，互相干扰，反而更难连上。
 *
 *   ② 无效空转
 *      设备确实不在附近时，每 100ms 重试一次 = 每秒 10 次无效的
 *      扫描+连接，手机电量直线下降，用户感知是"手机发烫"。
 *
 * 解决方案：第 n 次重试的等待时间 = min(上限, 基础 × 2^n) + 随机抖动
 *
 *   第 1 次：1s
 *   第 2 次：2s
 *   第 3 次：4s
 *   第 4 次：8s
 *   第 5 次：16s
 *   第 6 次起：30s（封顶）
 *
 * 为什么要加随机抖动（jitter）？
 *   两个设备同时断开，前 5 次的退避时间完全一样 → 依然会在同一秒重试。
 *   给等待时间加 0~30% 的随机量，就能把它们错开。
 *   这是分布式系统里非常经典的一个技巧（AWS 官方架构博客专门讲过）。
 *
 * @param baseDelayMs   第一次重试前的等待
 * @param maxDelayMs    等待时间上限，防止无限增长
 * @param multiplier    退避倍数，2.0 = 每次翻倍
 * @param jitterRatio   抖动比例，0.3 = 在 [0, 30%] 范围内随机加量
 * @param maxAttempts   最大重试次数，超过则放弃（null = 无限重试）
 */
class ReconnectPolicy(
    private val baseDelayMs: Long = 1_000L,
    private val maxDelayMs: Long = 30_000L,
    private val multiplier: Double = 2.0,
    private val jitterRatio: Double = 0.3,
    private val maxAttempts: Int? = 10
) {
    private var attempt = 0
    // kotlin.random.Random 是抽象类，Random(seed) 是 stdlib 提供的顶层工厂函数
    private val random: Random = Random(System.currentTimeMillis())

    /** 当前是第几次重试（从 1 开始） */
    val currentAttempt: Int get() = attempt

    /** 是否已经放弃（超过最大重试次数） */
    val exhausted: Boolean
        get() = maxAttempts != null && attempt >= maxAttempts

    /**
     * 计算下一次重试应该等待多久
     *
     * @return 等待毫秒数；如果已放弃返回 -1
     */
    fun nextDelay(): Long {
        if (exhausted) return -1L

        // 指数部分：base × multiplier^attempt
        val exponential = baseDelayMs * multiplier.pow(attempt.toDouble())

        // 封顶：避免第 10 次变成 17 分钟
        val capped = min(exponential.toDouble(), maxDelayMs.toDouble())

        // 抖动：capped × (1 + rand(0, jitterRatio))
        val jitter = 1.0 + random.nextDouble() * jitterRatio
        val withJitter = (capped * jitter).toLong()

        attempt++
        return min(withJitter, maxDelayMs)
    }

    /** 连接成功，重置计数（下次断开从 1s 重新开始，而不是延续上次的 30s） */
    fun reset() {
        attempt = 0
    }

    /** 便于日志/UI 展示 */
    override fun toString(): String {
        return "ReconnectPolicy(attempt=$attempt, base=${baseDelayMs}ms, max=${maxDelayMs}ms)"
    }
}

/**
 * 连接状态机
 *
 * BLE 连接不是"连上/没连"两个状态，中间还有发现服务、订阅等步骤。
 * 把状态显式建模出来，UI 才能显示"正在发现服务…"而不是干等。
 * 这是本项目状态管理的骨架，所有 UI 都从这个状态派生。
 */
sealed interface ConnectionState {
    /** 未连接（初始态 / 已主动断开） */
    data object Idle : ConnectionState

    /** 正在建立 GATT 连接 */
    data object Connecting : ConnectionState

    /** 已连上，正在发现服务（discoverServices） */
    data object Discovering : ConnectionState

    /** 就绪：已订阅 Notify，数据开始流入 */
    data object Ready : ConnectionState

    /** 断开并等待重连 */
    data class Retrying(val attempt: Int, val delayMs: Long) : ConnectionState

    /** 失败：携带错误码，UI 会翻译成人话 */
    data class Failed(val code: Int, val message: String) : ConnectionState
}

/**
 * BLE 错误码 → 可读文案
 *
 * Android 的 onConnectionStateChange 回调给的 status 是一串神秘数字，
 * 直接弹 "Error 133" 用户完全看不懂。这个映射表是踩坑攒出来的。
 */
fun describeBleError(code: Int): String = when (code) {
    133 -> "GATT 内部错误(133)：通常是上次的连接没释放干净，请重启手机蓝牙"
    8 -> "连接超时(8)：设备可能已断电或超出信号范围"
    22 -> "设备主动断开(22)：下位机重启或信号不稳定"
    19 -> "对端主动终止(19)"
    257 -> "GATT 连接冲突(257)：请等上一次连接完全结束再试"
    5 -> "GATT 权限不足(5)：检查 BLUETOOTH_CONNECT 权限是否已授予"
    6 -> "不支持的 GATT 请求(6)"
    0 -> "正常"
    else -> "连接错误($code)"
}
