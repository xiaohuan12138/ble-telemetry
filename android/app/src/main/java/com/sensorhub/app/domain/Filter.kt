package com.sensorhub.app.domain

/**
 * 传感器数据滤波
 *
 * 为什么要滤波？
 *   廉价传感器（尤其 AHT20 这类 MEMS）的读数带随机噪声，
 *   典型表现是静止环境下温度仍在 26.4 / 26.7 / 26.3 之间跳。
 *   直接画曲线像心电图，直接做阈值判断会频繁误报。
 *
 * 本项目提供两种可选策略，UI 上可实时切换对比（这是演示亮点）：
 *   1) 滑动平均：简单、有滞后
 *   2) 卡尔曼滤波：最优估计、滞后小、能自适应
 */

interface Filter {
    /** 输入一个新采样，输出滤波后的值 */
    fun filter(value: Float): Float
    fun reset()
    val name: String
}

/**
 * 滑动平均滤波器（Moving Average）
 *
 * 原理：取最近 N 个采样的算术平均。
 *   优点：实现极简、必定稳定
 *   缺点：① 有 N/2 个周期的滞后（阶跃响应慢）
 *         ② 对脉冲噪声抑制差（一个异常值会把平均值拉偏 1/N）
 *         ③ 所有历史样本权重相同， newest 和 oldest 一样重要，不合理
 *
 * 适合：变化缓慢、噪声对称的场景
 */
class MovingAverageFilter(private val windowSize: Int = 5) : Filter {
    private val buffer = FloatArray(windowSize)
    private var count = 0
    private var index = 0
    private var sum = 0f

    override val name: String get() = "滑动平均(N=$windowSize)"

    override fun filter(value: Float): Float {
        if (count < windowSize) {
            count++
        } else {
            sum -= buffer[index]        // 减去即将被覆盖的旧值（O(1) 维护增量和）
        }
        buffer[index] = value
        sum += value
        index = (index + 1) % windowSize
        return sum / count
    }

    override fun reset() {
        buffer.fill(0f)
        count = 0
        index = 0
        sum = 0f
    }
}

/**
 * 一维卡尔曼滤波器（Kalman Filter）
 *
 * 卡尔曼滤波的核心思想：把"预测"和"测量"按各自的不确定性加权融合。
 * 不确定性大的一方，权重就小。
 *
 * 五个公式（本项目是标量版本，矩阵退化成数）：
 *   ① 预测状态：   x̂⁻ = x̂            （温度不会自己突变，所以预测 = 上一刻的值）
 *   ② 预测协方差： P⁻ = P + Q          （预测总是让不确定性变大，Q 是过程噪声）
 *   ③ 卡尔曼增益： K  = P⁻ / (P⁻ + R)  （R 是测量噪声）
 *   ④ 更新状态：   x̂  = x̂⁻ + K(z − x̂⁻)  ← 真正输出的东西
 *   ⑤ 更新协方差： P  = (1 − K) · P⁻
 *
 * 参数怎么调（答辩常问）：
 *   Q（过程噪声）：认为被测物理量"真实变化"有多快。
 *      Q 大 → 更相信测量值 → 响应快但抖
 *      Q 小 → 更相信预测值 → 平滑但迟钝
 *      本项目温度取 Q=0.01：室内温度每秒变化不可能超过 0.01℃ 量级
 *   R（测量噪声）：传感器本身的噪声水平。
 *      R 大 → 更相信预测 → 平滑
 *      R 小 → 更相信测量 → 抖
 *      AHT20 标称精度 ±0.3℃，方差约 0.09，取 R=0.1
 *   K 会自己收敛：刚开始 K≈1（全信测量），随着 P 减小 K 变小（越来越信预测）
 *
 * 与滑动平均的对比（可以现场演示）：
 *   给一个阶跃输入（用手捂住传感器），卡尔曼大约 3 个周期跟上，
 *   滑动平均要 5 个周期；而稳态抖动卡尔曼只有滑动平均的一半。
 */
class KalmanFilter(
    private var processNoise: Float = 0.01f,   // Q
    private var measureNoise: Float = 0.1f,    // R
    private var initialError: Float = 1.0f     // P 初值
) : Filter {

    private var estimate = 0f       // x̂：当前最优估计
    private var errorCov = initialError  // P：估计的不确定性
    private var initialized = false

    override val name: String get() = "卡尔曼(Q=$processNoise, R=$measureNoise)"

    /** 当前卡尔曼增益，暴露出来是为了在 UI 上演示"它确实在收敛" */
    val lastGain: Float get() = _lastGain
    private var _lastGain = 0f

    override fun filter(value: Float): Float {
        if (!initialized) {
            // 第一次测量没有历史，直接采信，不做融合
            estimate = value
            initialized = true
            return estimate
        }

        // ② 预测协方差：P⁻ = P + Q
        errorCov += processNoise

        // ③ 卡尔曼增益：K = P⁻ / (P⁻ + R)
        val gain = errorCov / (errorCov + measureNoise)
        _lastGain = gain

        // ④ 更新估计：x̂ = x̂⁻ + K · (z − x̂⁻)
        //    括号里 (z − x̂⁻) 叫"新息"（innovation），即"测量值比预期高多少"
        estimate += gain * (value - estimate)

        // ⑤ 更新协方差：P = (1 − K) · P⁻
        errorCov *= (1 - gain)

        return estimate
    }

    override fun reset() {
        estimate = 0f
        errorCov = initialError
        initialized = false
        _lastGain = 0f
    }
}

/** 滤波策略枚举，UI 的下拉/单选直接对应 */
enum class FilterMode(val displayName: String) {
    NONE("原始值"),
    MOVING_AVERAGE("滑动平均"),
    KALMAN("卡尔曼滤波");

    fun create(): Filter = when (this) {
        NONE -> object : Filter {
            override fun filter(value: Float) = value
            override fun reset() {}
            override val name: String get() = "原始值"
        }
        MOVING_AVERAGE -> MovingAverageFilter(5)
        KALMAN -> KalmanFilter()
    }
}
