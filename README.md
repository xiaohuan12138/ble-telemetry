# 传感中枢 SensorHub

> 基于 BLE 的嵌入式多传感器数据采集与控制系统
> Android 课程设计 · 主 App（C5 蓝牙设备控制）+ 伴生模块（P1 桌面小组件）

---

## 一、这个项目在做什么

一句话：**用 ESP32 采集温湿度/气压/光照，通过 BLE 以自定义二进制协议上报；Android 端完成扫描连接、协议解析、实时曲线、阈值告警与反向控制。**

```
┌─────────────────┐      自定义二进制帧（CRC16 校验）      ┌──────────────────┐
│  ESP32-WROOM-32 │  ──────────── BLE ─────────────────▶  │  Android 手机     │
│                 │                                        │                  │
│  AHT20  温湿度  │  ◀──── 控制命令（LED/蜂鸣器/周期）─────  │  6 个界面        │
│  BMP280 气压   │                                        │  Room 持久化     │
│  BH1750 光照   │                                        │  规则引擎告警     │
│  LED + 蜂鸣器   │                                        │  桌面小组件       │
└─────────────────┘                                        └──────────────────┘
```

---

## 二、目录结构

```
ble-sensorhub/
├── firmware/                    # ESP32 下位机（PlatformIO 工程）
│   ├── platformio.ini
│   ├── include/
│   │   ├── config.h             # 引脚 / UUID / 周期 / 帧类型常量
│   │   ├── protocol.h           # 协议帧与 CRC16 声明
│   │   ├── sensors.h            # 传感器采集
│   │   ├── actuators.h          # LED PWM + 蜂鸣器
│   │   └── ble_server.h         # NimBLE GATT Server
│   └── src/
│       ├── main.cpp             # setup / loop 主流程
│       ├── protocol.cpp         # 组帧、CRC16、流式解析
│       ├── sensors.cpp          # AHT20 + BMP280 + BH1750
│       ├── actuators.cpp
│       └── ble_server.cpp       # GATT 服务、Notify、命令回调
├── android/                     # Android 上位机
│   └── app/src/main/java/com/sensorhub/app/
│       ├── SensorHubApp.kt      # Application：依赖与通知渠道
│       ├── protocol/            # Crc16 / Frame / FrameParser（纯逻辑，可单测）
│       ├── domain/              # Filter / AutomationRule / RuleEngine /
│       │                        #   ReconnectPolicy / ConnectionState
│       ├── ble/                 # GattSpec / BleScanner / BleConnection /
│       │                        #   BleConnectionManager / MockDataSource
│       ├── data/                # Room 三表 + Repository + DataStore
│       ├── service/             # SensorHubService（前台服务 + 业务中枢）
│       ├── ui/                  # 6 个界面 + 自定义 View
│       └── widget/              # SensorWidgetProvider（P1 小组件）
├── docs/                        # 开发文档（需求分析/设计/测试/总结）
└── README.md
```

---

## 三、硬件清单与接线

### 3.1 清单

| 器件 | 数量 | 用途 |
|---|---|---|
| ESP32-WROOM-32（USB-C） | 2 | 主控（第 2 块做多设备演示） |
| AHT20 + BMP280 模块 | 2 | 温湿度 + 气压/第二温度源 |
| BH1750 光照模块 | 2 | 光照 |
| 5mm LED | 2 | 被控执行器（PWM 调光） |
| 220Ω 1/4W 电阻 | 2 | LED 限流（**必须串**） |
| 有源蜂鸣器模块 | 2 | 告警提示音 |
| 830 孔面包板 | 2 | 免焊接连线 |
| 杜邦线（母对母 / 公对公） | 各 20 | 连线 |
| USB-C 数据线（**带数据芯**） | 2 | 烧录 + 供电 |

### 3.2 接线表（I2C 三传感器并联）

| ESP32 引脚 | 接到 | 说明 |
|---|---|---|
| 3V3 | 三个模块的 VCC | **必须 3.3V**，接 5V 会烧 AHT20/BH1750 |
| GND | 三个模块的 GND + 面包板蓝轨 | 共地 |
| GPIO21 (SDA) | AHT20 DA / BMP280 SDA / BH1750 SDA | 数据线，三根并联 |
| GPIO22 (SCL) | AHT20 CL / BMP280 SCL / BH1750 SCL | 时钟线，三根并联 |
| GPIO2 | LED 正极（负极串 220Ω 到 GND） | PWM 调光 |
| GPIO4 | 蜂鸣器 I/O | 高电平响 |

**为什么一个引脚能接三根线？** 因为 I2C 是总线：三根 SDA 并联到同一根线上，靠"地址"区分（AHT20=0x38、BMP280=0x76、BH1750=0x23），同一时刻只有一个设备应答。就像一栋楼里多户共用一个门牌号段，敲门时报名字就行。

### 3.3 面包板接线顺序（推荐）

1. 先把 ESP32 插在面包板中间槽上（两侧引脚各占一列）
2. 用 2 根线把 3V3 引到左侧红轨、GND 引到左侧蓝轨
3. 用 2 根线把右侧红轨接左侧红轨、右侧蓝轨接左侧蓝轨（两侧电源连通）
4. 三个传感器插好，VCC 接红轨、GND 接蓝轨
5. SDA/SCL 用母对母杜邦线接到 GPIO21/GPIO22
6. LED：GPIO2 → LED 正极；LED 负极 → 220Ω → 蓝轨
7. 蜂鸣器：VCC 接红轨、GND 接蓝轨、I/O 接 GPIO4

---

## 四、固件烧录

### 方式 A：PlatformIO（推荐）

```bash
cd firmware
pio run -t upload        # 编译并烧录
pio device monitor       # 打开串口监视器（115200）
```

### 方式 B：Arduino IDE

1. 安装 ESP32 开发板支持（ Boards Manager 搜 "esp32"）
2. 库管理器安装：`NimBLE-Arduino`、`Adafruit AHTX0`、`Adafruit BMP280 Library`、`BH1750`、`Adafruit Unified Sensor`
3. 把 `include/` 里的 5 个 `.h` 和 `src/` 里的 5 个 `.cpp` **复制到同一个文件夹**，把 `main.cpp` 改名为 `esp32_sensorhub.ino`
4. 开发板选 "ESP32 Dev Module"，端口选对应 COM，上传

### 烧录后自检

串口监视器会打印：

```
[I2C] 扫描总线...
  0x23        ← BH1750
  0x38        ← AHT20
  0x76        ← BMP280
[SENSOR] AHT20  : OK
[SENSOR] BMP280 : OK
[SENSOR] BH1750 : OK
[BLE] GATT 服务已启动，广播名：SensorHub-01
[DATA] T=26.5C H=50.3% P=1013.2hPa L=320 flags=0x07
```

**如果显示"一个设备都没找到"**：先查 3V3/GND 有没有接反，再查 SDA/SCL 是不是接成了 21→SCL、22→SDA（接反也能通电但通信不了）。

### 串口调试命令（不需要手机）

```
led 128      # LED 亮度调到 128
beep 500     # 蜂鸣 500ms
period 2000  # 上报周期改成 2 秒
scan         # 重新扫描 I2C
report       # 立即上报一次
status       # 查看帧计数、连接状态
```

---

## 五、Android 端运行

1. Android Studio（Hedgehog 或更新）打开 `android/` 目录
2. 首次 Sync 时会下载 Gradle 8.2 和依赖（需要联网，约 3–5 分钟）
3. 真机运行（**模拟器没有蓝牙，跑不了 BLE，但可以用模拟数据源模式**）
4. 手机 Android 8.0+（minSdk 26）

> 如果提示 Gradle wrapper 缺失，在 `android/` 下执行 `gradle wrapper` 生成即可。

### 没有硬件怎么办

打开 App → **设置** → 打开 **「模拟数据源」**。App 会用内置假数据走完**完全相同的协议帧和解析流程**，6 个界面、曲线、规则告警、小组件全都能演示和录像。

### 包名替换（可选）

现在包名是 `com.sensorhub.app`。如果想改成带自己标识的名字（如 `com.zhang.sensorhub`）：

1. Android Studio 里右键包名 → Refactor → Rename
2. 或手动替换三处：`app/build.gradle.kts` 的 `namespace` 和 `applicationId`、`AndroidManifest.xml`（若含全限定名）、把 `java/com/sensorhub/app/` 目录改名

---

## 六、协议帧格式

| 字段 | 长度 | 说明 |
|---|---|---|
| SOF | 2B | `0xAA 0x55`，帧同步头 |
| VER | 1B | `0x01` |
| TYPE | 1B | `0x01` 传感器数据 / `0x02` 心跳 / `0x03` 告警 / `0x41` ACK |
| SEQ | 1B | 0–255 循环，用于丢包检测 |
| LEN | 1B | 载荷长度 |
| PAYLOAD | NB | 见下表 |
| CRC16 | 2B | CRC-16/CCITT-FALSE（poly 0x1021，init 0xFFFF），大端 |

传感器载荷（12 字节）：

| 偏移 | 类型 | 含义 |
|---|---|---|
| 0–1 | u16 | 温度 ×10 |
| 2–3 | u16 | 湿度 ×10 |
| 4–5 | u16 | 气压 ×10 (hPa) |
| 6–7 | u16 | 光照 lux |
| 8–9 | i16 | 第二温度源 ×10（交叉校验） |
| 10 | u8 | 传感器健康位 |
| 11 | u8 | 电量占位 |

**为什么浮点要 ×10 变整数？** 省带宽、避免浮点字节序歧义、CRC 计算也更简单。

---

## 七、GATT 服务结构

| 服务 | UUID | 特征 | 属性 |
|---|---|---|---|
| 传感器（自定义） | `0000FF10-...` | DATA `...FF11` | Notify |
| | | PERIOD `...FF12` | Read + Write |
| 控制（自定义） | `0000FF20-...` | CTRL `...FF21` | Write + Read |
| Device Information | `0x180A` | 厂商/型号/固件版本 | Read |
| Battery | `0x180F` | 电量 `0x2A19` | Read + Notify |

> ⚠️ 订阅 Notify 必须**两步**：先 `setCharacteristicNotification(true)`（告诉手机本地），再往 CCCD `0x2902` 写 `ENABLE_NOTIFICATION_VALUE`（告诉对端开始推）。少第二步的表现是"连上了但永远收不到数据"。

---

## 八、运行单元测试

```bash
cd android
./gradlew test
```

覆盖：CRC16 标准向量、帧解析（分包/粘包/CRC 错/垃圾前缀）、卡尔曼滤波收敛性、规则引擎防抖与冷却、重连退避策略。

---

## 九、Git 提交粒度建议（≥15 次，真实的开发节奏）

不要一次提交所有文件。建议按下面这个顺序，每次完成一个小目标就提交一次：

```
 1. init: 建立仓库结构与 .gitignore
 2. docs: 编写选题登记表与技术方案
 3. firmware: 添加 config.h 与引脚/协议常量定义
 4. firmware: 实现 CRC16 与自定义帧组帧函数
 5. firmware: 实现流式帧解析器（支持分包粘包）
 6. firmware: 接入 AHT20 温湿度采集
 7. firmware: 接入 BMP280 气压与海拔换算
 8. firmware: 接入 BH1750 光照采集
 9. firmware: 实现双温度源交叉校验
10. firmware: 搭建 NimBLE GATT 服务与广播
11. firmware: 实现 DATA 特征 Notify 推送
12. firmware: 实现命令解析与 ACK 应答
13. firmware: 添加 LED PWM 与蜂鸣器控制
14. firmware: 添加串口调试命令
15. android: 初始化 Gradle 工程与依赖
16. android: 移植 CRC16 与协议帧模型（Kotlin）
17. android: 实现 FrameParser 与 SEQ 丢包检测
18. android: 编写协议层单元测试
19. android: 实现 BLE 扫描器（callbackFlow 封装）
20. android: 实现 GATT 连接状态机与错误码映射
21. android: 实现指数退避 + 抖动重连
22. android: 搭建 Room 数据库三张表
23. android: 实现 Repository 与 DataStore 设置
24. android: 实现设备列表页与四态 UI
25. android: 实现设备详情页与实时数据展示
26. android: 编写自定义折线图 View（环形缓冲）
27. android: 实现卡尔曼滤波与滑动平均切换
28. android: 实现规则引擎与规则管理界面
29. android: 实现前台服务与后台保活
30. android: 实现阈值告警通知与权限降级
31. android: 实现 P1 桌面小组件
32. android: 添加模拟数据源（无硬件降级方案）
33. test: 补充规则引擎与重连策略单测
34. docs: 编写开发文档与演示脚本
35. docs: 编写问答预测与总结
```

---

## 十、演示流程（3 分钟录屏用）

1. 打开 App，点「开始扫描」→ 出现 SensorHub-01
2. 点击进入详情 → 连接 → 状态变绿 → 四个数字开始跳动，曲线滚动
3. 切换滤波方式（原始 → 卡尔曼）→ 曲线明显变平滑
4. 拖动 LED 滑杆 → 面包板上的 LED 亮度实时变化
5. 点「鸣叫」→ 蜂鸣器响
6. 用手捂住 AHT20 → 温度上升 → 超过 30℃ 触发规则 → 状态栏弹出告警通知
7. 切到「历史」→ 显示刚采集的所有记录
8. 回到桌面 → 桌面小组件显示最新温湿度和光照
9. 断开连接（拔掉 ESP32 电源）→ 观察 App 显示"重连中（第 N 次）"
10. 重新上电 → 自动重连成功
