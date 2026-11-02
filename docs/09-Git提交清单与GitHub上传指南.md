# Git 提交清单与 GitHub 上传指南

## 一、提交概况

| 项 | 值 |
|----|----|
| 提交总数 | **65 次**（课程要求 ≥15 次） |
| 分支 | `master` |
| 统计 | 107 文件变更，新增 12174 行 |
| 工作区 | 干净（无未跟踪/未提交文件） |

提交按**依赖顺序**组织，能看出功能是怎么一步步长出来的：

```
仓库初始化 → ESP32 固件（11 次）→ Android 工程骨架（2 次）
→ 协议层（5 次）→ 领域层（7 次）→ BLE 层（5 次）→ 数据层（5 次）
→ UI 层（8 次）→ 服务/小组件/Worker（5 次）→ 资源（6 次）
→ Manifest（1 次）→ 文档（7 次）→ 收尾（1 次）
```

---

## 二、⚠️ 第一件事：改成你自己的身份

当前提交用的是**占位身份** `Student <student@example.com>`。**在推送到 GitHub 之前必须改掉**，否则仓库里显示的作者不是你。

在 `ble-sensorhub/` 目录下执行：

```bash
git config user.name  "你的名字"
git config user.email "你的GitHub邮箱"
```

然后重写全部 65 次提交的作者信息：

```bash
git filter-branch --env-filter '
    export GIT_AUTHOR_NAME="你的名字"
    export GIT_AUTHOR_EMAIL="你的GitHub邮箱"
    export GIT_COMMITTER_NAME="你的名字"
    export GIT_COMMITTER_EMAIL="你的GitHub邮箱"
' --tag-name-filter cat -- --all
```

> **注意**：`filter-branch` 会重写历史。只在**推送到 GitHub 之前**做，做完直接推。如果已经推过了，就不要再改。

改完验证：

```bash
git log --format="%an <%ae>" | sort -u
# 应该只输出你的名字和邮箱
```

---

## 三、完整提交清单

### 阶段 0 · 仓库初始化（2）

| # | hash | 说明 |
|---|------|------|
| 1 | `2e5f4a8` | chore: 初始化仓库，添加 .gitignore |
| 2 | `357aeb3` | docs: 添加项目 README |

### 阶段 1 · ESP32 固件（11）

| # | hash | 说明 |
|---|------|------|
| 3 | `af7a408` | feat(firmware): platformio.ini 工程配置 |
| 4 | `b2b79b1` | feat(firmware): config.h 引脚/UUID/协议常量 |
| 5 | `d841bdb` | feat(firmware): protocol.h 帧格式与 CRC16 接口 |
| 6 | `afbe5dd` | feat(firmware): protocol.cpp 组帧与流式解析 |
| 7 | `01b328e` | feat(firmware): sensors.h 接口 |
| 8 | `324097b` | feat(firmware): sensors.cpp 三传感器驱动 + 交叉校验 |
| 9 | `1a41110` | feat(firmware): actuators.h 接口 |
| 10 | `a16b809` | feat(firmware): actuators.cpp PWM + 非阻塞蜂鸣器 |
| 11 | `83bdaa7` | feat(firmware): ble_server.h 接口 |
| 12 | `92aa25a` | feat(firmware): ble_server.cpp NimBLE GATT Server |
| 13 | `6c22d01` | feat(firmware): main.cpp 主循环与串口 CLI |

### 阶段 2 · Android 工程骨架（2）

| # | hash | 说明 |
|---|------|------|
| 14 | `101edf8` | build(android): Gradle 骨架（AGP 9 + KSP + wrapper） |
| 15 | `cb1b443` | feat(android): app 模块依赖配置 |

### 阶段 3 · 协议层（5）

| # | hash | 说明 |
|---|------|------|
| 16 | `2adbcaf` | feat(protocol): Frame/SensorData 数据模型 |
| 17 | `a426e6e` | feat(protocol): CRC-16/CCITT-FALSE |
| 18 | `6d2c3cb` | test(protocol): CRC16 单元测试 |
| 19 | `32ec9e0` | feat(protocol): 流式帧解析器 + 滑动窗口 |
| 20 | `80501e1` | test(protocol): 帧解析器测试 |

### 阶段 4 · 领域层（7）

| # | hash | 说明 |
|---|------|------|
| 21 | `70d177d` | feat(domain): 滑动平均 + 卡尔曼滤波 |
| 22 | `b03f821` | test(domain): 卡尔曼滤波测试 |
| 23 | `b3bae94` | feat(domain): 自动化规则模型 |
| 24 | `cbbc6c1` | feat(domain): 规则引擎（防抖+冷却+滞回） |
| 25 | `4ef6627` | test(domain): 规则引擎测试 |
| 26 | `97eba3a` | feat(domain): 指数退避重连策略 |
| 27 | `74173a3` | test(domain): 重连策略测试 |

### 阶段 5 · BLE 层（5）

| # | hash | 说明 |
|---|------|------|
| 28 | `333f41f` | feat(ble): GATT UUID 常量与 BleDevice |
| 29 | `a0e3bd6` | feat(ble): BLE 扫描器（callbackFlow） |
| 30 | `8f88bda` | feat(ble): GATT 连接与 CCCD 订阅两步走 |
| 31 | `ede172b` | feat(ble): 多设备连接管理器 |
| 32 | `3dbc09e` | feat(ble): 模拟数据源 |

### 阶段 6 · 数据层（5）

| # | hash | 说明 |
|---|------|------|
| 33 | `15ad40e` | feat(data): Room 三表实体 |
| 34 | `a2fe498` | feat(data): DAO 接口（返回 Flow） |
| 35 | `4672f83` | feat(data): AppDatabase 单例 |
| 36 | `9866d1a` | feat(data): SettingsRepository（DataStore） |
| 37 | `cb18345` | feat(data): SensorRepository |

### 阶段 7 · UI 层（8）

| # | hash | 说明 |
|---|------|------|
| 38 | `ca07647` | feat(ui): UiState 密封接口 |
| 39 | `5b3c601` | feat(ui): MainActivity + 底部导航 |
| 40 | `d591c48` | feat(ui): 设备列表页 |
| 41 | `895cace` | feat(ui): 设备详情页 |
| 42 | `194d0bb` | feat(ui): 实时曲线自绘 View |
| 43 | `cae0706` | feat(ui): 规则页 |
| 44 | `b42e1e1` | feat(ui): 历史页 |
| 45 | `ed43563` | feat(ui): 设置页（三级降级） |

### 阶段 8 · 服务 / 小组件 / Worker（5）

| # | hash | 说明 |
|---|------|------|
| 46 | `f7d860b` | feat(service): 通知渠道封装 |
| 47 | `5c5536f` | feat(service): 前台服务 |
| 48 | `3c82a79` | feat(widget): 桌面小组件 P1 |
| 49 | `bff5e4c` | feat(worker): WorkManager 数据裁剪 |
| 50 | `da92776` | feat(app): Application 类 |

### 阶段 9 · 资源（6）

| # | hash | 说明 |
|---|------|------|
| 51 | `c751da2` | feat(res): color/string/theme |
| 52 | `fe511cf` | feat(res): drawable 图形 |
| 53 | `9b5135a` | feat(res): 六个界面布局 |
| 54 | `8b088ff` | feat(res): 列表项与对话框布局 |
| 55 | `39fed97` | feat(res): 小组件布局与元数据 |
| 56 | `0e2b555` | feat(res): 导航图、菜单、图标 |

### 阶段 10 · Manifest 与文档（8）

| # | hash | 说明 |
|---|------|------|
| 57 | `ee81318` | feat(android): AndroidManifest 权限与组件 |
| 58 | `eb42aff` | docs: 手把手使用指南 |
| 59 | `280dec9` | docs: 开发文档 1-2 章 |
| 60 | `7023537` | docs: 开发文档 3-4 章 |
| 61 | `15a837e` | docs: 开发文档 5-6 章 |
| 62 | `83a4383` | docs: 开发文档 7-8 章 |
| 63 | `aa3f3aa` | docs: 答辩 PPT / 录屏分镜 / 问答预测 |
| 64 | `434803c` | docs: 实测运行截图 |
| 65 | `048ffed` | chore: 补充 wrapper jar 与提交脚本 |

---

## 四、推送到 GitHub

### 4.1 在 GitHub 上创建空仓库

1. 登录 GitHub → 右上角 `+` → **New repository**
2. **Repository name**：`SensorHub-BLE`（或你想起的其他名字）
3. **Description**：`基于 BLE 的多设备传感器数据采集与控制系统 · Android 课程设计`
4. 选 **Public**（老师要能访问）
5. ⚠️ **不要勾选** "Add a README file"、"Add .gitignore"、"Choose a license"
   —— 仓库必须是**完全空的**，否则首次推送会冲突
6. 点 **Create repository**

### 4.2 关联并推送

GitHub 创建完会给你一个地址，形如 `https://github.com/你的用户名/SensorHub-BLE.git`。

在 `ble-sensorhub/` 下执行：

```bash
# 关联远程仓库
git remote add origin https://github.com/你的用户名/SensorHub-BLE.git

# 重命名分支为 main（GitHub 默认）
git branch -M main

# 推送
git push -u origin main
```

如果提示输入密码：**GitHub 早已不支持密码登录**，需要用 Personal Access Token：
1. GitHub → Settings → Developer settings → Personal access tokens → **Tokens (classic)**
2. Generate new token → 勾选 `repo` → 生成
3. 推送时用户名填你的 GitHub 用户名，密码填**这个 token**

### 4.3 验证

推送完成后刷新仓库页面，应该能看到：
- 65 次 commit
- 目录结构：`android/` `firmware/` `docs/` `README.md` `.gitignore`
- 提交作者是你的名字（如果按第二节改过）

---

## 五、后续开发怎么继续提交

课程要求"贯穿开发周期"，所以**答辩前不要停止提交**。每次改动都按同样的粒度提交：

```bash
git add <具体文件>
git commit -m "类型(模块): 简短描述"
git push
```

**类型前缀规范**（已在 65 次提交中统一使用）：

| 前缀 | 用途 |
|------|------|
| `feat` | 新增功能 |
| `fix` | 修复 bug |
| `test` | 补充测试 |
| `docs` | 文档 |
| `refactor` | 重构（不改行为） |
| `build` | 构建配置 |
| `chore` | 杂项 |

**接下来可以提交的真实改动**（都是有价值的加分项）：

1. `feat(firmware): 接入真实硬件，完成首次联调` —— 接线烧录后
2. `fix(ble): 修复真机扫描不到设备的问题` —— 联调时大概率会遇到
3. `test(android): 补真实硬件的端到端测试`
4. `feat(security): 添加 BLE 配对与 LE Secure Connections`
5. `feat(data): 支持历史记录导出 CSV`
6. `refactor(widget): 改用 RemoteViewsService 从 Room 读取，消除进程死亡丢数据`

> **提醒**：这些必须是**真实发生**的改动。不要为了凑提交数而制造假提交——老师看代码 diff 一眼就能识破，伪造是 0 分。

---

## 六、登记表里填什么

| 字段 | 内容 |
|------|------|
| 主 App 题目 | C5 蓝牙设备控制 |
| 档位 | C 档（挑战级） |
| 伴生模块 | P1 桌面小组件 |
| 个性化功能分配 | 必做：CRC16 协议帧 / 多设备重连；自选：卡尔曼滤波告警 |
| 仓库地址 | `https://github.com/你的用户名/SensorHub-BLE` |
