package com.sensorhub.app.protocol

/**
 * 流式帧解析器
 *
 * 为什么需要它？
 *   BLE 的 Notify 一次最多传 MTU-3 字节（默认 MTU=23 时只有 20 字节），
 *   而且手机回调给过来的数据边界和"协议帧边界"毫无关系——可能一次来半帧，
 *   也可能一次来两帧半。所以必须有一个"蓄水池"，把字节流攒起来，
 *   凑够一整帧再吐出去。
 *
 * 状态机（逐字节推进）：
 *   WAIT_SOF0 → WAIT_SOF1 → 收 VER/TYPE/SEQ/LEN → 收 PAYLOAD → 收 CRC → 校验
 *   任何一步失败：滑动窗口丢弃最老的 1 个字节，重新找帧头。
 *
 * 为什么出错时只丢 1 字节而不是清空？
 *   假设流里是 ...AA 55 01 01 05 0C ... 但 CRC 错了，
 *   有可能是中间夹了一个噪声字节。清空整个缓冲区会连带丢掉后面
 *   已经收到的好数据；只丢 1 字节能保证不漏掉任何潜在的帧头。
 *   这是"滑动窗口"思想，成本只是多几次循环。
 */
class FrameParser(private val capacity: Int = 256) {

    private val buffer = ByteArray(capacity)
    private var size = 0

    /** 统计信息：答辩时可以用来说明"我确实做了可靠性观测" */
    var framesOk = 0
        private set
    var framesBadCrc = 0
        private set
    var framesDropped = 0
        private set

    /** 重置内部缓冲（例如重新连接时） */
    fun reset() {
        size = 0
    }

    /**
     * 喂入一段新收到的字节流
     *
     * @param data 本次收到的字节
     * @param length 有效长度（默认整个数组）
     * @return 本次解析出来的所有完整帧（可能 0 个或多个）
     */
    fun feed(data: ByteArray, length: Int = data.size): List<Frame> {
        val out = ArrayList<Frame>(2)

        for (i in 0 until length) {
            val b = data[i]

            when (size) {
                0 -> {
                    // 等帧头第 1 字节 0xAA
                    if (b == FrameSpec.SOF_0) buffer[size++] = b
                    // 不是 0xAA：静默丢弃（这些是帧间隙的垃圾字节）
                }
                1 -> {
                    // 等帧头第 2 字节 0x55
                    if (b == FrameSpec.SOF_1) {
                        buffer[size++] = b
                    } else if (b == FrameSpec.SOF_0) {
                        // 出现了 AA AA：后面那个 AA 可能是真正的帧头起点
                        buffer[0] = b   // size 保持 1
                    } else {
                        size = 0
                    }
                }
                else -> {
                    if (size >= capacity) {
                        // 缓冲区满却还没凑齐一帧 → 说明收到的是持续垃圾
                        reset()
                        framesDropped++
                        continue
                    }
                    buffer[size++] = b

                    if (size < FrameSpec.HEADER_SIZE) continue   // 头还没齐

                    val payloadLen = buffer[5].toInt() and 0xFF
                    if (payloadLen > FrameSpec.MAX_PAYLOAD) {
                        // 长度字段非法（噪声导致）→ 丢 1 字节重来
                        dropOne()
                        framesDropped++
                        continue
                    }

                    val need = FrameSpec.HEADER_SIZE + payloadLen + FrameSpec.CRC_SIZE
                    if (size < need) continue                    // 还没收满，继续攒

                    // 收满一帧，校验
                    val frameBytes = buffer.copyOfRange(0, need)
                    if (Crc16.verifyFrame(frameBytes)) {
                        out.add(
                            Frame(
                                type = frameBytes[3].toInt() and 0xFF,
                                seq = frameBytes[4].toInt() and 0xFF,
                                payload = frameBytes.copyOfRange(
                                    FrameSpec.HEADER_SIZE,
                                    FrameSpec.HEADER_SIZE + payloadLen
                                )
                            )
                        )
                        framesOk++
                        size = 0                                  // 一帧消费完，清空
                    } else {
                        dropOne()
                        framesBadCrc++
                    }
                }
            }
        }
        return out
    }

    /** 丢弃缓冲区最老的 1 字节，其余前移 */
    private fun dropOne() {
        if (size <= 0) return
        System.arraycopy(buffer, 1, buffer, 0, size - 1)
        size--
    }
}

/**
 * 丢包检测器
 *
 * SEQ 是 0~255 的循环序号，固件每发一帧 +1。
 * 接收端用 (当前 - 上次) 判断中间跳过了几个号：
 *   delta = 1  → 正常，没丢
 *   delta = 2  → 中间丢了 1 帧
 *   delta > 2  → 丢了 delta-1 帧
 *   delta ≤ 0  → 说明序号回绕了（255→0），按 mod 256 修正
 */
class SequenceTracker {
    private var lastSeq: Int? = null

    var totalReceived = 0
        private set
    var totalLost = 0
        private set

    /** @return 本次是否发生了丢包（true = 丢了至少一帧） */
    fun onFrame(seq: Int): Boolean {
        val prev = lastSeq
        lastSeq = seq
        totalReceived++
        if (prev == null) return false          // 第一帧，无从比较

        // mod 256 差值：处理 255 → 0 的回绕
        val delta = (seq - prev + 256) % 256
        return if (delta > 1) {
            totalLost += (delta - 1)
            true
        } else {
            false
        }
    }

    fun reset() {
        lastSeq = null
        totalReceived = 0
        totalLost = 0
    }

    /** 丢包率：0.0 ~ 1.0 */
    val lossRate: Float
        get() {
            val total = totalReceived + totalLost
            return if (total == 0) 0f else totalLost.toFloat() / total
        }
}
