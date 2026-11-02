// ============================================================================
// 传感器采集层：AHT20（温湿度）+ BMP280（气压/温度）+ BH1750（光照）
// ----------------------------------------------------------------------------
// 三个模块都挂在同一组 I2C 上（GPIO21/22），靠 7 位地址区分：
//   AHT20 = 0x38   BMP280 = 0x76   BH1750 = 0x23
//
// 设计要点（答辩可讲）：
// 1) 每个传感器独立 try-begin，单个挂掉不影响其它两个——这叫"优雅降级"。
// 2) AHT20 与 BMP280 都能测温，两个值做交叉校验：差值 > 2℃ 就判定异常。
//    这是低成本的冗余设计，一个传感器漂移了能被发现。
// 3) 读数做合理性钳位（湿度 0~100%），杜绝 NaN/负数污染上层。
// ============================================================================
#pragma once

#include <stdint.h>
#include "config.h"

// 一次采样的完整结果
struct SensorSample {
    float    tempAht = 0.0f;    // 温度 ℃（AHT20）
    float    hum     = 0.0f;    // 相对湿度 %
    float    press   = 0.0f;    // 气压 hPa
    uint16_t lux     = 0;       // 光照 lux
    float    tempBmp = 0.0f;    // 温度 ℃（BMP280，第二温度源）
    bool     okAht   = false;   // 各传感器在岗标记
    bool     okBmp   = false;
    bool     okBh    = false;

    // 打包成协议里的 flags 字节
    uint8_t flags() const {
        uint8_t f = 0;
        if (okAht) f |= cfg::FLAG_AHT20_OK;
        if (okBmp) f |= cfg::FLAG_BMP280_OK;
        if (okBh)  f |= cfg::FLAG_BH1750_OK;
        return f;
    }

    // 双温度源是否一致（差值 ≤ 2℃ 视为正常）
    bool tempConsistent() const {
        if (!okAht || !okBmp) return true;   // 只有一个源，无从比对，判正常
        float d = tempAht - tempBmp;
        if (d < 0) d = -d;
        return d <= 2.0f;
    }
};

class SensorHub {
public:
    // 初始化 I2C 与三个传感器；串口打印扫描结果（调试必备）
    bool begin();

    // 读一轮全部传感器。返回 false 表示"一个都没读到"
    bool readAll(SensorSample& out);

    // 扫描 I2C 总线上有哪些设备（返回找到的地址个数）
    // 用途：硬件接错线时，一眼看出是地址不对还是线没接
    int scanBus(uint8_t* found, int maxFound);

    bool available() const { return ahtOk_ || bmpOk_ || bhOk_; }

    // 气压 → 海拔（国际标准大气模型）
    static float pressureToAltitude(float pressHpa);

private:
    bool ahtOk_ = false;
    bool bmpOk_ = false;
    bool bhOk_  = false;
    void* aht_  = nullptr;   // 用 void* 隔离第三方库类型，避免头文件污染
    void* bmp_  = nullptr;
};
