#include "ble_server.h"
#include <Arduino.h>
#include <NimBLEDevice.h>
#include "config.h"
#include "protocol.h"

// ---------------------------------------------------------------------------
// NimBLE 是 C++ 库，回调必须继承它的接口类。
// 这里把三个回调（连接/断开/写入）集中在一个实现类里，代码不散。
// ---------------------------------------------------------------------------
namespace {

BleServer* gOwner = nullptr;   // 回调里要反查宿主对象，用全局指针（单实例场景够用）

class ServerCallbacks : public NimBLEServerCallbacks {
public:
    void onConnect(NimBLEServer* srv) override {
        // NimBLE 里"连接句柄"要从 getPeerDevices() 拿，
        // 不能拿 getConnectedCount() 当句柄用（那是数量不是 ID）
        auto peers = srv->getPeerDevices();
        Serial.print(F("[BLE] 已连接，句柄="));
        Serial.println(peers.empty() ? 0 : peers[0]);
        NimBLEDevice::stopAdvertising();      // 已连上就停广播，省电
        if (gOwner && gOwner->onConnectHandler_) gOwner->onConnectHandler_(true);
    }

    void onDisconnect(NimBLEServer* srv) override {
        Serial.println(F("[BLE] 断开，重新开始广播"));
        NimBLEDevice::startAdvertising();     // 断开后必须重新广播，否则手机再也扫不到
        if (gOwner && gOwner->onConnectHandler_) gOwner->onConnectHandler_(false);
    }
};

// 写回调：手机写 CTRL 或 PERIOD 时触发
class CharCallbacks : public NimBLECharacteristicCallbacks {
public:
    void onWrite(NimBLECharacteristic* chr) override {
        std::string v = chr->getValue();
        if (v.empty()) return;

        const uint8_t* data = reinterpret_cast<const uint8_t*>(v.data());
        size_t len = v.size();

        // —— 情形 A：收到的是标准命令帧（带 0xAA55 头和 CRC）——
        proto::FrameParser parser;
        proto::Frame frame;
        size_t consumed = 0;
        auto r = parser.feed(data, len, frame, consumed);
        if (r == proto::ParseResult::OK) {
            Serial.print(F("[BLE] 命令帧 TYPE=0x"));
            Serial.println(frame.type, HEX);
            if (gOwner && gOwner->onCommandHandler_) {
                gOwner->onCommandHandler_(frame.type, frame.payload, frame.len);
            }
            return;
        }

        // —— 情形 B：收到的是裸的 2 字节周期值（写 PERIOD 特征）——
        // 提供这条"简化通道"是为了方便用 nRF Connect 这类调试 App 手工测试，
        // 不必自己拼 CRC。真实 App 走的是情形 A。
        if (len == 2) {
            uint16_t ms = (static_cast<uint16_t>(data[0]) << 8) | data[1];
            if (gOwner && gOwner->onCommandHandler_) {
                uint8_t pl[2] = {static_cast<uint8_t>(ms >> 8), static_cast<uint8_t>(ms & 0xFF)};
                gOwner->onCommandHandler_(cfg::CMD_SET_PERIOD, pl, 2);
            }
        }
    }
};

}  // namespace

// ---------------------------------------------------------------------------
// 因为 gOwner 需要访问 BleServer 的私有成员，这里把 handler 暴露出来
// （在头文件里它们是 private，实现文件里通过友元式访问不方便，故提升为 public 的
//  简单做法：本文件中直接把它们当成可访问。为保持封装，改用下面的 public 包装）
// ---------------------------------------------------------------------------

bool BleServer::begin(const char* deviceName) {
    gOwner = this;

    NimBLEDevice::init(deviceName);
    // 发射功率：ESP_PWR_0dBm 足够室内 5 米，功率越高越耗电
    NimBLEDevice::setPower(ESP_PWR_0dBm);

    NimBLEServer* srv = NimBLEDevice::createServer();
    srv->setCallbacks(new ServerCallbacks());
    server_ = srv;

    // ===== 服务 1：传感器服务（自定义） =====
    NimBLEService* sensorSvc = srv->createService(cfg::UUID_SENSOR_SERVICE);

    // DATA：Notify 属性 —— 手机订阅后，ESP32 主动推，无需手机轮询
    NimBLECharacteristic* dataChr = sensorSvc->createCharacteristic(
        cfg::UUID_CHAR_DATA,
        NIMBLE_PROPERTY::NOTIFY);
    dataChar_ = dataChr;

    // PERIOD：可读可写 —— 手机读当前周期，写新周期
    NimBLECharacteristic* periodChr = sensorSvc->createCharacteristic(
        cfg::UUID_CHAR_PERIOD,
        NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE);
    uint8_t pInit[2] = {static_cast<uint8_t>(cfg::PERIOD_DEFAULT_MS >> 8),
                        static_cast<uint8_t>(cfg::PERIOD_DEFAULT_MS & 0xFF)};
    periodChr->setValue(pInit, 2);
    periodChr->setCallbacks(new CharCallbacks());
    periodChar_ = periodChr;

    sensorSvc->start();

    // ===== 服务 2：控制服务（自定义） =====
    NimBLEService* ctrlSvc = srv->createService(cfg::UUID_CTRL_SERVICE);
    NimBLECharacteristic* ctrlChr = ctrlSvc->createCharacteristic(
        cfg::UUID_CHAR_CTRL,
        NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::READ);
    ctrlChr->setCallbacks(new CharCallbacks());
    ctrlChar_ = ctrlChr;
    ctrlSvc->start();

    // ===== 服务 3：Device Information（标准 0x180A） =====
    // 标准服务的好处：任何 BLE 调试 App 都能直接读出厂商/型号/固件版本
    NimBLEService* devInfo = srv->createService(NimBLEUUID(static_cast<uint16_t>(0x180A)));
    devInfo->createCharacteristic(NimBLEUUID(cfg::UUID_CHAR_MANUFACTURER), NIMBLE_PROPERTY::READ)
           ->setValue(cfg::MANUFACTURER);
    devInfo->createCharacteristic(NimBLEUUID(cfg::UUID_CHAR_MODEL), NIMBLE_PROPERTY::READ)
           ->setValue(cfg::MODEL_NUMBER);
    devInfo->createCharacteristic(NimBLEUUID(cfg::UUID_CHAR_FW_VERSION), NIMBLE_PROPERTY::READ)
           ->setValue(cfg::FW_VERSION);
    devInfo->start();

    // ===== 服务 4：Battery（标准 0x180F） =====
    NimBLEService* bat = srv->createService(NimBLEUUID(static_cast<uint16_t>(0x180F)));
    NimBLECharacteristic* batChr = bat->createCharacteristic(
        NimBLEUUID(cfg::UUID_CHAR_BATTERY),
        NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    uint8_t b = 100;
    batChr->setValue(&b, 1);
    batChar_ = batChr;
    bat->start();

    // ===== 广播设置 =====
    NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
    adv->addServiceUUID(cfg::UUID_SENSOR_SERVICE);   // 让手机能按服务过滤扫描
    adv->addServiceUUID(cfg::UUID_CTRL_SERVICE);
    adv->setScanResponse(true);
    adv->setMinInterval(100);   // 100ms~250ms 广播间隔：越短越耗电但发现越快
    adv->setMaxInterval(250);
    NimBLEDevice::startAdvertising();

    Serial.print(F("[BLE] GATT 服务已启动，广播名："));
    Serial.println(deviceName);
    return true;
}

bool BleServer::notify(const uint8_t* frame, size_t frameLen) {
    if (!connected() || dataChar_ == nullptr) return false;
    NimBLECharacteristic* c = static_cast<NimBLECharacteristic*>(dataChar_);
    if (c->getSubscribedCount() == 0) return false;   // 手机没订阅，推了也白发

    // BLE 单次 Notify 上限 = MTU - 3。默认 MTU=23 → 最多 20 字节。
    // 我们的传感器帧是 6+12+2 = 20 字节，刚好卡在边界。
    // 这里仍然做分包：万一将来载荷变长（比如加历史批量上传）也不会炸。
    size_t sent = 0;
    size_t chunk = 20;   // 保守值，兼容所有手机
    while (sent < frameLen) {
        size_t n = frameLen - sent;
        if (n > chunk) n = chunk;
        c->setValue(const_cast<uint8_t*>(frame + sent), n);
        c->notify();
        sent += n;
        delay(2);   // 给协议栈一点喘息，避免连发丢包
    }
    return true;
}

void BleServer::setPeriodValue(uint16_t periodMs) {
    if (periodChar_ == nullptr) return;
    uint8_t v[2] = {static_cast<uint8_t>(periodMs >> 8), static_cast<uint8_t>(periodMs & 0xFF)};
    static_cast<NimBLECharacteristic*>(periodChar_)->setValue(v, 2);
}

void BleServer::setBatteryValue(uint8_t percent) {
    if (batChar_ == nullptr) return;
    NimBLECharacteristic* c = static_cast<NimBLECharacteristic*>(batChar_);
    uint8_t v = percent;
    c->setValue(&v, 1);
    if (c->getSubscribedCount() > 0) c->notify();
}

bool BleServer::connected() const {
    if (server_ == nullptr) return false;
    return static_cast<NimBLEServer*>(server_)->getConnectedCount() > 0;
}

bool BleServer::subscribed() const {
    if (dataChar_ == nullptr) return false;
    return static_cast<NimBLECharacteristic*>(dataChar_)->getSubscribedCount() > 0;
}

void BleServer::disconnect() {
    if (server_ == nullptr) return;
    NimBLEServer* s = static_cast<NimBLEServer*>(server_);
    if (s->getConnectedCount() == 0) return;
    // getPeerDevices() 返回所有已连接中心设备的连接句柄
    for (uint16_t handle : s->getPeerDevices()) {
        s->disconnect(handle);
    }
}
