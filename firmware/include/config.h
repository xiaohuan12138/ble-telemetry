// ============================================================================
// 传感中枢 SensorHub —— ESP32 下位机全局配置
// ----------------------------------------------------------------------------
// 这个文件集中存放"改一处就能改全局"的常量：引脚、UUID、周期、设备名。
// 答辩时被问到"为什么这么定义"，直接指向本文件即可。
// ============================================================================
#pragma once

#include <stdint.h>

namespace cfg {

// ----------------------------- 1. 设备身份 --------------------------------
// 两块板子烧同一份固件，靠这个名字区分（第二块改成 02）。
// BLE 广播名最长 31 字节，这里 11 字节，安全。
inline const char* DEVICE_NAME = "SensorHub-01";

// 固件版本号，写进 Device Information 服务，手机端可读
inline const char* FW_VERSION  = "1.0.0";
inline const char* MANUFACTURER = "SensorHubLab";
inline const char* MODEL_NUMBER = "SH-ESP32-A1";

// ----------------------------- 2. I2C 引脚 ---------------------------------
// ESP32 的硬件 I2C 可以映射到任意 GPIO，这里用最常见的默认组合。
// SDA = 数据线，SCL = 时钟线。三个传感器并联挂在同一组线上。
inline const uint8_t PIN_SDA = 21;
inline const uint8_t PIN_SCL = 22;

// I2C 设备地址（7 位）。靠地址区分"同一根线上挂着的三个设备"。
inline const uint8_t ADDR_AHT20  = 0x38;  // 温湿度
inline const uint8_t ADDR_BMP280 = 0x76;  // 气压（部分模块是 0x77，改这里）
inline const uint8_t ADDR_BH1750 = 0x23;  // 光照

// ----------------------------- 3. 执行器引脚 --------------------------------
inline const uint8_t PIN_LED    = 2;   // 板载 LED，同时外接 LED（PWM 调光）
inline const uint8_t PIN_BUZZER = 4;   // 有源蜂鸣器（高电平响）

// PWM 参数：5kHz 频率、8 位分辨率（0~255）
inline const uint16_t PWM_FREQ       = 5000;
inline const uint8_t  PWM_RESOLUTION = 8;
inline const uint8_t  PWM_CHANNEL    = 0;

// ----------------------------- 4. 上报周期 ---------------------------------
// 默认 1000ms 报一次。手机可以通过 PERIOD 特征改（200~10000ms）。
inline const uint16_t PERIOD_DEFAULT_MS = 1000;
inline const uint16_t PERIOD_MIN_MS     = 200;
inline const uint16_t PERIOD_MAX_MS     = 10000;

// 心跳帧间隔：即使传感器全挂了，也要证明"链路还活着"
inline const uint32_t HEARTBEAT_INTERVAL_MS = 5000;

// ----------------------------- 5. BLE GATT UUID -----------------------------
// 16 位 UUID 是蓝牙联盟分配给标准服务的（0x180A 设备信息、0x180F 电量）。
// 128 位 UUID 是我们自己随手定义的私有服务，全球唯一，不会和别人撞车。
// 命名规则：0000FFxy-0000-1000-8000-00805F9B34FB
//   FF1x = 传感器服务   FF2x = 控制服务
inline const char* UUID_SENSOR_SERVICE = "0000ff10-0000-1000-8000-00805f9b34fb";
inline const char* UUID_CHAR_DATA      = "0000ff11-0000-1000-8000-00805f9b34fb"; // Notify
inline const char* UUID_CHAR_PERIOD    = "0000ff12-0000-1000-8000-00805f9b34fb"; // Read+Write

inline const char* UUID_CTRL_SERVICE   = "0000ff20-0000-1000-8000-00805f9b34fb";
inline const char* UUID_CHAR_CTRL      = "0000ff21-0000-1000-8000-00805f9b34fb"; // Write+Read

// 标准特征（16 位）：手机 App 里可以直接读
inline const uint16_t UUID_CHAR_MANUFACTURER = 0x2A29;
inline const uint16_t UUID_CHAR_MODEL        = 0x2A24;
inline const uint16_t UUID_CHAR_FW_VERSION   = 0x2A26;
inline const uint16_t UUID_CHAR_BATTERY      = 0x2A19;

// ----------------------------- 6. 协议常量 ---------------------------------
inline const uint8_t  FRAME_SOF_0   = 0xAA;  // 帧头第 1 字节
inline const uint8_t  FRAME_SOF_1   = 0x55;  // 帧头第 2 字节
inline const uint8_t  FRAME_VERSION = 0x01;  // 协议版本
inline const uint8_t  FRAME_MAX_LEN = 60;    // 载荷上限（BLE 默认 MTU 23，留余量）
inline const uint16_t FRAME_MAX_SIZE = 2 + 1 + 1 + 1 + 1 + FRAME_MAX_LEN + 2; // 68

// 帧类型 TYPE
enum FrameType : uint8_t {
    TYPE_SENSOR_DATA = 0x01,  // 下位机 → 手机：传感器采样
    TYPE_HEARTBEAT   = 0x02,  // 下位机 → 手机：心跳
    TYPE_ALERT       = 0x03,  // 下位机 → 手机：本地告警
    TYPE_ACK         = 0x41,  // 下位机 → 手机：命令应答
    CMD_SET_LED      = 0x10,  // 手机 → 下位机：LED 亮度
    CMD_SET_BUZZER   = 0x11,  // 手机 → 下位机：蜂鸣器
    CMD_SET_PERIOD   = 0x12,  // 手机 → 下位机：上报周期
    CMD_FORCE_REPORT = 0x13,  // 手机 → 下位机：立即上报一次
};

// 应答状态码
enum AckStatus : uint8_t {
    ACK_OK             = 0x00,
    ACK_ERR_CRC        = 0x01,
    ACK_ERR_TYPE       = 0x02,
    ACK_ERR_LEN        = 0x03,
    ACK_ERR_PARAM      = 0x04,
};

// 传感器健康位（flags 字段，bit 位掩码）
enum SensorFlag : uint8_t {
    FLAG_AHT20_OK  = 0x01,  // bit0：温湿度在岗
    FLAG_BMP280_OK = 0x02,  // bit1：气压在岗
    FLAG_BH1750_OK = 0x04,  // bit2：光照在岗
};

// 告警码（TYPE_ALERT 的 payload[0]）
enum AlertCode : uint8_t {
    ALERT_TEMP_HIGH   = 0x01,  // 温度过高
    ALERT_SENSOR_FAULT = 0x02, // 双温度源偏差过大，判定某颗挂了
};

}  // namespace cfg
