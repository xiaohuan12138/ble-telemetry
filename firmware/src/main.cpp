// ============================================================================
// 传感中枢 SensorHub —— ESP32 下位机主程序
// ----------------------------------------------------------------------------
// 程序骨架（答辩时一句话讲清）：
//   setup() 里把"传感器 / 执行器 / BLE 服务"三件事初始化好；
//   loop()  里用 millis() 做非阻塞定时，到点了就采一次样、组一帧、推给手机；
//   手机下发的命令由 BLE 写回调异步触发，处理完回一帧 ACK。
//
// ⚠️ 铁律：loop() 里绝对不能出现 delay(1000) 这种长阻塞。
//   蓝牙协议栈跑在后台任务上，主循环卡住会导致 Notify 堆积、连接超时断开。
//   所有延时一律用 millis() 做时间戳比较。
// ============================================================================

#include <Arduino.h>
#include "config.h"
#include "protocol.h"
#include "sensors.h"
#include "actuators.h"
#include "ble_server.h"

// ------------------------------ 全局对象 ------------------------------------
static SensorHub   gSensors;
static Actuators   gActuators;
static BleServer   gBle;

// ------------------------------ 运行状态 ------------------------------------
static uint16_t   gPeriodMs    = cfg::PERIOD_DEFAULT_MS;  // 当前上报周期
static uint32_t   gLastSample  = 0;                       // 上次采样时刻
static uint32_t   gLastBeat    = 0;                       // 上次心跳时刻
static uint32_t   gBootTime    = 0;                       // 开机时刻（算 uptime）
static uint32_t   gFrameCount  = 0;                       // 已发送帧数（统计用）
static bool       gForceReport = false;                   // 手机请求立即上报
static float      gAlertTempHi = 35.0f;                   // 本地高温告警阈值

// ---------------------------------------------------------------------------
// 发送一帧（统一出口，方便加日志/统计）
// ---------------------------------------------------------------------------
static void sendFrame(uint8_t type, const uint8_t* payload, uint8_t len) {
    uint8_t buf[cfg::FRAME_MAX_SIZE];
    size_t n = proto::buildFrame(type, payload, len, buf, sizeof(buf));
    if (n == 0) return;
    if (gBle.notify(buf, n)) {
        ++gFrameCount;
    }
}

// ---------------------------------------------------------------------------
// 采样 + 上报（核心业务逻辑）
// ---------------------------------------------------------------------------
static void sampleAndReport() {
    SensorSample s;
    bool ok = gSensors.readAll(s);

    if (!ok) {
        Serial.println(F("[SAMPLER] 所有传感器读取失败，本轮只发心跳"));
        return;
    }

    // 打包载荷（12 字节，见 protocol.h 的布局表）
    uint8_t payload[proto::SENSOR_PAYLOAD_LEN];
    proto::packSensorPayload(payload,
                             s.tempAht, s.hum, s.press,
                             s.lux, s.tempBmp, s.flags());

    sendFrame(cfg::TYPE_SENSOR_DATA, payload, proto::SENSOR_PAYLOAD_LEN);

    // —— 本地告警：温度超阈值 ——
    if (s.okAht && s.tempAht > gAlertTempHi) {
        uint16_t v = static_cast<uint16_t>(s.tempAht * 10);
        uint16_t th = static_cast<uint16_t>(gAlertTempHi * 10);
        uint8_t ap[5] = {
            cfg::ALERT_TEMP_HIGH,
            static_cast<uint8_t>(v >> 8), static_cast<uint8_t>(v & 0xFF),
            static_cast<uint8_t>(th >> 8), static_cast<uint8_t>(th & 0xFF)
        };
        sendFrame(cfg::TYPE_ALERT, ap, 5);
    }

    // —— 双温度源交叉校验：差值过大说明有颗传感器漂移/损坏 ——
    if (!s.tempConsistent()) {
        uint16_t v = static_cast<uint16_t>(s.tempAht * 10);
        uint16_t v2 = static_cast<uint16_t>(s.tempBmp * 10);
        uint8_t ap[5] = {cfg::ALERT_SENSOR_FAULT,
                         static_cast<uint8_t>(v >> 8), static_cast<uint8_t>(v & 0xFF),
                         static_cast<uint8_t>(v2 >> 8), static_cast<uint8_t>(v2 & 0xFF)};
        sendFrame(cfg::TYPE_ALERT, ap, 5);
    }

    // 串口回显，方便不开手机也能验证硬件是否正常
    // 注意：ESP32 的 printf 用 %u 输出 unsigned，不要写 %lu（会错位读参）
    Serial.printf("[DATA] T=%.1fC H=%.1f%% P=%.1fhPa L=%u flags=0x%02X\n",
                  s.tempAht, s.hum, s.press,
                  static_cast<unsigned int>(s.lux), s.flags());
}

// ---------------------------------------------------------------------------
// 心跳帧：即便没有传感器数据也要证明"我还活着"
// 手机端 5 秒收不到任何帧就判定链路异常，触发重连。
// ---------------------------------------------------------------------------
static void sendHeartbeat() {
    uint32_t up = (millis() - gBootTime) / 1000;
    uint8_t p[4] = {static_cast<uint8_t>(up >> 24), static_cast<uint8_t>(up >> 16),
                    static_cast<uint8_t>(up >> 8), static_cast<uint8_t>(up & 0xFF)};
    sendFrame(cfg::TYPE_HEARTBEAT, p, 4);
}

// ---------------------------------------------------------------------------
// 命令处理：手机写 CTRL / PERIOD 时被调用
// ---------------------------------------------------------------------------
static void handleCommand(uint8_t type, const uint8_t* payload, uint8_t len) {
    uint8_t status = cfg::ACK_OK;

    switch (type) {
        case cfg::CMD_SET_LED: {
            if (len < 1) { status = cfg::ACK_ERR_LEN; break; }
            gActuators.setLed(payload[0]);
            Serial.printf("[CMD] LED -> %u\n", payload[0]);
            break;
        }
        case cfg::CMD_SET_BUZZER: {
            if (len < 3) { status = cfg::ACK_ERR_LEN; break; }
            bool on = payload[0] != 0;
            uint16_t ms = (static_cast<uint16_t>(payload[1]) << 8) | payload[2];
            if (on) gActuators.beep(ms);
            else    gActuators.setBuzzer(false);
            Serial.printf("[CMD] BUZZER on=%d ms=%u\n", on, ms);
            break;
        }
        case cfg::CMD_SET_PERIOD: {
            if (len < 2) { status = cfg::ACK_ERR_LEN; break; }
            uint16_t ms = (static_cast<uint16_t>(payload[0]) << 8) | payload[1];
            // 边界钳位：太快会把蓝牙堵死，太慢又失去实时性
            if (ms < cfg::PERIOD_MIN_MS) ms = cfg::PERIOD_MIN_MS;
            if (ms > cfg::PERIOD_MAX_MS) ms = cfg::PERIOD_MAX_MS;
            gPeriodMs = ms;
            gBle.setPeriodValue(ms);
            Serial.printf("[CMD] PERIOD -> %u ms\n", ms);
            break;
        }
        case cfg::CMD_FORCE_REPORT: {
            gForceReport = true;      // 标记一下，loop 里立刻采一次
            Serial.println(F("[CMD] 强制上报"));
            break;
        }
        default:
            status = cfg::ACK_ERR_TYPE;
            Serial.printf("[CMD] 未知类型 0x%02X\n", type);
            break;
    }

    // 回 ACK：让手机知道命令到了（这是可靠双向链路的证据）
    uint8_t ack[2] = {type, status};
    sendFrame(cfg::TYPE_ACK, ack, 2);
}

// ---------------------------------------------------------------------------
// 串口调试命令（不用手机也能测硬件）
// 打开串口监视器 115200，输入 led 128 / beep 500 / period 2000 / scan
// ---------------------------------------------------------------------------
static void handleSerial() {
    if (!Serial.available()) return;
    String line = Serial.readStringUntil('\n');
    line.trim();
    if (line.length() == 0) return;

    if (line.startsWith("led")) {
        int v = line.substring(3).toInt();
        gActuators.setLed(constrain(v, 0, 255));
        Serial.printf("LED = %d\n", constrain(v, 0, 255));
    } else if (line.startsWith("beep")) {
        int ms = line.substring(4).toInt();
        gActuators.beep(ms > 0 ? ms : 200);
        Serial.println("BEEP");
    } else if (line.startsWith("period")) {
        int ms = line.substring(6).toInt();
        gPeriodMs = constrain(ms, cfg::PERIOD_MIN_MS, cfg::PERIOD_MAX_MS);
        gBle.setPeriodValue(gPeriodMs);
        Serial.printf("PERIOD = %u ms\n", gPeriodMs);
    } else if (line == "scan") {
        uint8_t found[8];
        gSensors.scanBus(found, 8);
    } else if (line == "report") {
        sampleAndReport();
    } else if (line == "status") {
        Serial.printf("frames=%lu period=%ums conn=%d sub=%d\n",
                      static_cast<unsigned long>(gFrameCount), gPeriodMs,
                      gBle.connected(), gBle.subscribed());
    } else {
        Serial.println(F("命令：led <0-255> | beep <ms> | period <ms> | scan | report | status"));
    }
}

// ---------------------------------------------------------------------------
// setup
// ---------------------------------------------------------------------------
void setup() {
    Serial.begin(115200);
    delay(200);                                 // 等串口稳定
    Serial.println();
    Serial.println(F("========================================"));
    Serial.println(F("  传感中枢 SensorHub · ESP32 下位机"));
    Serial.printf("  固件 v%s  设备名 %s\n", cfg::FW_VERSION, cfg::DEVICE_NAME);
    Serial.println(F("========================================"));

    gBootTime = millis();

    // 1) 传感器（含 I2C 扫描，接错线会立刻在这里暴露）
    if (!gSensors.begin()) {
        Serial.println(F("[WARN] 传感器初始化异常，将以降级模式运行"));
    }

    // 2) 执行器
    gActuators.begin();
    gActuators.beep(120);                       // 开机"滴"一声，确认蜂鸣器接线对

    // 3) BLE
    gBle.begin(cfg::DEVICE_NAME);
    gBle.setPeriodValue(gPeriodMs);
    gBle.setBatteryValue(100);
    gBle.onCommand(handleCommand);
    gBle.onConnect([](bool connected) {
        // 连上时闪一下灯，断开时灭灯——纯物理层能看出连接状态
        gActuators.setLed(connected ? 30 : 0);
    });

    gLastSample = millis();
    gLastBeat = millis();
    Serial.println(F("[SYS] 初始化完成，输入 help 查看串口命令"));
}

// ---------------------------------------------------------------------------
// loop
// ---------------------------------------------------------------------------
void loop() {
    uint32_t now = millis();

    // 1) 到点采样上报（或手机请求立即上报）
    if (now - gLastSample >= gPeriodMs || gForceReport) {
        gLastSample = now;
        gForceReport = false;
        sampleAndReport();
    }

    // 2) 心跳：与采样独立计时，采样周期被调大时链路仍能被证明存活
    if (now - gLastBeat >= cfg::HEARTBEAT_INTERVAL_MS) {
        gLastBeat = now;
        sendHeartbeat();
    }

    // 3) 执行器非阻塞定时（到点关蜂鸣器）
    gActuators.tick();

    // 4) 串口调试
    handleSerial();

    // 5) 让出 CPU 给蓝牙协议栈任务（FreeRTOS 下必须，否则看门狗可能复位）
    delay(5);
}
