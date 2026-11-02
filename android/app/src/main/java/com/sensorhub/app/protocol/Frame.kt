package com.sensorhub.app.protocol

/**
 * 协议帧的常量定义与数据模型
 *
 * 帧格式（与 ESP32 固件 include/config.h 严格一致，改一端必须同步改另一端）：
 *   ┌──────┬──────┬──────┬──────┬──────┬─────────┬───────┐
 *   │ SOF  │ VER  │ TYPE │ SEQ  │ LEN  │ PAYLOAD │ CRC16 │
 *   │ 2B   │ 1B   │ 1B   │ 1B   │ 1B   │ NB      │ 2B    │
 *   └──────┴──────┴──────┴──────┴──────┴─────────┴───────┘
 *   0xAA55   0x01
 */
object FrameSpec {
    const val SOF_0 = 0xAA.toByte()
    const val SOF_1 = 0x55.toByte()
    const val VERSION = 0x01.toByte()

    const val HEADER_SIZE = 6      // SOF(2) + VER + TYPE + SEQ + LEN
    const val CRC_SIZE = 2
    const val MAX_PAYLOAD = 60
    const val MAX_FRAME = HEADER_SIZE + MAX_PAYLOAD + CRC_SIZE

    // ---- 帧类型：下位机 → 手机 ----
    const val TYPE_SENSOR_DATA: Int = 0x01
    const val TYPE_HEARTBEAT: Int = 0x02
    const val TYPE_ALERT: Int = 0x03
    const val TYPE_ACK: Int = 0x41

    // ---- 帧类型：手机 → 下位机（命令）----
    const val CMD_SET_LED: Int = 0x10
    const val CMD_SET_BUZZER: Int = 0x11
    const val CMD_SET_PERIOD: Int = 0x12
    const val CMD_FORCE_REPORT: Int = 0x13

    // ---- ACK 状态码 ----
    const val ACK_OK = 0x00
    const val ACK_ERR_CRC = 0x01
    const val ACK_ERR_TYPE = 0x02
    const val ACK_ERR_LEN = 0x03
    const val ACK_ERR_PARAM = 0x04

    // ---- 传感器健康位 ----
    const val FLAG_AHT20_OK = 0x01
    const val FLAG_BMP280_OK = 0x02
    const val FLAG_BH1750_OK = 0x04

    // ---- 告警码 ----
    const val ALERT_TEMP_HIGH = 0x01
    const val ALERT_SENSOR_FAULT = 0x02

    /** 传感器数据帧的载荷固定 12 字节 */
    const val SENSOR_PAYLOAD_LEN = 12
}

/** 解析成功后得到的一帧 */
data class Frame(
    val type: Int,
    val seq: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return type == other.type && seq == other.seq && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + seq
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * 传感器读数（已还原为物理量的浮点值）
 *
 * @param tempAht  AHT20 温度 ℃
 * @param hum      相对湿度 %
 * @param press    气压 hPa
 * @param lux      光照 lux
 * @param tempBmp  BMP280 温度 ℃（第二温度源，用于交叉校验）
 * @param flags    传感器健康位
 * @param rssi     收到时的信号强度 dBm（本地附加，不由协议携带）
 * @param timestamp 本地接收时刻（毫秒）
 */
data class SensorData(
    val tempAht: Float,
    val hum: Float,
    val press: Float,
    val lux: Int,
    val tempBmp: Float,
    val flags: Int,
    val rssi: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
) {
    val ahtOk: Boolean get() = (flags and FrameSpec.FLAG_AHT20_OK) != 0
    val bmpOk: Boolean get() = (flags and FrameSpec.FLAG_BMP280_OK) != 0
    val bhOk: Boolean get() = (flags and FrameSpec.FLAG_BH1750_OK) != 0

    /**
     * 双温度源交叉校验：差值 ≤ 2℃ 认为正常。
     * 这是本项目的冗余设计亮点——用两颗芯片互相盯着对方，
     * 任何一颗漂移都能被发现，成本只多了一个本来就需要的气压传感器。
     */
    val tempConsistent: Boolean
        get() {
            if (!ahtOk || !bmpOk) return true
            return kotlin.math.abs(tempAht - tempBmp) <= 2.0f
        }

    /** 温度取值：优先 AHT20（精度 ±0.3℃），缺失时回退到 BMP280 */
    val temperature: Float get() = if (ahtOk) tempAht else tempBmp

    /**
     * 气压换算海拔（国际标准大气模型）
     * h = 44330 × [1 − (p/1013.25)^(1/5.255)]
     * 局限：天气变化会让同一地点读数漂移 ±30m，只能看相对变化
     */
    val altitude: Float
        get() {
            if (press <= 0f) return 0f
            // 用 java.lang.Math.pow（而不是 kotlin.math.pow）：
            // Kotlin 新版把 Float 版的 pow 移除了，改用 Double 计算最稳
            val ratio = (press / 1013.25f).toDouble()
            return (44330.0 * (1.0 - Math.pow(ratio, 1.0 / 5.255))).toFloat()
        }
}

/** 解析传感器载荷：整数 ÷10 还原浮点 */
fun parseSensorPayload(payload: ByteArray, rssi: Int = 0): SensorData? {
    if (payload.size < FrameSpec.SENSOR_PAYLOAD_LEN) return null

    fun u16(i: Int): Int = ((payload[i].toInt() and 0xFF) shl 8) or (payload[i + 1].toInt() and 0xFF)

    return SensorData(
        tempAht = u16(0) / 10f,
        hum = u16(2) / 10f,
        press = u16(4) / 10f,
        lux = u16(6),
        tempBmp = u16(8) / 10f,
        flags = payload[10].toInt() and 0xFF,
        rssi = rssi
    )
}

/**
 * 构造一条要发给 ESP32 的命令帧
 * 组帧逻辑与固件端 proto::buildFrame 完全对称
 */
fun buildCommandFrame(type: Int, payload: ByteArray, seq: Int): ByteArray {
    val len = payload.size
    val frame = ByteArray(FrameSpec.HEADER_SIZE + len + FrameSpec.CRC_SIZE)
    var p = 0
    frame[p++] = FrameSpec.SOF_0
    frame[p++] = FrameSpec.SOF_1
    frame[p++] = FrameSpec.VERSION
    frame[p++] = type.toByte()
    frame[p++] = seq.toByte()
    frame[p++] = len.toByte()
    payload.copyInto(frame, p)
    p += len
    val crc = Crc16.ccittFalse(frame, offset = 2, length = 4 + len)
    Crc16.writeBigEndian(frame, p, crc)
    return frame
}
