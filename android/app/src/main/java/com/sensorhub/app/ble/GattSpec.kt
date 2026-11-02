package com.sensorhub.app.ble

import java.util.UUID

/**
 * GATT 服务与特征的 UUID 定义
 *
 * 必须与 ESP32 固件 include/config.h 里的字符串完全一致（小写也要一致，
 * UUID 字符串比较是大小写敏感的，曾经因为大小写不一致坑了半小时）。
 *
 * UUID 的两种形式：
 *   16 位（如 0x180A）：蓝牙技术联盟 SIG 分配给标准服务的"短号"，
 *                      系统会自动补全成 0000180A-0000-1000-8000-00805F9B34FB
 *   128 位（本项目自定义的 FF10/FF20）：完全私有，全球唯一，不会撞车
 */
object GattSpec {

    // ================= 自定义传感器服务 =================
    val SERVICE_SENSOR: UUID = UUID.fromString("0000ff10-0000-1000-8000-00805f9b34fb")
    val CHAR_DATA: UUID = UUID.fromString("0000ff11-0000-1000-8000-00805f9b34fb")
    val CHAR_PERIOD: UUID = UUID.fromString("0000ff12-0000-1000-8000-00805f9b34fb")

    // ================= 自定义控制服务 =================
    val SERVICE_CTRL: UUID = UUID.fromString("0000ff20-0000-1000-8000-00805f9b34fb")
    val CHAR_CTRL: UUID = UUID.fromString("0000ff21-0000-1000-8000-00805f9b34fb")

    // ================= 标准服务 =================
    val SERVICE_DEVICE_INFO: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val SERVICE_BATTERY: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")

    val CHAR_MANUFACTURER: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
    val CHAR_MODEL: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    val CHAR_FW_VERSION: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    val CHAR_BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    /**
     * CCCD（Client Characteristic Configuration Descriptor）
     *
     * 这是 BLE 里最容易忘的一步：
     *   光调用 setCharacteristicNotification(true) 只告诉了"手机本地协议栈"
     *   我要收通知，并没有告诉"对端设备"开始发。
     *   必须再往这个 descriptor 写入 ENABLE_NOTIFICATION_VALUE，
     *   ESP32 端才会收到订阅请求并开始推送。
     * 忘了这一步的典型症状：连接成功、服务发现成功，但永远收不到数据。
     */
    val DESCRIPTOR_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/** 扫描到的设备信息 */
data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val isConnectable: Boolean = true
) {
    /** 信号强度分级，用于列表页的信号格数 */
    val signalLevel: Int
        get() = when {
            rssi >= -55 -> 3      // 很近
            rssi >= -70 -> 2      // 正常
            rssi >= -85 -> 1      // 较弱
            else -> 0             // 勉强能连
        }
}
