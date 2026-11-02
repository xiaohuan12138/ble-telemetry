// ============================================================================
// 执行器层：LED（PWM 调光）+ 有源蜂鸣器
// ----------------------------------------------------------------------------
// 这一层体现"反向控制"——不是只读数据，而是手机能反过来驱动硬件。
// 这是 C5（蓝牙设备控制）区别于"蓝牙数据采集"的关键，答辩重点讲。
//
// LED 为什么必须串 220Ω 电阻？
//   ESP32 引脚 3.3V，LED 正向压降约 2V，若不限流电流会超过引脚上限(12mA)
//   烧芯片。串 220Ω 后 I = (3.3-2)/220 ≈ 6.8mA，安全且够亮。
//
// 为什么用 PWM 而不是直接 digitalWrite？
//   PWM 能调亮度（0~255），可以做"光照越低 LED 越亮"的闭环演示，
//   比单纯的开关有说服力得多。
// ============================================================================
#pragma once

#include <stdint.h>

class Actuators {
public:
    void begin();

    // 设置 LED 亮度 0~255（0 = 灭）
    void setLed(uint8_t brightness);
    uint8_t led() const { return ledLevel_; }

    // 蜂鸣器：鸣叫 ms 毫秒后自动停止（非阻塞，靠 loop 里 tick 检查）
    void beep(uint16_t ms);
    void setBuzzer(bool on);
    bool buzzer() const { return buzzerOn_; }

    // 必须放进 loop()：负责到点关蜂鸣器
    void tick();

private:
    uint8_t  ledLevel_ = 0;
    bool     buzzerOn_ = false;
    uint32_t buzzerStopAt_ = 0;
};
