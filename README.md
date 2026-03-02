# 午休卫士 (Nap Guard)

> 🛌 **从真正入睡时开始计时的科学午休闹钟**

午休卫士是一款专为午休设计的 Android 智能闹钟应用。它通过实时麦克风检测鼾声来判断入睡时机，并在你真正睡着后的指定时长后唤醒你——告别"还没睡着就被吵醒"和"午睡超时毁掉一下午"的困境。

---

## 🎯 核心痛点

> 周末午休睡着了，一觉醒来下午三点，晚上又睡不着……

- **传统闹钟**：从"躺下"那刻开始计时，不知道自己什么时候才睡着
- **午休卫士**：从"打呼噜满 N 分钟（判定入睡）"那刻才开始倒计时

---

## ✨ 功能特性

| 功能 | 说明 |
|------|------|
| 🎙️ 实时鼾声检测 | 通过麦克风持续监听，识别鼾声特征 |
| 😴 智能入睡判定 | 持续打鼾超过设定时长（默认 5 分钟），判定已熟睡 |
| ⏰ 自动启动闹钟 | 入睡后倒计时到设定时长（默认 30 分钟），自动响铃 |
| 📊 睡眠统计 | 闹钟结束后展示总时长 & 深度睡眠时长 |
| 🔒 锁屏唤醒 | 支持在锁屏界面弹出闹钟，确保不错过 |
| 🔔 后台保活 | Foreground Service 保证倒计时不被系统中断 |

---

## 📱 界面预览

本项目包含完整的 UI 原型设计文件 (`pencil-new.pen`)，使用 [Pencil](https://pencil.di.fm) 打开可查看三个核心页面：

| 主控制台 | 睡眠监控页 | 闹钟页 |
|----------|------------|--------|
| 配置时长与入睡判定时长 | 实时展示检测状态与计时 | 唤醒用户 + 展示午休统计 |

**设计风格**：仿 iOS / 微信简约风，清新白底 + 微信绿强调色

---

## 🚀 快速开始

### 环境要求

- Android Studio Hedgehog (2023.1.1) 或更高版本
- Android SDK 26+（最低支持 Android 8.0）
- Kotlin 1.9+

### 克隆与运行

```bash
git clone https://github.com/zhang/nap-guard.git
cd nap-guard
```

用 Android Studio 打开项目，连接真机（推荐，麦克风录音在模拟器上受限），点击运行即可。

---

## 🏗️ 技术架构（规划）

```
app/
├── ui/
│   ├── dashboard/       # 主控制台（配置页面）
│   ├── monitoring/      # 睡眠监控页
│   └── alarm/           # 闹钟页
├── service/
│   └── NapMonitorService.kt   # Foreground Service，负责音频分析 + 倒计时
├── audio/
│   └── SnoreDetector.kt       # 鼾声识别核心逻辑（音频频率分析）
└── data/
    ├── NapRecord.kt            # Room 实体：午休记录
    └── NapRepository.kt        # 数据访问层
```

### 技术选型

| 层级 | 技术 |
|------|------|
| UI | Jetpack Compose |
| 后台服务 | Android Foreground Service |
| 音频分析 | AudioRecord + FFT 频率分析 |
| 数据持久化 | Room Database + SharedPreferences |
| 闹钟触发 | AlarmManager (精确时钟) |

---

## 🔐 权限说明

应用申请以下权限，**所有录音数据均仅在设备本地处理，不上传任何服务器**：

| 权限 | 原因 |
|------|------|
| `RECORD_AUDIO` | 实时鼾声检测 |
| `FOREGROUND_SERVICE` | 后台持续监控 |
| `WAKE_LOCK` | 防止设备提前休眠 |
| `USE_FULL_SCREEN_INTENT` | 锁屏弹出闹钟界面 |
| `SCHEDULE_EXACT_ALARM` | 精确定时触发闹钟 |

---

## 📄 文档

- [产品需求文档 (PRD)](./docs/requirements.md) — 完整的功能规格与交互逻辑

---

## 🗺️ Roadmap

- [x] UI 原型设计（Pencil）
- [x] PRD 文档撰写
- [ ] Android 工程初始化
- [ ] 鼾声检测算法实现
- [ ] Foreground Service 实现
- [ ] Room 数据库集成
- [ ] 闹钟页 & 锁屏弹出实现
- [ ] 睡眠历史记录页
- [ ] 自定义铃声

---

## 📃 License

MIT License © 2026
