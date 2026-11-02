package com.sensorhub.app.protocol

/**
 * CRC-16/CCITT-FALSE 校验算法
 *
 * 参数（这三个参数决定了一个 CRC 变体，答辩必背）：
 *   多项式 Polynomial = 0x1021
 *   初值   Init       = 0xFFFF
 *   输入反射 false、输出反射 false、输出异或 0x0000
 *
 * 为什么需要 CRC？
 *   BLE 工作在 2.4GHz ISM 公共频段，Wi-Fi、微波炉、无线鼠标都在这个频段。
 *   空中传输偶发位翻转是客观存在的。如果不校验，一次翻转就可能把 26.5℃
 *   变成 154.1℃，用户看到完全错误的读数。CRC16 能检出：
 *     - 所有单比特错误
 *     - 所有双比特错误（≤16 位间隔内）
 *     - 所有奇数个比特错误
 *     - 99.998% 的突发错误（≤16 位）
 *
 * 注意：CRC 只能"检错"不能"纠错"。本项目的策略是：检出错误 → 丢弃该帧 →
 *       丢包计数 +1 → UI 显示丢包率。这比"显示一个错的值"要好得多。
 */
object Crc16 {

    private const val POLY = 0x1021
    private const val INIT = 0xFFFF

    /**
     * 计算 CRC。逐位实现，与 ESP32 端 C 代码逐行对应，方便交叉验证。
     *
     * @param data   数据
     * @param offset 起始下标
     * @param length 参与计算的长度
     * @return 16 位无符号结果（存在 Int 的低 16 位）
     */
    fun ccittFalse(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var crc = INIT
        for (i in offset until (offset + length)) {
            // Kotlin 的 Byte 是有符号的（−128~127），必须 and 0xFF 转成无符号
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            crc = crc and 0xFFFF
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor POLY) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc and 0xFFFF
    }

    /**
     * 校验一整帧：[VER][TYPE][SEQ][LEN][PAYLOAD...][CRC_HI][CRC_LO]
     * 校验范围 = VER 到 PAYLOAD 结束（不含 2 字节 SOF 和 2 字节 CRC 自身）
     *
     * @return true 表示数据完整未被篡改/干扰
     */
    fun verifyFrame(frame: ByteArray): Boolean {
        if (frame.size < 8) return false                    // 最小帧：6 头 + 0 载荷 + 2 CRC
        val payloadLen = frame[5].toInt() and 0xFF
        val expectedTotal = 6 + payloadLen + 2
        if (frame.size != expectedTotal) return false

        val bodyLen = 4 + payloadLen                        // VER+TYPE+SEQ+LEN+PAYLOAD
        val calculated = ccittFalse(frame, offset = 2, length = bodyLen)

        // 帧尾两个字节是大端（高字节在前），这是网络字节序的约定
        val received = ((frame[expectedTotal - 2].toInt() and 0xFF) shl 8) or
                (frame[expectedTotal - 1].toInt() and 0xFF)

        return calculated == received
    }

    /** 把 CRC 以大端方式写入字节数组 */
    fun writeBigEndian(buffer: ByteArray, offset: Int, crc: Int) {
        buffer[offset] = ((crc shr 8) and 0xFF).toByte()
        buffer[offset + 1] = (crc and 0xFF).toByte()
    }
}
