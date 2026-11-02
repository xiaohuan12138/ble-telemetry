package com.sensorhub.app.ble

import com.sensorhub.app.protocol.Crc16
import com.sensorhub.app.protocol.FrameSpec
import kotlin.math.sin
import kotlin.random.Random

/**
 * 模拟数据源（Mock Data Source）
 *
 * 作用：在没有 ESP32 硬件的情况下，让 App 的**全部功能**都能跑通并录像。
 *
 * 这不是"造假"，而是一个正式的降级方案，理由：
 *   1) 硬件采购、焊接、烧录可能出问题，不能让整个课程设计卡死在硬件上
 *   2) 演示"协议解析、CRC 校验、丢包检测、曲线绘制、规则告警"这些
 *      **软件**功能时，数据来源是真是假并不影响逻辑正确性
 *   3) 答辩时可以直接说："这是我在硬件到货前用来验证上层逻辑的 Mock 层，
 *      它走的是和真实设备完全相同的协议帧和解析代码路径。"
 *
 * 关键设计：Mock 生成的不是 SensorData 对象，而是**带 CRC 的二进制帧**，
 * 然后交给同一个 FrameParser 去解析。这样 Mock 模式也在持续验证协议层，
 * 而不像很多课程设计那样绕过协议直接塞对象——那等于没做协议。
 */
class MockDataSource(
    private val deviceName: String = "SensorHub-Mock",
    /** 模拟丢包的概率：0.0 ~ 1.0，用来演示丢包率统计 */
    private val lossProbability: Float = 0.0f
) {
    private var seq = 0
    private var tick = 0
    private val random = Random(System.currentTimeMillis())

    private var baseTemp = 25.0f
    private var baseHum = 50.0f
    private var basePress = 1013.0f

    /** 让曲线有意思一点：模拟一个缓慢上升又下降的过程 */
    private var driftPhase = 0.0

    /**
     * 生成一帧传感器数据
     * @return 完整的二进制帧（含 SOF、CRC），可以直接喂给 FrameParser
     */
    fun nextFrame(): ByteArray {
        tick++
        driftPhase += 0.05

        // 温度：基准 25℃ + 正弦波动 ±2℃ + ±0.15℃ 随机噪声
        val temp = baseTemp + (sin(driftPhase) * 2.0f) + (random.nextFloat() - 0.5f) * 0.3f
        // 湿度：基准 50% + 缓慢反向波动
        val hum = baseHum - (sin(driftPhase * 0.7) * 8.0f) + (random.nextFloat() - 0.5f) * 1.5f
        // 气压：基准 1013hPa + 微小波动
        val press = basePress + (sin(driftPhase * 0.3) * 3.0f)
        // 光照：0~800 lux 的一个周期变化，模拟有人在旁边走过遮光
        val lux = ((sin(driftPhase * 0.5) + 1.0) * 400).toInt().coerceIn(0, 65535)

        val payload = ByteArray(FrameSpec.SENSOR_PAYLOAD_LEN)
        fun putU16(offset: Int, v: Int) {
            payload[offset] = ((v shr 8) and 0xFF).toByte()
            payload[offset + 1] = (v and 0xFF).toByte()
        }
        putU16(0, (temp * 10).toInt())
        putU16(2, (hum * 10).toInt().coerceIn(0, 1000))
        putU16(4, (press * 10).toInt())
        putU16(6, lux)
        putU16(8, ((temp + 0.2f) * 10).toInt())   // 第二温度源故意差 0.2℃，模拟真实偏差
        payload[10] = (FrameSpec.FLAG_AHT20_OK or FrameSpec.FLAG_BMP280_OK or
                FrameSpec.FLAG_BH1750_OK).toByte()
        payload[11] = 100

        val currentSeq = seq
        seq = (seq + 1) and 0xFF

        // 模拟丢包：直接跳过某些 SEQ 不生成（解析器会检测到序号断层）
        if (lossProbability > 0f && random.nextFloat() < lossProbability) {
            seq = (seq + 1) and 0xFF     // 多跳一号 = 丢了一帧
        }

        return buildFrame(FrameSpec.TYPE_SENSOR_DATA, currentSeq, payload)
    }

    /** 生成心跳帧 */
    fun heartbeatFrame(uptimeSec: Int): ByteArray {
        val p = byteArrayOf(
            ((uptimeSec shr 24) and 0xFF).toByte(),
            ((uptimeSec shr 16) and 0xFF).toByte(),
            ((uptimeSec shr 8) and 0xFF).toByte(),
            (uptimeSec and 0xFF).toByte()
        )
        return buildFrame(FrameSpec.TYPE_HEARTBEAT, 0, p)
    }

    /** 组帧（与真实固件完全相同的逻辑） */
    private fun buildFrame(type: Int, seqValue: Int, payload: ByteArray): ByteArray {
        val len = payload.size
        val frame = ByteArray(FrameSpec.HEADER_SIZE + len + FrameSpec.CRC_SIZE)
        var p = 0
        frame[p++] = FrameSpec.SOF_0
        frame[p++] = FrameSpec.SOF_1
        frame[p++] = FrameSpec.VERSION
        frame[p++] = type.toByte()
        frame[p++] = seqValue.toByte()
        frame[p++] = len.toByte()
        payload.copyInto(frame, p)
        p += len
        val crc = Crc16.ccittFalse(frame, offset = 2, length = 4 + len)
        Crc16.writeBigEndian(frame, p, crc)
        return frame
    }
}
