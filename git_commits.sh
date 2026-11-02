#!/bin/bash
# 按模块粒度做真实分批提交（每次提交一个完整的功能单元）
set -e
cd "/c/Users/29735/WorkBuddy/2026-10-09-11-18-20/ble-sensorhub"

# ---- 首次使用请先设置你的身份 ----
if [ -z "$(git config user.name)" ]; then
  git config user.name "sensorhub-dev"
  git config user.email "sensorhub-dev@example.com"
  echo "[!] 已使用默认身份，请用下面两条命令改成你自己的："
  echo "    git config user.name  \"你的名字\""
  echo "    git config user.email \"你的邮箱\""
fi

git init -q 2>/dev/null || true

N=0
c() {
  local msg="$1"; shift
  git add "$@" 2>/dev/null || true
  if git diff --cached --quiet 2>/dev/null; then
    echo "  [skip] $msg"
    return
  fi
  git commit -m "$msg" -q
  N=$((N+1))
  printf "  [%02d] %s\n" "$N" "$msg"
}

echo "== 阶段 0：仓库初始化 =="
c "chore: 初始化仓库，添加 .gitignore（Gradle/PlatformIO/IDE 产物）" .gitignore
c "docs: 添加项目 README（硬件清单、接线表、协议与 GATT 说明）" README.md

echo "== 阶段 1：ESP32 固件 =="
FW="firmware"
c "feat(firmware): 添加 platformio.ini 工程配置" "$FW/platformio.ini"
c "feat(firmware): 添加 config.h，集中定义引脚、UUID、周期与帧类型常量" "$FW/include/config.h"
c "feat(firmware): 定义 protocol.h 帧格式与 CRC16 接口" "$FW/include/protocol.h"
c "feat(firmware): 实现 protocol.cpp 组帧、CRC16 与流式解析" "$FW/src/protocol.cpp"
c "feat(firmware): 定义 sensors.h 传感器接口" "$FW/include/sensors.h"
c "feat(firmware): 实现 sensors.cpp 驱动 AHT20/BMP280/BH1750，含双温度源交叉校验" "$FW/src/sensors.cpp"
c "feat(firmware): 定义 actuators.h 执行器接口" "$FW/include/actuators.h"
c "feat(firmware): 实现 actuators.cpp，LEDC PWM 调光与非阻塞蜂鸣器 tick" "$FW/src/actuators.cpp"
c "feat(firmware): 定义 ble_server.h GATT 服务与命令回调接口" "$FW/include/ble_server.h"
c "feat(firmware): 实现 ble_server.cpp，NimBLE GATT Server 与 20 字节分包推送" "$FW/src/ble_server.cpp"
c "feat(firmware): 实现 main.cpp 非阻塞主循环与串口 CLI" "$FW/src/main.cpp"

echo "== 阶段 2：Android 工程骨架 =="
c "build(android): 搭建 Gradle 工程骨架（AGP 9 + KSP + wrapper）" \
  android/build.gradle.kts android/settings.gradle.kts android/gradle.properties \
  android/gradle/wrapper/gradle-wrapper.properties android/gradlew android/gradlew.bat
c "feat(android): 配置 app 模块依赖（Room/Coroutines/Lifecycle/Work/DataStore）" \
  android/app/build.gradle.kts

echo "== 阶段 3：协议层 =="
P="android/app/src/main/java/com/sensorhub/app/protocol"
c "feat(protocol): 定义帧常量、Frame 与 SensorData 数据模型" "$P/Frame.kt"
c "feat(protocol): 实现 CRC-16/CCITT-FALSE（多项式 0x1021，初值 0xFFFF）" "$P/Crc16.kt"
c "test(protocol): 补充 CRC16 单元测试（已知向量、单比特翻转必检出）" \
  android/app/src/test/java/com/sensorhub/app/Crc16Test.kt
c "feat(protocol): 实现流式帧解析器与滑动窗口（出错只丢 1 字节）" "$P/FrameParser.kt"
c "test(protocol): 补充帧解析器测试（半帧分片、一次两帧半、错帧恢复）" \
  android/app/src/test/java/com/sensorhub/app/FrameParserTest.kt

echo "== 阶段 4：领域层（纯 Kotlin，无 Android 依赖） =="
D="android/app/src/main/java/com/sensorhub/app/domain"
c "feat(domain): 实现滑动平均与一维卡尔曼滤波" "$D/Filter.kt"
c "test(domain): 补充卡尔曼滤波测试（常值收敛、阶跃响应、增益单调收敛）" \
  android/app/src/test/java/com/sensorhub/app/KalmanFilterTest.kt
c "feat(domain): 定义自动化规则模型与通道/比较符枚举" "$D/AutomationRule.kt"
c "feat(domain): 实现规则引擎（连续命中防抖 + 5 秒冷却 + 滞回比较）" "$D/RuleEngine.kt"
c "test(domain): 补充规则引擎测试（单次不触发、冷却期、滞回死区）" \
  android/app/src/test/java/com/sensorhub/app/RuleEngineTest.kt
c "feat(domain): 实现指数退避重连策略与 BLE 错误码释义" "$D/ReconnectPolicy.kt"
c "test(domain): 补充重连策略测试（指数增长、抖动范围、上限封顶）" \
  android/app/src/test/java/com/sensorhub/app/ReconnectPolicyTest.kt

echo "== 阶段 5：BLE 通信层 =="
B="android/app/src/main/java/com/sensorhub/app/ble"
c "feat(ble): 定义 GATT 服务与特征 UUID 常量、BleDevice 模型" "$B/GattSpec.kt"
c "feat(ble): 实现 BLE 扫描器，用 callbackFlow 包装扫描回调" "$B/BleScanner.kt"
c "feat(ble): 实现 GATT 连接、服务发现与 CCCD 订阅两步走" "$B/BleConnection.kt"
c "feat(ble): 实现多设备连接管理器（ConcurrentHashMap 并发持有）" "$B/BleConnectionManager.kt"
c "feat(ble): 实现模拟数据源，生成真实二进制帧走同一解析器" "$B/MockDataSource.kt"

echo "== 阶段 6：数据持久层 =="
DB="android/app/src/main/java/com/sensorhub/app/data"
c "feat(data): 定义 Room 三表实体 readings / rules / devices" "$DB/db/Entities.kt"
c "feat(data): 定义 DAO 接口，返回 Flow 实现自动刷新" "$DB/db/Daos.kt"
c "feat(data): 实现 AppDatabase 单例" "$DB/db/AppDatabase.kt"
c "feat(data): 实现 SettingsRepository，用 DataStore 管理偏好设置" "$DB/SettingsRepository.kt"
c "feat(data): 实现 SensorRepository，封装读数入库与统计查询" "$DB/SensorRepository.kt"

echo "== 阶段 7：UI 层 =="
U="android/app/src/main/java/com/sensorhub/app/ui"
c "feat(ui): 定义 UiState 密封接口，统一驱动加载/空/错误/成功四态" "$U/common/UiState.kt"
c "feat(ui): 实现 MainActivity 与底部导航容器" "$U/MainActivity.kt"
c "feat(ui): 实现设备列表页（扫描、RSSI 分级、空态/错误态）" \
  "$U/devices/DeviceListFragment.kt" "$U/devices/DeviceListViewModel.kt" "$U/devices/DeviceAdapter.kt"
c "feat(ui): 实现设备详情页（数值卡片、反向控制、链路质量）" \
  "$U/detail/DeviceDetailFragment.kt" "$U/detail/DeviceDetailViewModel.kt"
c "feat(ui): 实现实时曲线自定义 View，环形缓冲 + Canvas 自绘" "$U/widget/RealtimeChartView.kt"
c "feat(ui): 实现自动化规则页" \
  "$U/rules/RulesFragment.kt" "$U/rules/RulesViewModel.kt" "$U/rules/RuleAdapter.kt"
c "feat(ui): 实现历史记录页" \
  "$U/history/HistoryFragment.kt" "$U/history/HistoryViewModel.kt" "$U/history/HistoryAdapter.kt"
c "feat(ui): 实现设置页，含三级降级开关与手动 MAC 直连" \
  "$U/settings/SettingsFragment.kt" "$U/settings/SettingsViewModel.kt"

echo "== 阶段 8：服务、小组件与后台任务 =="
J="android/app/src/main/java/com/sensorhub/app"
c "feat(service): 实现通知渠道与告警通知封装" "$J/util/NotificationHelper.kt"
c "feat(service): 实现前台服务，维持 BLE 长连接并转发数据帧" "$J/service/SensorHubService.kt"
c "feat(widget): 实现伴生模块 P1 桌面小组件（RemoteViews + 主动推送刷新）" "$J/widget/SensorWidgetProvider.kt"
c "feat(worker): 实现 WorkManager 定期裁剪数据库，避免无限膨胀" "$J/worker/DatabaseCleanupWorker.kt"
c "feat(app): 添加 Application 类，初始化通知渠道与 WorkManager" "$J/SensorHubApp.kt"

echo "== 阶段 9：资源文件 =="
R="android/app/src/main/res"
c "feat(res): 添加 color / string / theme 资源" \
  "$R/values/colors.xml" "$R/values/strings.xml" "$R/values/themes.xml"
c "feat(res): 添加 drawable 图形资源" \
  "$R/drawable/bg_card.xml" "$R/drawable/bg_status_dot.xml" "$R/drawable/ic_bluetooth.xml" \
  "$R/drawable/ic_refresh.xml" "$R/drawable/ic_warning.xml" "$R/drawable/ic_launcher_foreground.xml"
c "feat(res): 添加布局文件（主界面、列表、详情、规则、历史、设置、小组件）" \
  "$R/layout/activity_main.xml" "$R/layout/fragment_device_list.xml" \
  "$R/layout/fragment_device_detail.xml" "$R/layout/fragment_rules.xml" \
  "$R/layout/fragment_history.xml" "$R/layout/fragment_settings.xml"
c "feat(res): 添加列表项布局与规则对话框" \
  "$R/layout/item_device.xml" "$R/layout/item_history.xml" "$R/layout/item_rule.xml" "$R/layout/dialog_rule.xml"
c "feat(res): 添加小组件布局与元数据" "$R/layout/widget_sensor.xml" "$R/xml/sensor_widget_info.xml"
c "feat(res): 添加导航图、底部菜单与应用图标" \
  "$R/navigation/nav_graph.xml" "$R/menu/bottom_nav_menu.xml" "$R/mipmap-anydpi-v26/ic_launcher.xml"

echo "== 阶段 10：清单文件 =="
c "feat(android): 配置 AndroidManifest，声明蓝牙/通知权限与四大组件" \
  android/app/src/main/AndroidManifest.xml

echo "== 阶段 11：文档 =="
c "docs: 添加手把手使用指南（装软件、接线、烧录、排查、改代码对照）" \
  docs/00-手把手使用指南.md
c "docs: 添加开发文档第 1-2 章（需求分析、功能结构）" \
  docs/01-需求分析.md docs/02-功能结构.md
c "docs: 添加开发文档第 3-4 章（系统设计、数据库与接口设计）" \
  docs/03-系统设计.md docs/04-数据库与接口设计.md
c "docs: 添加开发文档第 5-6 章（关键实现、伴生模块设计）" \
  docs/05-关键实现.md docs/06-伴生模块设计.md
c "docs: 添加开发文档第 7-8 章（测试与问题记录、总结）" \
  docs/07-测试与问题记录.md docs/08-总结.md
c "docs: 添加答辩 PPT、3 分钟录屏分镜脚本与问答预测题" \
  docs/答辩PPT-SensorHub.pptx docs/make_ppt.py \
  docs/录屏分镜脚本-3分钟.md docs/答辩问答预测题与标准答案.md
c "docs: 添加实测运行截图（主界面/设置/设备列表/详情/规则/历史）" \
  docs/screenshots/01-main.png docs/screenshots/02-settings.png \
  docs/screenshots/03-devices.png docs/screenshots/04-detail.png \
  docs/screenshots/05-rules.png docs/screenshots/06-history.png

echo ""
echo "完成，共 $N 次提交"
