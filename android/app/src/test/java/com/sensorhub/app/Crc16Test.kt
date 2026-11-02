package com.sensorhub.app

import com.sensorhub.app.protocol.Crc16
import com.sensorhub.app.protocol.FrameSpec
import com.sensorhub.app.protocol.buildCommandFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CRC16 单元测试
 *
 * 为什么必须写这个测试？
 *   CRC 是"看不见 correctness"的典型：算错一位，平时完全正常，
 *   只有真遇到干扰时才漏检。而那时候你已经没法复现了。
 *   所以必须用公开的测试向量钉死它。
 */
class Crc16Test {

    /**
     * 标准测试向量：CRC-16/CCITT-FALSE("123456789") == 0x29B1
     * 这是 CRC 算法目录（CRC Catalogue）里公认的验证值，
     * 换任何语言实现都应该得到同样的结果。
     */
    @Test
    fun `标准向量 123456789 应等于 0x29B1`() {
        val input = "123456789".toByteArray()
        val crc = Crc16.ccittFalse(input)
        assertEquals(
            "多项式 0x1021 / 初值 0xFFFF / 无反射 的 CRC-16 应为 0x29B1",
            0x29B1, crc
        )
    }

    @Test
    fun `空数据应等于初值 0xFFFF`() {
        assertEquals(0xFFFF, Crc16.ccittFalse(ByteArray(0)))
    }

    @Test
    fun `offset 与 length 参数生效`() {
        val data = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        // 只算中间两个字节，应等于单独算 {0x03,0x04} 的结果
        val part = Crc16.ccittFalse(data, offset = 1, length = 2)
        val whole = Crc16.ccittFalse(byteArrayOf(0x02, 0x03))
        assertEquals(whole, part)
    }

    @Test
    fun `单比特翻转应被检出`() {
        val frame = buildCommandFrame(FrameSpec.CMD_SET_LED, byteArrayOf(0x80.toByte()), 0)
        assertTrue("原始帧必须通过校验", Crc16.verifyFrame(frame))

        // 翻转载荷里的一比特
        val broken = frame.copyOf()
        broken[6] = (broken[6].toInt() xor 0x01).toByte()
        org.junit.Assert.assertFalse("翻转一比特后必须被检出", Crc16.verifyFrame(broken))
    }

    @Test
    fun `帧尾被截断应判为非法`() {
        val frame = buildCommandFrame(FrameSpec.CMD_SET_PERIOD, byteArrayOf(0x03, 0xE8.toByte()), 1)
        val truncated = frame.copyOf(frame.size - 1)
        org.junit.Assert.assertFalse(Crc16.verifyFrame(truncated))
    }

    @Test
    fun `大端写入顺序正确`() {
        val buf = ByteArray(2)
        Crc16.writeBigEndian(buf, 0, 0x29B1)
        assertEquals(0x29.toByte(), buf[0])   // 高字节在前
        assertEquals(0xB1.toByte(), buf[1])
    }
}
