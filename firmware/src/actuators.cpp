#include "actuators.h"
#include <Arduino.h>
#include "config.h"

void Actuators::begin() {
    // ledc = LED Control，ESP32 硬件 PWM 外设
    // 三个参数：通道号、频率、分辨率位数（8 位 → 占空比 0~255）
    ledcSetup(cfg::PWM_CHANNEL, cfg::PWM_FREQ, cfg::PWM_RESOLUTION);
    ledcAttachPin(cfg::PIN_LED, cfg::PWM_CHANNEL);
    ledcWrite(cfg::PWM_CHANNEL, 0);          // 上电先熄灭

    pinMode(cfg::PIN_BUZZER, OUTPUT);
    digitalWrite(cfg::PIN_BUZZER, LOW);      // 有源蜂鸣器：低电平静音
    Serial.println(F("[ACT] LED(PWM) 与蜂鸣器已初始化"));
}

void Actuators::setLed(uint8_t brightness) {
    ledLevel_ = brightness;
    ledcWrite(cfg::PWM_CHANNEL, brightness);  // 硬件自动持续输出，无需软件维持
}

void Actuators::beep(uint16_t ms) {
    if (ms == 0) { setBuzzer(false); return; }
    setBuzzer(true);
    buzzerStopAt_ = millis() + ms;           // 记下"该关"的时刻
}

void Actuators::setBuzzer(bool on) {
    buzzerOn_ = on;
    digitalWrite(cfg::PIN_BUZZER, on ? HIGH : LOW);
}

// 非阻塞定时：不用 delay()，否则会卡住蓝牙协议栈导致断连
void Actuators::tick() {
    if (buzzerOn_ && buzzerStopAt_ != 0 && millis() >= buzzerStopAt_) {
        setBuzzer(false);
        buzzerStopAt_ = 0;
    }
}
