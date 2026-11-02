#include "sensors.h"
#include <Arduino.h>
#include <Wire.h>
#include <math.h>
#include <Adafruit_AHTX0.h>
#include <Adafruit_BMP280.h>
#include <BH1750.h>

// BH1750 库的对象是值类型（不像 Adafruit 那两款支持 new + begin），
// 所以定义成文件级静态：保证 begin() 和 readAll() 操作的是同一个对象。
// ⚠️ 坑点提醒：如果把它写成函数内的 static 局部变量，begin() 和 readAll()
//    里会各自生成一个独立对象，读出来永远是 0。这是初学者最常踩的坑之一。
static BH1750 gLightMeter;

// ---------------------------------------------------------------------------
// 为什么用 void* 而不是直接放对象？
// 因为头文件 sensors.h 要保持"干净"（不依赖第三方库），这样单元测试时可以
// 直接 include sensors.h 而不必拉进一堆 Arduino 依赖。
// 代价是这里要做类型转换，写的时候小心一点即可。
// ---------------------------------------------------------------------------

bool SensorHub::begin() {
    // 以 100kHz 启动 I2C。100kHz 是"标准模式"，比 400kHz 抗干扰、线长也能跑。
    Wire.begin(cfg::PIN_SDA, cfg::PIN_SCL);
    Wire.setClock(100000);

    Serial.println(F("[I2C] 扫描总线..."));
    uint8_t found[8];
    int n = scanBus(found, 8);
    if (n == 0) {
        Serial.println(F("[I2C] 一个设备都没找到！检查接线：SDA->21, SCL->22, VCC->3V3, GND->GND"));
    } else {
        Serial.print(F("[I2C] 找到 "));
        Serial.print(n);
        Serial.println(F(" 个设备"));
    }

    // ---- AHT20 温湿度 ----
    aht_ = new Adafruit_AHTX0();
    ahtOk_ = static_cast<Adafruit_AHTX0*>(aht_)->begin();
    Serial.print(F("[SENSOR] AHT20  : "));
    Serial.println(ahtOk_ ? F("OK") : F("缺失"));

    // ---- BMP280 气压 ----
    // 先试 0x76，失败再试 0x77（市面两种模块都有）
    bmp_ = new Adafruit_BMP280();
    Adafruit_BMP280* bmp = static_cast<Adafruit_BMP280*>(bmp_);
    bmpOk_ = bmp->begin(cfg::ADDR_BMP280);
    if (!bmpOk_) {
        Serial.println(F("[SENSOR] BMP280 0x76 未响应，试 0x77..."));
        bmpOk_ = bmp->begin(0x77);
    }
    if (bmpOk_) {
        // 室内监测推荐配置：强制模式 + 高精度采样，省电且噪声小
        bmp->setSampling(Adafruit_BMP280::MODE_NORMAL,
                         Adafruit_BMP280::SAMPLING_X2,   // 温度过采样
                         Adafruit_BMP280::SAMPLING_X16,  // 气压过采样
                         Adafruit_BMP280::FILTER_X4,     // IIR 滤波
                         Adafruit_BMP280::STANDBY_MS_500);
    }
    Serial.print(F("[SENSOR] BMP280 : "));
    Serial.println(bmpOk_ ? F("OK") : F("缺失"));

    // ---- BH1750 光照 ----
    bhOk_ = gLightMeter.begin(BH1750::CONTINUOUS_HIGH_RES_MODE);
    Serial.print(F("[SENSOR] BH1750 : "));
    Serial.println(bhOk_ ? F("OK") : F("缺失"));

    // BH1750 对象存在静态区，readAll 里通过同一个静态实例访问
    // （这里是故意的简化：BH1750 库没有默认构造函数外的状态需求）
    return available();
}

int SensorHub::scanBus(uint8_t* found, int maxFound) {
    int count = 0;
    for (uint8_t addr = 1; addr < 127; ++addr) {
        Wire.beginTransmission(addr);
        uint8_t err = Wire.endTransmission();   // 0 = 有设备应答
        if (err == 0) {
            Serial.print(F("  0x"));
            if (addr < 16) Serial.print('0');
            Serial.println(addr, HEX);
            if (count < maxFound) found[count] = addr;
            ++count;
        }
    }
    return count;
}

bool SensorHub::readAll(SensorSample& out) {
    bool anyOk = false;

    // ---- AHT20：一次读温度和湿度 ----
    if (ahtOk_) {
        sensors_event_t humEvt, tempEvt;
        static_cast<Adafruit_AHTX0*>(aht_)->getEvent(&humEvt, &tempEvt);
        float t = tempEvt.temperature;
        float h = humEvt.relative_humidity;
        if (!isnan(t) && !isnan(h)) {
            out.tempAht = t;
            out.hum = (h < 0.0f) ? 0.0f : (h > 100.0f ? 100.0f : h);  // 钳位
            out.okAht = true;
            anyOk = true;
        } else {
            out.okAht = false;   // 读到 NaN，本轮判该传感器失效
        }
    }

    // ---- BMP280：气压 + 第二路温度 ----
    if (bmpOk_) {
        float t = static_cast<Adafruit_BMP280*>(bmp_)->readTemperature();
        float p = static_cast<Adafruit_BMP280*>(bmp_)->readPressure();  // 单位 Pa
        if (!isnan(t) && !isnan(p)) {
            out.tempBmp = t;
            out.press = p / 100.0f;     // Pa → hPa（1 hPa = 100 Pa）
            out.okBmp = true;
            anyOk = true;
        } else {
            out.okBmp = false;
        }
        // AHT20 挂了的话，用 BMP280 的温度顶上，保证 App 始终有温度曲线
        if (!out.okAht) {
            out.tempAht = out.tempBmp;
            out.okAht = out.okBmp;
        }
    }

    // ---- BH1750：光照 ----
    if (bhOk_) {
        float l = gLightMeter.readLightLevel();
        if (l >= 0.0f) {
            out.lux = static_cast<uint16_t>(l);
            out.okBh = true;
            anyOk = true;
        } else {
            out.okBh = false;
        }
    }

    return anyOk;
}

// ---------------------------------------------------------------------------
// 气压换算海拔（国际标准大气公式）
//   h = 44330 × [1 − (p / p0)^(1/5.255)]
//   p0 = 1013.25 hPa（海平面标准气压）
// 注意：这是"气压高度"，天气变化会让同一地点读数漂移几十米，
//      所以它只适合看相对变化，不适合当绝对海拔。答辩要说明这个局限。
// ---------------------------------------------------------------------------
float SensorHub::pressureToAltitude(float pressHpa) {
    if (pressHpa <= 0.0f) return 0.0f;
    return 44330.0f * (1.0f - powf(pressHpa / 1013.25f, 1.0f / 5.255f));
}
