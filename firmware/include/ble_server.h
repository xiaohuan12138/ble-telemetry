// ============================================================================
// BLE GATT 服务器（基于 NimBLE-Arduino）
// ----------------------------------------------------------------------------
// 角色说明：ESP32 是 GATT Server（外设/从机），手机是 GATT Client（中心设备）。
// ESP32 主动广播，手机扫描到后发起连接，连上后手机"发现服务"，然后：
//   - 订阅 DATA 特征（Notify）→ ESP32 每秒主动推数据，手机被动收
//   - 写 CTRL 特征 → 手机下发命令（开灯/改周期）
//   - 读/写 PERIOD 特征 → 查询和调整上报周期
//
// 为什么选 NimBLE 而不是官方 Bluedroid BLE 库？
//   官方库 RAM 占用大（约 100KB+），NimBLE 只有一半，且连接更稳。
//   ESP32-WROOM 只有 520KB RAM，省出来的空间留给协议缓冲。
// ============================================================================
#pragma once

#include <stdint.h>
#include <stddef.h>
#include <functional>

// 手机写 CTRL 特征时的回调：参数 = 命令类型、载荷、载荷长度
using CommandHandler = std::function<void(uint8_t type, const uint8_t* payload, uint8_t len)>;
using ConnectHandler = std::function<void(bool connected)>;

class BleServer {
public:
    bool begin(const char* deviceName);

    // 推送一帧（内部自动分包 + 检查连接状态 + 检查订阅状态）
    bool notify(const uint8_t* frame, size_t frameLen);

    // 更新 PERIOD 特征的当前值（手机读的时候能读到最新）
    void setPeriodValue(uint16_t periodMs);
    void setBatteryValue(uint8_t percent);

    bool connected() const;
    bool subscribed() const;

    void onCommand(CommandHandler h) { cmdHandler_ = h; }
    void onConnect(ConnectHandler h) { connHandler_ = h; }

    // 断开当前连接（用于"手机端主动断开"或异常恢复）
    void disconnect();

    // 这两个 handler 设为 public：NimBLE 的回调类定义在 .cpp 的匿名命名空间里，
    // 需要通过宿主指针反查并调用，私有成员访问不到。
    // 代价是外部也能读，但本项目是单实例，可接受。
    CommandHandler cmdHandler_ = nullptr;
    ConnectHandler connHandler_ = nullptr;

private:
    void* server_ = nullptr;    // NimBLEServer*
    void* dataChar_ = nullptr;  // NimBLECharacteristic*
    void* periodChar_ = nullptr;
    void* batChar_ = nullptr;
    void* ctrlChar_ = nullptr;
};
