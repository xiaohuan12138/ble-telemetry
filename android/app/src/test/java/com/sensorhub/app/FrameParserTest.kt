package com.sensorhub.app

import com.sensorhub.app.protocol.Frame
import com.sensorhub.app.protocol.FrameParser
import com.sensorhub.app.protocol.FrameSpec
import com.sensorhub.app.protocol.SequenceTracker
import com.sensorhub.app.protocol.buildCommandFrame
import com.sensorhub.app.protocol.parseSensorPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧解析器单元测试
 *
 * 这里覆盖的是"流式"场景：蓝牙把一个帧拆成好几段送过来。
 * 这些用例直接对应真实世界中会遇到的情况，不是为测试而测试。
 */
class FrameParserTest {

    private fun sensorFrame(seq: Int = 0): ByteArray {
        val payload = ByteArray(FrameSpec.SENSOR_PAYLOAD_LEN)
        // 温度 26.5℃ → 265
        payload[0] = 0x01; payload[1] = 0x09.toByte()
        // 湿度 50.0% → 500
        payload[2] = 0x01; payload[3] = 0xF4.toByte()
        // 气压 1013.0hPa → 10130
        payload[4] = 0x27; payload[5] = 0x92.toByte()
        // 光照 320 lux
        payload[6] = 0x01; payload[7] = 0x40
        // 第二温度 26.7℃ → 267
        payload[8] = 0x01; payload[9] = 0x0B
        // flags + 电量
        payload[10] = 0x07
        payload[11] = 100.toByte()
        return buildCommandFrame(FrameSpec.TYPE_SENSOR_DATA, payload, seq)
    }

    @Test
    fun `整帧一次性到达应解析成功`() {
        val parser = FrameParser()
        val frames = parser.feed(sensorFrame(7))
        assertEquals(1, frames.size)
        assertEquals(FrameSpec.TYPE_SENSOR_DATA, frames[0].type)
        assertEquals(7, frames[0].seq)
    }

    @Test
    fun `分多次到达应拼出完整帧`() {
        val parser = FrameParser()
        val frame = sensorFrame(1)

        // 前 5 个字节：还不够一帧
        assertTrue(parser.feed(frame.copyOfRange(0, 5)).isEmpty())
        // 再来 5 个
        assertTrue(parser.feed(frame.copyOfRange(5, 10)).isEmpty())
        // 剩下的补齐
        val result = parser.feed(frame.copyOfRange(10, frame.size))
        assertEquals("三次喂入后应该恰好解析出一帧", 1, result.size)
    }

    @Test
    fun `一次到达两帧应解析出两帧`() {
        val parser = FrameParser()
        val two = sensorFrame(1) + sensorFrame(2)
        val frames = parser.feed(two)
        assertEquals(2, frames.size)
        assertEquals(1, frames[0].seq)
        assertEquals(2, frames[1].seq)
    }

    @Test
    fun `帧前有垃圾数据应能自动对齐`() {
        val parser = FrameParser()
        val garbage = byteArrayOf(0xFF.toByte(), 0x00, 0x13, 0xAA.toByte())
        val frames = parser.feed(garbage + sensorFrame(3))
        assertEquals("垃圾前缀不应影响后续解析", 1, frames.size)
        assertEquals(3, frames[0].seq)
    }

    @Test
    fun `CRC 错误的帧应被丢弃且能恢复`() {
        val parser = FrameParser()
        val broken = sensorFrame(4)
        broken[broken.size - 1] = (broken[broken.size - 1].toInt() xor 0xFF).toByte()

        // 坏帧后面跟一个好帧：应丢弃坏帧、解出好帧
        val frames = parser.feed(broken + sensorFrame(5))
        assertEquals(1, frames.size)
        assertEquals(5, frames[0].seq)
        assertTrue("应记录到 CRC 错误", parser.framesBadCrc >= 1)
    }

    @Test
    fun `载荷解析应正确还原物理量`() {
        val parser = FrameParser()
        val frames = parser.feed(sensorFrame(0))
        val data = parseSensorPayload(frames[0].payload)
        org.junit.Assert.assertNotNull(data)
        assertEquals(26.5f, data!!.tempAht, 0.01f)
        assertEquals(50.0f, data.hum, 0.01f)
        assertEquals(1013.0f, data.press, 0.01f)
        assertEquals(320, data.lux)
        assertEquals(26.7f, data.tempBmp, 0.01f)
        assertTrue("三个传感器都应标记为在岗", data.ahtOk && data.bmpOk && data.bhOk)
        assertTrue("温差 0.2℃ 应判定一致", data.tempConsistent)
    }

    @Test
    fun `SEQ 连续时不应报丢包`() {
        val tracker = SequenceTracker()
        tracker.onFrame(10)
        tracker.onFrame(11)
        tracker.onFrame(12)
        assertEquals(0, tracker.totalLost)
        assertEquals(0f, tracker.lossRate)
    }

    @Test
    fun `SEQ 跳号应检出丢包`() {
        val tracker = SequenceTracker()
        tracker.onFrame(10)
        tracker.onFrame(11)
        tracker.onFrame(13)      // 12 丢了
        assertEquals(1, tracker.totalLost)
    }

    @Test
    fun `SEQ 从 255 回绕到 0 不应误报`() {
        val tracker = SequenceTracker()
        tracker.onFrame(255)
        tracker.onFrame(0)
        assertEquals("回绕是正常现象，不能算丢包", 0, tracker.totalLost)
    }

    @Test
    fun `海拔换算在合理范围`() {
        val parser = FrameParser()
        val frames = parser.feed(sensorFrame(0))
        val data = parseSensorPayload(frames[0].payload)!!
        // 1013.25hPa 对应海拔 0 米左右；1013hPa 应接近 2 米
        assertTrue(data.altitude > -10 && data.altitude < 20)
    }
}
