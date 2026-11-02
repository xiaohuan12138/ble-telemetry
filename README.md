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

## 九、Git 提交记录（已完成 65 次真实提交）

课程要求 ≥15 次，本仓库已有 **65 次**，按依赖顺序组织，能看出功能是怎么一步步长出来的：

```
仓库初始化(2) → ESP32 固件(11) → Android 工程骨架(2) → 协议层(5)
→ 领域层(7) → BLE 层(5) → 数据层(5) → UI 层(8)
→ 服务/小组件/Worker(5) → 资源(6) → Manifest(1) → 文档(7) → 收尾(1)
```

查看完整清单：

```bash
git log --oneline --reverse | cat -n
```

每条提交的 hash、说明、所属阶段，见 **[docs/09-Git提交清单与GitHub上传指南.md](docs/09-Git提交清单与GitHub上传指南.md)**。

### ⚠️ 推送到 GitHub 之前必做

当前提交用的是**占位身份** `Student <student@example.com>`，必须改成你自己的：

```bash
git config user.name  "你的名字"
git config user.email "你的GitHub邮箱"

git filter-branch --env-filter '
    export GIT_AUTHOR_NAME="你的名字"
    export GIT_AUTHOR_EMAIL="你的GitHub邮箱"
    export GIT_COMMITTER_NAME="你的名字"
    export GIT_COMMITTER_EMAIL="你的GitHub邮箱"
' --tag-name-filter cat -- --all
```

提交类型前缀规范：`feat` / `fix` / `test` / `docs` / `refactor` / `build` / `chore`。

**后续继续提交**（答辩前不要停，但必须是真实改动）：

```bash
git add <具体文件>
git commit -m "类型(模块): 简短描述"
git push
```

> 伪造提交记录被查出来是 0 分。宁可提交数少，也不要造假。

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

---

## 十一、文档索引

| 文件 | 内容 | 什么时候看 |
|------|------|-----------|
| **README.md** | 本文件：硬件清单、接线、烧录、协议、GATT | 第一次上手 |
| `docs/00-手把手使用指南.md` | 装什么软件、Android Studio 逐步点击、连不上怎么排查 | 不会操作时 |
| `docs/01-需求分析.md` | 背景、功能/非功能需求、运行环境 | 写文档第 1 章 |
| `docs/02-功能结构.md` | 功能树、界面清单、数据流、权限 | 写文档第 2 章 |
| `docs/03-系统设计.md` | 架构、分层、关键类、状态机、线程模型、三级降级 | 写文档第 3 章 |
| `docs/04-数据库与接口设计.md` | E-R、表结构、DAO、GATT 约定、DataStore | 写文档第 4 章 |
| `docs/05-关键实现.md` | 协议帧、CRC16、流式解析、重连、卡尔曼、规则引擎 | 答辩前必读 |
| `docs/06-伴生模块设计.md` | RemoteViews 原理、刷新策略、技术取舍 | 写文档第 6 章 |
| `docs/07-测试与问题记录.md` | 单元测试、运行记录、17 个真实问题、已知局限 | 写文档第 7 章 |
| `docs/08-总结.md` | 完成情况、逐条对照、技术亮点、收获 | 写文档第 8 章 |
| `docs/09-Git提交清单与GitHub上传指南.md` | 65 次提交清单、改身份、建仓库、推送 | **推送前必读** |
| `docs/答辩PPT-SensorHub.pptx` | 10 页答辩演示文稿 | 答辩 |
| `docs/录屏分镜脚本-3分钟.md` | 9 镜头逐秒时间轴 + 旁白 + 翻车点 | 录演示视频 |
| `docs/答辩问答预测题与标准答案.md` | 30 题分 5 类 + 高频速查表 | 答辩前突击 |
| `docs/screenshots/` | 6 张实测截图 | 做 PPT / 写文档 |
| `docs/make_ppt.py` | PPT 生成脚本（python-pptx） | 想改 PPT 时 |

---

## 十二、当前状态

| 项 | 状态 |
|----|------|
| Android 源码 | ✅ 43 个 Kotlin 文件，约 5400 行 |
| ESP32 固件 | ✅ 10 个 C/C++ 文件，约 1180 行 |
| 编译 | ✅ BUILD SUCCESSFUL，APK 9.4 MB |
| 运行验证 | ✅ 模拟器实测跑通，6 张截图 |
| 单元测试 | ✅ 5 个测试类覆盖核心算法 |
| 开发文档 | ✅ 8 章齐全 |
| 答辩材料 | ✅ PPT + 分镜 + 问答预测 |
| Git 提交 | ✅ 65 次（待改身份后推送） |
| 真实硬件联调 | ⏳ 硬件已采购，尚未接线 |
