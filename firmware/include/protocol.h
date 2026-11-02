// ============================================================================
// 自定义二进制协议层
// ----------------------------------------------------------------------------
// 帧格式（大端 / 网络字节序）：
//   ┌──────┬──────┬──────┬──────┬──────┬─────────┬───────┐
//   │ SOF  │ VER  │ TYPE │ SEQ  │ LEN  │ PAYLOAD │ CRC16 │
//   │ 2B   │ 1B   │ 1B   │ 1B   │ 1B   │ NB      │ 2B    │
//   └──────┴──────┴──────┴──────┴──────┴─────────┴───────┘
//   0xAA55   0x01
//
// 为什么要有帧头？蓝牙 Notify 是字节流，手机一次可能收到半帧或两帧，
// 必须靠 0xAA55 把"一帧的起点"标出来，否则解析全乱。
//
// 为什么要有 CRC16？2.4GHz 空口有干扰，位翻转不罕见。CRC 能检出 99.99%
// 以上的错误帧，收到坏帧直接丢弃，绝不把 78.3℃ 这种脏数据显示给用户。
//
// 为什么要有 SEQ？手机端用 (本次SEQ - 上次SEQ) mod 256 判断是否丢包。
// 丢包率本身是一个可展示的指标（答辩加分）。
// ============================================================================
#pragma once

#include <stdint.h>
#include <stddef.h>
#include "config.h"

namespace proto {

// CRC-16/CCITT-FALSE：多项式 0x1021，初值 0xFFFF，无输入反转、无输出异或
// 这是嵌入式最常用的一个变体（XMODEM、Modbus 都用它）。
uint16_t crc16(const uint8_t* data, size_t len);

// 组帧：把 type/payload 打包进 outBuf，返回整帧字节数；失败返回 0
// 注意：CRC 只覆盖 VER..PAYLOAD，不含 SOF——因为 SOF 已用于同步。
size_t buildFrame(uint8_t type, const uint8_t* payload, uint8_t len,
                  uint8_t* outBuf, size_t bufCap);

// 解析结果
enum class ParseResult {
    NEED_MORE,   // 字节不够，等下一包
    OK,          // 解析出一帧
    BAD_CRC,     // 校验失败
    BAD_FRAME,   // 帧头/长度非法
};

struct Frame {
    uint8_t type;
    uint8_t seq;
    uint8_t len;
    uint8_t payload[cfg::FRAME_MAX_LEN];
};

// 流式解析器：每次喂进一段字节，内部记住"已收到但还没凑齐一帧"的残余。
// 这样蓝牙分包（MTU 23 字节限制）就不会造成丢帧。
class FrameParser {
public:
    // 返回 OK 时，out 里是一帧完整数据，consumed 是被消耗的字节数
    ParseResult feed(const uint8_t* data, size_t len, Frame& out, size_t& consumed);
    void reset();

private:
    static const size_t kBufCap = 128;
    uint8_t  buf_[kBufCap] = {0};
    size_t   size_ = 0;
};

// ---- 传感器数据帧的载荷布局（TYPE_SENSOR_DATA）----
// 所有浮点都放大 10 倍变整数传输：省带宽、免字节序歧义、手机端 ÷10 还原。
// 偏移  类型   含义
//  0    u16    温度 ×10（AHT20）
//  2    u16    湿度 ×10
//  4    u16    气压 ×10（hPa）
//  6    u16    光照（lux，本身是整数不用放大）
//  8    i16    第二温度源 ×10（BMP280，用于交叉校验）
//  10   u8     flags 传感器健康位
//  11   u8     电量占位（本项目 USB 供电，恒为 100）
inline const uint8_t SENSOR_PAYLOAD_LEN = 12;

void packSensorPayload(uint8_t* out,
                       float tempAht, float hum, float press,
                       uint16_t lux, float tempBmp,
                       uint8_t flags);

}  // namespace proto
