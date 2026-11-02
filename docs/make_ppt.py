# -*- coding: utf-8 -*-
"""生成 SensorHub 课程设计答辩 PPT"""
import os
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR

BASE = r"C:\Users\29735\WorkBuddy\2026-10-09-11-18-20\ble-sensorhub"
SHOT = os.path.join(BASE, "docs", "screenshots")
OUT = os.path.join(BASE, "docs", "答辩PPT-SensorHub.pptx")

# ---------- 配色（浅色主题） ----------
C_PRIMARY = RGBColor(0x1E, 0x3A, 0x5F)   # 深蓝
C_ACCENT = RGBColor(0x2E, 0x7D, 0xD1)    # 亮蓝
C_TEXT = RGBColor(0x22, 0x22, 0x22)      # 正文黑
C_GRAY = RGBColor(0x66, 0x66, 0x66)      # 次要灰
C_BG_CARD = RGBColor(0xF4, 0xF7, 0xFA)   # 卡片底色
C_WHITE = RGBColor(0xFF, 0xFF, 0xFF)
C_DANGER = RGBColor(0xD9, 0x3A, 0x3A)
C_OK = RGBColor(0x2E, 0x9E, 0x5B)

prs = Presentation()
prs.slide_width = Inches(13.333)
prs.slide_height = Inches(7.5)
BLANK = prs.slide_layouts[6]

SW = prs.slide_width
SH = prs.slide_height


def add_slide():
    return prs.slides.add_slide(BLANK)


def rect(slide, l, t, w, h, fill=None, line=None, line_w=1):
    from pptx.enum.shapes import MSO_SHAPE
    sh = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, l, t, w, h)
    if fill is None:
        sh.fill.background()
    else:
        sh.fill.solid()
        sh.fill.fore_color.rgb = fill
    if line is None:
        sh.line.fill.background()
    else:
        sh.line.color.rgb = line
        sh.line.width = Pt(line_w)
    sh.shadow.inherit = False
    return sh


def textbox(slide, l, t, w, h, text, size=18, color=C_TEXT, bold=False,
            align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.TOP, spacing=1.0, font="微软雅黑"):
    tb = slide.shapes.add_textbox(l, t, w, h)
    tf = tb.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = anchor
    tf.margin_left = 0
    tf.margin_right = 0
    tf.margin_top = 0
    tf.margin_bottom = 0
    lines = text.split("\n")
    for i, ln in enumerate(lines):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align
        p.line_spacing = spacing
        r = p.add_run()
        r.text = ln
        r.font.size = Pt(size)
        r.font.bold = bold
        r.font.color.rgb = color
        r.font.name = font
    return tb


def header(slide, title, sub=None):
    """统一页眉：左侧蓝色竖条 + 标题"""
    rect(slide, 0, 0, Inches(0.13), SH, fill=C_PRIMARY)
    textbox(slide, Inches(0.55), Inches(0.35), Inches(11), Inches(0.6),
            title, size=30, color=C_PRIMARY, bold=True)
    if sub:
        textbox(slide, Inches(0.6), Inches(1.02), Inches(11), Inches(0.4),
                sub, size=14, color=C_GRAY)
    # 页脚
    textbox(slide, Inches(0.6), SH - Inches(0.42), Inches(11), Inches(0.3),
            "SensorHub · 蓝牙传感器中枢 | C5 蓝牙设备控制 + P1 桌面小组件",
            size=10, color=C_GRAY)


def card(slide, l, t, w, h, title, body, size=13, accent=C_ACCENT, title_size=15):
    rect(slide, l, t, w, h, fill=C_BG_CARD)
    rect(slide, l, t, Inches(0.06), h, fill=accent)
    textbox(slide, l + Inches(0.22), t + Inches(0.14), w - Inches(0.4), Inches(0.35),
            title, size=title_size, color=C_PRIMARY, bold=True)
    textbox(slide, l + Inches(0.22), t + Inches(0.55), w - Inches(0.4), h - Inches(0.7),
            body, size=size, color=C_TEXT, spacing=1.25)


def table(slide, l, t, w, rows, col_widths=None, size=12, header_fill=C_PRIMARY):
    """简易表格：用 shape 画"""
    n_rows = len(rows)
    n_cols = len(rows[0])
    row_h = Inches(0.42)
    total_h = row_h * n_rows
    tbl_shape = slide.shapes.add_table(n_rows, n_cols, l, t, w, total_h)
    tbl = tbl_shape.table
    if col_widths:
        total = sum(col_widths)
        for i, cw in enumerate(col_widths):
            tbl.columns[i].width = Emu(int(w * cw / total))
    for ri, row in enumerate(rows):
        tbl.rows[ri].height = row_h
        for ci, val in enumerate(row):
            cell = tbl.cell(ri, ci)
            cell.text = str(val)
            cell.margin_left = Inches(0.08)
            cell.margin_top = Inches(0.03)
            cell.margin_bottom = Inches(0.03)
            p = cell.text_frame.paragraphs[0]
            p.alignment = PP_ALIGN.LEFT
            for r in p.runs:
                r.font.size = Pt(size)
                r.font.name = "微软雅黑"
                if ri == 0:
                    r.font.bold = True
                    r.font.color.rgb = C_WHITE
                else:
                    r.font.color.rgb = C_TEXT
            cell.fill.solid()
            if ri == 0:
                cell.fill.fore_color.rgb = header_fill
            else:
                cell.fill.fore_color.rgb = C_WHITE if ri % 2 else C_BG_CARD
    return tbl_shape


def code_block(slide, l, t, w, h, code, size=11.5):
    rect(slide, l, t, w, h, fill=RGBColor(0xF7, 0xF5, 0xF0), line=RGBColor(0xDD, 0xDD, 0xDD))
    tb = slide.shapes.add_textbox(l + Inches(0.18), t + Inches(0.14), w - Inches(0.36), h - Inches(0.28))
    tf = tb.text_frame
    tf.word_wrap = True
    for i, ln in enumerate(code.split("\n")):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.line_spacing = 1.15
        r = p.add_run()
        r.text = ln
        r.font.size = Pt(size)
        r.font.name = "Consolas"
        r.font.color.rgb = RGBColor(0x1A, 0x1A, 0x1A)
    return tb


# ============================================================
# P1 封面
# ============================================================
s = add_slide()
rect(s, 0, 0, SW, SH, fill=RGBColor(0xF7, 0xF9, 0xFC))
rect(s, 0, 0, SW, Inches(0.16), fill=C_PRIMARY)
rect(s, Inches(0.9), Inches(2.05), Inches(0.09), Inches(1.5), fill=C_ACCENT)

textbox(s, Inches(1.25), Inches(2.0), Inches(11), Inches(0.8),
        "SensorHub 蓝牙传感器中枢", size=44, color=C_PRIMARY, bold=True)
textbox(s, Inches(1.25), Inches(2.95), Inches(11), Inches(0.5),
        "基于 BLE 的多设备数据采集与控制系统", size=20, color=C_GRAY)

rect(s, Inches(1.25), Inches(3.75), Inches(10.8), Inches(0.02), fill=RGBColor(0xDD, 0xE3, 0xEA))

info = [
    ("主 App 题目", "C5 蓝牙设备控制（C 档 · 挑战级）"),
    ("伴生模块", "P1 桌面小组件"),
    ("技术栈", "Kotlin + 协程 + Room + BLE / ESP32 + NimBLE"),
]
y = Inches(4.0)
for k, v in info:
    textbox(s, Inches(1.25), y, Inches(1.6), Inches(0.35), k, size=14, color=C_ACCENT, bold=True)
    textbox(s, Inches(2.95), y, Inches(9), Inches(0.35), v, size=14, color=C_TEXT)
    y += Inches(0.45)

textbox(s, Inches(1.25), SH - Inches(1.05), Inches(11), Inches(0.35),
        "《Android 移动终端应用开发技术》 + 《移动互联网应用开发实战》", size=13, color=C_GRAY)
textbox(s, Inches(1.25), SH - Inches(0.72), Inches(11), Inches(0.35),
        "学号：__________    姓名：__________", size=13, color=C_GRAY)

# ============================================================
# P2 项目概述
# ============================================================
s = add_slide()
header(s, "一、项目概述", "做什么、为什么要做、做到什么程度")

card(s, Inches(0.6), Inches(1.55), Inches(5.9), Inches(2.05),
     "要解决的问题",
     "BLE 开发有两个门槛：\n"
     "① 协议栈层次多（GATT 三层 + 异步回调）\n"
     "② 调试依赖硬件，采购周期卡住学习\n\n"
     "本项目构建「采集 + 控制」完整闭环，并刻意加入\n"
     "自定义协议、断线重连、滤波、自动化规则，\n"
     "超出「点灯 demo」的复杂度。")

card(s, Inches(6.85), Inches(1.55), Inches(5.9), Inches(2.05),
     "产出规模",
     "Android 端：43 个 Kotlin 文件 · 约 5400 行\n"
     "ESP32 固件：10 个 C/C++ 文件 · 约 1180 行\n"
     "单元测试：5 个测试类覆盖核心算法\n"
     "界面数量：6 个（课程要求 ≥ 4）\n"
     "运行状态：模拟器实测跑通，APK 9.4 MB", accent=C_OK)

table(s, Inches(0.6), Inches(3.95), Inches(12.15), [
    ["课程最低复杂度标准", "本项目实现", "位置"],
    ["≥4 界面，含列表→详情", "6 个界面，列表→详情 Navigation + Bundle 传参", "ui/ 五个 Fragment"],
    ["数据持久化", "Room 三表（readings / rules / devices）+ DataStore", "data/"],
    ["一项硬能力", "BLE 通信（系统级 API，非三方 SDK）", "ble/"],
    ["协程 + ViewModel", "全项目无裸线程；5 个 ViewModel + StateFlow", "全局"],
    ["权限降级", "三级降级：蓝牙 / 通知 / 无硬件", "见架构章"],
    ["自定义业务逻辑模块", "CRC16 协议帧、卡尔曼滤波、规则引擎、重连策略", "protocol/ domain/"],
    ["加载 / 空 / 错误态", "UiState 密封接口统一驱动三套视图", "ui/common/"],
], col_widths=[3.2, 6.2, 2.75], size=11.5)

# ============================================================
# P3 系统架构
# ============================================================
s = add_slide()
header(s, "二、系统架构", "两端结构、分层设计、数据流向")

rect(s, Inches(0.6), Inches(1.5), Inches(5.2), Inches(2.45), fill=C_BG_CARD)
textbox(s, Inches(0.8), Inches(1.62), Inches(4.8), Inches(0.35),
        "ESP32 下位机", size=15, color=C_PRIMARY, bold=True)
code_block(s, Inches(0.8), Inches(2.05), Inches(4.8), Inches(1.75),
           "传感器层  AHT20 / BMP280 / BH1750\n"
           "    ↓ I2C (SCL=22, SDA=21)\n"
           "协议层    组帧 / CRC16 / 解析\n"
           "    ↓\n"
           "BLE Server (NimBLE)  Notify 分包 20B\n"
           "    ↓\n"
           "执行器    LED(PWM) / 有源蜂鸣器", size=11)

rect(s, Inches(6.15), Inches(1.5), Inches(6.6), Inches(2.45), fill=C_BG_CARD)
textbox(s, Inches(6.35), Inches(1.62), Inches(6.2), Inches(0.35),
        "Android 上位机（MVVM + Repository，单向数据流）", size=15, color=C_PRIMARY, bold=True)
code_block(s, Inches(6.35), Inches(2.05), Inches(6.2), Inches(1.75),
           "UI 层        Fragment × 5 + Adapter × 3 + 自绘曲线\n"
           "    ↓\n"
           "ViewModel    StateFlow<UiState>，viewModelScope 起协程\n"
           "    ↓\n"
           "Repository / Domain   卡尔曼 · 规则引擎 · 重连策略\n"
           "    ↓                              ↓\n"
           "BLE 层（Scanner/Connection）    Room / DataStore", size=11)

rect(s, Inches(0.6), Inches(4.15), Inches(12.15), Inches(0.55), fill=RGBColor(0xE8, 0xF1, 0xFA))
textbox(s, Inches(0.8), Inches(4.26), Inches(11.8), Inches(0.4),
        "核心约定：两端共用同一套协议定义 —— firmware/include/config.h 与 Android protocol/Frame.kt 常量必须严格一致，改一端必须同步改另一端。",
        size=12.5, color=C_PRIMARY)

card(s, Inches(0.6), Inches(4.95), Inches(3.85), Inches(1.75),
     "依赖方向", "UI → ViewModel → Repository → {BLE / Room / Domain}\n下层绝不反向依赖上层。", size=12)
card(s, Inches(4.72), Inches(4.95), Inches(3.85), Inches(1.75),
     "线程模型", "BLE 回调 → Binder 线程\n解析 → Default\n数据库 → IO\nUI → 主线程", size=12, accent=C_OK)
card(s, Inches(8.85), Inches(4.95), Inches(3.9), Inches(1.75),
     "可测试性", "protocol/ 与 domain/ 不依赖 Android 框架，\n纯 Kotlin，5 个测试类即可覆盖核心算法。", size=12, accent=C_DANGER)

# ============================================================
# P4 协议设计
# ============================================================
s = add_slide()
header(s, "三、自定义二进制协议", "帧格式、为什么不用 JSON、CRC16 校验")

rect(s, Inches(0.6), Inches(1.5), Inches(7.1), Inches(1.5), fill=C_WHITE, line=C_PRIMARY)
textbox(s, Inches(0.8), Inches(1.6), Inches(6.8), Inches(0.32),
        "帧格式（20 字节，适配 BLE 默认 MTU）", size=13.5, color=C_PRIMARY, bold=True)
code_block(s, Inches(0.8), Inches(2.0), Inches(6.7), Inches(0.9),
           "┌──────┬─────┬─────┬─────┬─────┬─────────┬───────┐\n"
           "│ SOF  │ VER │TYPE │ SEQ │ LEN │ PAYLOAD │ CRC16 │\n"
           "│ 2B   │ 1B  │ 1B  │ 1B  │ 1B  │  12B    │ 2B    │\n"
           "└──────┴─────┴─────┴─────┴─────┴─────────┴───────┘\n"
           "  AA55   01                              大端", size=11)

table(s, Inches(0.6), Inches(3.2), Inches(7.1), [
    ["字段", "作用"],
    ["SOF", "帧头 AA 55，两字节降低误判概率"],
    ["VER", "协议版本，便于将来升级兼容"],
    ["SEQ", "循环序号 0-255，用于检测丢包"],
    ["LEN", "载荷长度，解析器据此判断帧边界"],
    ["PAYLOAD", "温度/湿度/气压/光照/BMP温度/健康位"],
    ["CRC16", "校验范围 = VER..PAYLOAD，不含 SOF 与自身"],
], col_widths=[1.6, 5.5], size=11.5)

card(s, Inches(8.0), Inches(1.5), Inches(4.75), Inches(1.95),
     "为什么不用 JSON？",
     "一帧体积：JSON ≈ 45B / 二进制 20B\n"
     "MTU 分包：JSON 至少 3 包 / 二进制 1 包\n"
     "ESP32 内存：JSON 需解析库 / 二进制栈上组帧\n"
     "校验：JSON 需额外加 / 二进制天然自带\n\n"
     "ESP32 仅 520KB SRAM，二进制是正确选择。", size=11.5)

card(s, Inches(8.0), Inches(3.65), Inches(4.75), Inches(1.95),
     "浮点数如何传？",
     "统一放大 10 倍转整数：26.5℃ → 265\n"
     "接收端 ÷10 还原：265 → 26.5f\n\n"
     "优点：精度 0.1℃ 足够（传感器本身 ±0.3℃）\n"
     "　　　无精度丢失、无字节序歧义\n"
     "　　　12 字节塞下 5 个物理量", size=11.5, accent=C_OK)

card(s, Inches(8.0), Inches(5.8), Inches(4.75), Inches(0.95),
     "CRC-16/CCITT-FALSE（答辩必背三参数）",
     "多项式 0x1021 · 初值 0xFFFF · 无反射无异或", size=11.5, accent=C_DANGER)

rect(s, Inches(0.6), Inches(5.8), Inches(7.1), Inches(0.95), fill=RGBColor(0xFF, 0xF4, 0xF4), line=C_DANGER)
textbox(s, Inches(0.8), Inches(5.92), Inches(6.8), Inches(0.8),
        "CRC 只检错不纠错。策略：检出错误 → 丢弃该帧 → 丢包计数 +1 → UI 显示错误率。\n"
        "这比「把一个错的值显示给用户」要好得多。",
        size=12, color=RGBColor(0x99, 0x22, 0x22))

# ============================================================
# P5 关键实现 A：流式解析 + 重连
# ============================================================
s = add_slide()
header(s, "四、关键实现（一）", "流式帧解析的滑动窗口 + 指数退避重连")

card(s, Inches(0.6), Inches(1.5), Inches(6.0), Inches(1.5),
     "问题：BLE 分包边界 ≠ 协议帧边界",
     "一次回调可能只有半帧，也可能一次来两帧半。\n"
     "直接「收到一包就当一帧」必然丢数据。\n"
     "解法：累积缓冲区 + 状态机逐字节推进。", size=12.5)

card(s, Inches(6.75), Inches(1.5), Inches(6.0), Inches(1.5),
     "★ 为什么出错只丢 1 字节？",
     "CRC 失败若 reset() 清空缓冲区，会连带丢掉\n"
     "后面已经收到的好数据。只丢最老的 1 字节\n"
     "（arraycopy 前移），保证不漏任何潜在帧头。\n"
     "这就是滑动窗口，代价只是多循环几次。", size=12.5, accent=C_DANGER)

code_block(s, Inches(0.6), Inches(3.2), Inches(12.15), Inches(1.75),
           "val payloadLen = buffer[5].toInt() and 0xFF        // Byte 有符号，必须 and 0xFF\n"
           "if (payloadLen > MAX_PAYLOAD) { dropOne(); framesDropped++; continue }\n"
           "val need = HEADER_SIZE + payloadLen + CRC_SIZE\n"
           "if (size < need) continue                          // 还没收满，继续攒\n"
           "if (Crc16.verifyFrame(buffer.copyOfRange(0, need))) {\n"
           "    out.add(Frame(...)); framesOk++; size = 0      // 校验通过，产出帧\n"
           "} else {\n"
           "    dropOne(); framesBadCrc++                      // ★ 只丢 1 字节，不清空\n"
           "}", size=11.5)

card(s, Inches(0.6), Inches(5.15), Inches(6.0), Inches(1.6),
     "指数退避 + 随机抖动",
     "delay = min(30s, 1s × 2ⁿ) + 0~30% 抖动\n\n"
     "· 指数：刚断线很可能马上能连上，快速重试快速恢复；\n"
     "  长时间连不上说明真离开了，继续每秒重试只是白耗电。\n"
     "· 抖动：多台设备同时断线时，固定延迟会让它们\n"
     "  在完全相同时刻一齐重连（惊群），反而全部失败。", size=11.5)

table(s, Inches(6.75), Inches(5.15), Inches(6.0), [
    ["常见 BLE 错误码", "含义", "处理"],
    ["133", "GATT_ERROR 通用失败", "退避重试"],
    ["8", "连接超时", "退避重试"],
    ["22", "对端主动断开", "立即重试一次"],
    ["257", "配对失败", "提示重新配对"],
], col_widths=[1.8, 3.0, 1.9], size=11)

# ============================================================
# P6 关键实现 B：卡尔曼 + 规则引擎
# ============================================================
s = add_slide()
header(s, "五、关键实现（二）", "卡尔曼滤波与三重防抖规则引擎")

rect(s, Inches(0.6), Inches(1.5), Inches(6.0), Inches(2.5), fill=C_WHITE, line=C_ACCENT)
textbox(s, Inches(0.8), Inches(1.6), Inches(5.6), Inches(0.32),
        "一维卡尔曼滤波 · 五公式（标量版）", size=13.5, color=C_PRIMARY, bold=True)
code_block(s, Inches(0.8), Inches(2.0), Inches(5.6), Inches(1.85),
           "① 预测状态：   x̂⁻ = x̂\n"
           "② 预测协方差： P⁻ = P + Q\n"
           "③ 卡尔曼增益： K  = P⁻ / (P⁻ + R)\n"
           "④ 更新状态：   x̂  = x̂⁻ + K(z − x̂⁻)   ← 实际输出\n"
           "⑤ 更新协方差： P  = (1 − K) · P⁻", size=12)

table(s, Inches(0.6), Inches(4.15), Inches(6.0), [
    ["参数", "调大效果", "本项目取值依据"],
    ["Q 过程噪声", "更信测量→响应快但抖", "0.01（室内每秒变化远小于此）"],
    ["R 测量噪声", "更信预测→平滑但迟钝", "0.1（AHT20 ±0.3℃，方差≈0.09）"],
], col_widths=[1.5, 2.2, 2.8], size=11)

rect(s, Inches(0.6), Inches(5.6), Inches(6.0), Inches(1.15), fill=RGBColor(0xEF, 0xF8, 0xF2), line=C_OK)
textbox(s, Inches(0.8), Inches(5.72), Inches(5.6), Inches(1.0),
        "K 会自动收敛：起初 P 大 → K≈1 全信测量（快速跟上真值）；\n"
        "随后 P 减小 → K 变小（输出越来越平滑）。\n"
        "这正是卡尔曼优于固定窗口平均的本质原因。",
        size=11.5, color=RGBColor(0x1B, 0x66, 0x3B))

rect(s, Inches(6.75), Inches(1.5), Inches(6.0), Inches(2.5), fill=C_WHITE, line=C_DANGER)
textbox(s, Inches(6.95), Inches(1.6), Inches(5.6), Inches(0.32),
        "规则引擎 · 三重防线", size=13.5, color=C_DANGER, bold=True)
table(s, Inches(6.95), Inches(2.05), Inches(5.6), [
    ["防线", "作用", "不做的后果"],
    ["连续命中 N 次", "防噪声误报", "一次尖峰就触发"],
    ["5 秒冷却", "防刷屏", "通知栏爆炸"],
    ["滞回比较", "防阈值抖动", "反复触发/解除"],
], col_widths=[1.7, 2.0, 1.9], size=11)

card(s, Inches(6.75), Inches(4.15), Inches(6.0), Inches(1.45),
     "滞回比较器是什么",
     "温度 > 30.0 触发报警后，必须降到 < 28.5 才解除。\n"
     "中间 1.5℃ 的「死区」避免在临界点反复横跳。\n"
     "这是温控器与施密特触发器的标准做法。", size=12, accent=C_DANGER)

card(s, Inches(6.75), Inches(5.75), Inches(6.0), Inches(1.0),
     "UI 可实时切换对比（演示亮点）",
     "原始值 / 滑动平均(N=5) / 卡尔曼滤波 三档热切换\n"
     "阶跃响应：卡尔曼 ≈3 周期 vs 滑动平均 ≈5 周期", size=12, accent=C_OK)

# ============================================================
# P7 界面展示
# ============================================================
s = add_slide()
header(s, "六、界面与运行效果", "模拟器实测（Pixel_7 · API 33 · 1080×2400）")

shots = [
    ("01-main.png", "① 主界面"),
    ("03-devices.png", "② 设备列表"),
    ("04-detail.png", "③ 设备详情"),
    ("05-rules.png", "④ 自动化规则"),
    ("06-history.png", "⑤ 历史记录"),
    ("02-settings.png", "⑥ 设置与降级"),
]
x0, y0 = Inches(0.6), Inches(1.5)
sw_, sh_ = Inches(3.9), Inches(2.35)
for i, (fn, cap) in enumerate(shots):
    col, row = i % 3, i // 3
    l = x0 + col * Inches(4.08)
    t = y0 + row * Inches(2.75)
    p = os.path.join(SHOT, fn)
    if os.path.exists(p):
        try:
            s.shapes.add_picture(p, l, t, width=sw_, height=sh_)
        except Exception as e:
            rect(s, l, t, sw_, sh_, fill=C_BG_CARD)
            textbox(s, l, t + Inches(1.0), sw_, Inches(0.4), fn, size=11, color=C_GRAY, align=PP_ALIGN.CENTER)
    else:
        rect(s, l, t, sw_, sh_, fill=C_BG_CARD)
    textbox(s, l, t + sh_ + Inches(0.06), sw_, Inches(0.28),
            cap, size=11.5, color=C_PRIMARY, bold=True, align=PP_ALIGN.CENTER)

rect(s, Inches(0.6), SH - Inches(1.15), Inches(12.15), Inches(0.72), fill=RGBColor(0xEF, 0xF8, 0xF2), line=C_OK)
textbox(s, Inches(0.8), SH - Inches(1.05), Inches(11.8), Inches(0.6),
        "实测结果：扫描出现 SensorHub-01 (−48 dBm) 与 SensorHub-02 (−67 dBm)；连接后显示 25.9℃，实时曲线滚动，\n"
        "状态灯变绿，链路质量「丢包 0.0% · CRC 错 0.0%」。走的是模拟数据源——它生成真实二进制帧喂给同一个解析器。",
        size=12, color=RGBColor(0x1B, 0x66, 0x3B))

# ============================================================
# P8 伴生模块：桌面小组件
# ============================================================
s = add_slide()
header(s, "七、伴生模块 P1 · 桌面小组件", "RemoteViews 原理、刷新策略、明确的技术取舍")

card(s, Inches(0.6), Inches(1.5), Inches(6.0), Inches(2.35),
     "小组件运行在哪里？",
     "运行在桌面进程（Launcher），不在本 App 进程。\n\n"
     "· 拿不到自己的 View，不能 findViewById\n"
     "· 只能用 RemoteViews「远程描述」界面\n"
     "· 跨进程通信靠 AppWidgetManager\n\n"
     "RemoteViews 本质 = 可序列化的界面操作指令集合：\n"
     "  setTextViewText / setTextColor / setOnClickPendingIntent\n"
     "  → updateAppWidget() 打包发过去", size=12)

table(s, Inches(0.6), Inches(4.0), Inches(6.0), [
    ["RemoteViews 支持", "不支持"],
    ["TextView / ImageView / Button", "任何自定义 View"],
    ["LinearLayout / FrameLayout / RelativeLayout", "RecyclerView"],
    ["AnalogClock / Chronometer", "ConstraintLayout（API 31 前）"],
    ["ListView / GridView / StackView", "ViewPager"],
], col_widths=[3.6, 3.4], size=11)

textbox(s, Inches(0.6), Inches(5.85), Inches(6.0), Inches(0.6),
        "因此实时曲线（自定义 View）无法放进小组件 ——\n小组件只展示三个数值文本 + 时间，这是能力范围内的最优设计。",
        size=11.5, color=C_DANGER)

card(s, Inches(6.75), Inches(1.5), Inches(6.0), Inches(1.5),
     "刷新策略：为什么不能靠系统自动刷新",
     "系统最小间隔是 30 分钟，对实时温湿度毫无意义。\n\n"
     "改为主动推送：Service 收到 BLE 帧\n"
     "→ updateWidgetData() → updateAppWidget()\n"
     "实现「秒级」刷新。", size=12, accent=C_OK)

card(s, Inches(6.75), Inches(3.15), Inches(6.0), Inches(1.75),
     "⚠ 明确的技术取舍（主动说明优于被问出）",
     "局限：用进程内静态缓存 lastData，App 进程被回收后\n"
     "小组件显示「暂无数据」。\n\n"
     "更健壮做法：存 Room，小组件异步查询。\n"
     "为何没做：onUpdate 跑在桌面进程主线程，不允许耗时\n"
     "操作（含数据库查询，可能 ANR），需引入 RemoteViewsService。", size=11.5, accent=C_DANGER)

card(s, Inches(6.75), Inches(5.05), Inches(6.0), Inches(1.2),
     "空态与状态着色",
     "数据为 null 时显示「-- 暂无数据」而非留白；\n"
     "温度 > 30℃ 时 setTextColor 染红，桌面一眼看出异常。", size=12)

# ============================================================
# P9 测试与问题记录
# ============================================================
s = add_slide()
header(s, "八、测试与问题解决", "5 个单元测试类 + 17 个真实问题及修复")

table(s, Inches(0.6), Inches(1.5), Inches(6.0), [
    ["单元测试类", "核心用例"],
    ["Crc16Test", "已知向量、单比特翻转必被检出、大端写入"],
    ["FrameParserTest", "半帧分片、一次两帧半、错帧只丢 1 字节"],
    ["KalmanFilterTest", "常值收敛、阶跃响应、增益单调收敛"],
    ["RuleEngineTest", "单次不触发、连续 N 次触发、冷却、滞回"],
    ["ReconnectPolicyTest", "指数增长、抖动范围、上限封顶"],
], col_widths=[2.2, 4.6], size=11)

card(s, Inches(0.6), Inches(3.5), Inches(6.0), Inches(1.35),
     "为什么只测 protocol/ 与 domain/？",
     "BLE 与 UI 依赖 Android 框架，需 Robolectric，成本高、\n"
     "稳定性差。把核心算法设计成不依赖 Android 的纯 Kotlin，\n"
     "正是为了让它们能被低成本测试 —— 分层的直接收益。", size=11.5)

card(s, Inches(0.6), Inches(5.0), Inches(6.0), Inches(1.75),
     "集成验证（模拟器实测）",
     "① assembleDebug → BUILD SUCCESSFUL（APK 9.4 MB）\n"
     "② 安装 + 授权三项权限\n"
     "③ 开启模拟数据源 → 扫描 → 出现两台设备\n"
     "④ 连接 → 12 秒后数据跑起来，丢包 0.0%\n"
     "⑤ 规则页 / 历史页均正常渲染", size=11.5, accent=C_OK)

table(s, Inches(6.75), Inches(1.5), Inches(6.0), [
    ["典型问题", "根因 → 修复"],
    ["AGP 9 报 kotlin.android 插件多余", "AGP 9 内置 Kotlin → 删除插件声明"],
    ["Gradle 版本不足 9.6.0", "官方源被墙 → 改用腾讯镜像"],
    ["Unresolved reference 'pow'", "Kotlin 移除 Float 版 → 用 Math.pow"],
    ["启动崩溃 SwitchCompat.makeLayout", "M2 主题误用 M3 组件 → 换 SwitchMaterial"],
    ["BH1750 读数恒为 0", "两处各建对象 → 改文件级 static 单例"],
    ["printf(\"%ulu\") 输出错乱", "非法格式符 → 改 %u + 强转"],
    ["连上但收不到数据", "漏写 CCCD → 补第二步写 ENABLE"],
], col_widths=[3.0, 3.8], size=10.5)

card(s, Inches(6.75), Inches(4.95), Inches(6.0), Inches(1.8),
     "已知局限（诚实列出）",
     "· 小组件数据在进程死亡后丢失（改用 RemoteViewsService）\n"
     "· 未做真实硬件联调（硬件已采购，尚未接线）\n"
     "· 无配对加密（可加 LE Secure Connections）\n"
     "· 数据库无迁移代码（version=1，加字段时需补）\n"
     "· 未做多设备并发压测（现仅 2 台）", size=11.5, accent=C_DANGER)

# ============================================================
# P10 总结
# ============================================================
s = add_slide()
header(s, "九、总结与展望", "技术亮点、个人收获、改进方向")

card(s, Inches(0.6), Inches(1.5), Inches(6.0), Inches(2.6),
     "八个技术亮点",
     "1. 自定义二进制协议 + CRC16（20B 适配 MTU）\n"
     "2. 流式解析 + 滑动窗口（错帧只丢 1 字节）\n"
     "3. 指数退避 + 随机抖动重连（避免惊群）\n"
     "4. 卡尔曼滤波（Q/R 有物理依据，增益自收敛）\n"
     "5. 三重防抖规则引擎（命中+冷却+滞回）\n"
     "6. 双温度源交叉校验（AHT20 与 BMP280 互盯）\n"
     "7. 模拟数据源走真实协议栈（无硬件全链路验证）\n"
     "8. 纯自绘实时曲线（环形缓冲，无三方图表库）", size=12)

card(s, Inches(6.75), Inches(1.5), Inches(6.0), Inches(2.6),
     "个人收获",
     "协议：理解「为什么嵌入式不用 JSON」，学会设计\n"
     "　　　带校验、带序号、带长度的帧格式\n"
     "异步：callbackFlow 把 Java 回调包装成挂起函数；\n"
     "　　　理解 repeatOnLifecycle 与 launchWhen 的本质区别\n"
     "架构：分层带来的可测试性\n"
     "可靠：「能跑」与「恶劣条件下稳定跑」是两回事\n"
     "调试：崩溃堆栈 → 源码行 → 属性缺失 → 版本不匹配", size=11.5, accent=C_OK)

card(s, Inches(0.6), Inches(4.3), Inches(6.0), Inches(2.45),
     "后续改进方向",
     "1. 真实硬件联调，用 nRF Connect 交叉验证协议\n"
     "2. 多设备并发压测（2 台 → 5 台），观察信道竞争\n"
     "3. 安全加固：BLE Pairing / LE Secure Connections\n"
     "4. 历史记录导出 CSV\n"
     "5. 接入 MQTT，推送到 Home Assistant\n"
     "6. 小组件改用 RemoteViewsService 从 Room 查询", size=12)

rect(s, Inches(6.75), Inches(4.3), Inches(6.0), Inches(2.45), fill=RGBColor(0xE8, 0xF1, 0xFA), line=C_PRIMARY)
textbox(s, Inches(7.0), Inches(4.45), Inches(5.5), Inches(2.2),
        "结语\n\n"
        "本项目的价值不在于「做了一个能连蓝牙的 App」，\n"
        "而在于把实验室 demo 与工程产品之间的那段\n"
        "距离走了多少：\n\n"
        "带校验的协议 · 能自愈的连接\n"
        "不误报的告警 · 优雅降级的权限\n"
        "可裁剪的存储\n\n"
        "这些不炫技，但决定了软件能否在真实环境中被信任。",
        size=12.5, color=C_PRIMARY, spacing=1.3)

prs.save(OUT)
print("PPT saved:", OUT)
print("slides:", len(prs.slides.__iter__.__self__._sldIdLst))
