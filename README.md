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
| 😴 智能入睡判定 | 过去 5 分钟内鼾声帧占比 > 60%，判定已熟睡 |
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
- Kotlin 2.1+

### 克隆与运行

```bash
git clone https://github.com/zhang/nap-guard.git
cd nap-guard
```

用 Android Studio 打开项目，连接真机（推荐，麦克风录音在模拟器上受限），点击运行即可。

### 命令行运行单元测试

项目已配置好 Gradle Wrapper，可直接在项目根目录执行：

```bash
# 运行所有单元测试（含鼾声检测算法验证）
./gradlew :app:testDebugUnitTest

# 仅运行鼾声检测相关测试
./gradlew :app:testDebugUnitTest --tests "com.example.napguard.audio.SnoreDetectorTest"
```

> **注意**：首次运行需要配置 `JAVA_HOME` 指向 Android Studio 自带的 JDK：
> ```bash
> export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
> ```

---

## 🧠 鼾声检测算法（v2.0）

核心检测器 `SnoreDetector` 采用三层判定架构，确保准确、抗噪：

### 第一层：自适应噪底校准
开始后前 ~20 秒采集环境噪声，动态设定检测阈值（噪底 + 12dB），适应安静卧室到轻微噪声环境，无需手动调参。

### 第二层：FFT 频率过滤
对每一帧音频进行快速傅里叶变换（Cooley-Tukey FFT），检测 **50~400 Hz** 频段的能量占比。鼾声集中在低频，而说话声、铃声等能量分布更宽，占比不足时直接过滤。

### 第三层：滑动窗口统计
维护最近 **5 分钟**的帧级鼾声检测结果，当**鼾声帧占比超过 60%** 时才判定为入睡。这允许鼾声之间有自然的呼吸间隔，不再"中断即清零"。

```
麦克风 → 自适应阈值（dB）→ FFT 频率筛选（50-400Hz）→ 滑动窗口（5min，60%占比）→ 判定入睡
```

### 算法边界说明

| 场景 | 表现 | 原因 |
|------|------|------|
| 正常睡眠鼾声 | ✅ 可靠检测 | 符合低频+高振幅特征 |
| 说话声 | ✅ 有效过滤 | 频率分布超出 50~400Hz |
| 汽车/交通声 | ✅ 有效过滤 | 共振频率偏高或振幅低 |
| 餐厅人群嗡嗡声 | ⚠️ 存在误报 | 与鼾声频率范围高度重叠，但实际使用场景中自适应校准可有效抑制 |

---

## 🏗️ 技术架构

```
app/
├── ui/
│   ├── dashboard/          # 主控制台（配置页面）
│   ├── monitoring/         # 睡眠监控页
│   └── alarm/              # 闹钟页
├── service/
│   └── NapMonitorService.kt      # Foreground Service，状态机 + 倒计时
├── audio/
│   ├── SnoreDetector.kt          # 鼾声识别核心（v2.0，自适应+FFT+滑动窗口）
│   └── SimpleFFT.kt              # 纯 Kotlin FFT 实现（Cooley-Tukey）
└── data/
    ├── NapRecord.kt              # Room 实体：午休记录
    └── NapRepository.kt          # 数据访问层（Room + DataStore）
```

### 技术选型

| 层级 | 技术 |
|------|------|
| UI | Jetpack Compose + Material 3 |
| 架构 | MVVM + Hilt 依赖注入 |
| 后台服务 | Android Foreground Service |
| 音频分析 | AudioRecord + Cooley-Tukey FFT |
| 数据持久化 | Room Database + DataStore |
| 导航 | Navigation Compose |

---

## 🧪 测试

### 正样本（打鼾录音）
存放于 `app/src/test/assets/`：

| 文件 | 说明 |
|------|------|
| `snore_basic.wav` | 基础打鼾声录音 |
| `snore_male.wav` | 男性打鼾声录音 |

### 负样本（干扰声）
存放于 `app/src/test/assets/negative/`：

| 文件 | 说明 |
|------|------|
| `speech_talking.wav` | 人物说话声 |
| `speech_baby.wav` | 婴儿声音 |
| `speech_restaurant.wav` | 餐厅环境声（已知局限，见算法边界说明） |
| `car_traffic.wav` | 城市交通声 |
| `car_race.wav` | 赛车引擎声 |
| `car_door.wav` | 车门关闭声 |

### 测试套件（13 个用例）

| 模块 | 测试内容 |
|------|---------|
| FFT 精度 | 200Hz 纯正弦波主频识别，低/高频能量比验证 |
| 滑动窗口 | 合成信号积累占比，静音归零验证 |
| 正样本 E2E | 两段真实鼾声录音端到端检测 |
| 负样本 | 5 种干扰声误判率 < 30% 验证 |

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
- [x] Android 工程初始化
- [x] 鼾声检测算法实现（v2.0：自适应噪底 + FFT 频率过滤 + 滑动窗口）
- [x] Foreground Service 实现
- [x] Room 数据库集成
- [x] 单元测试（13 个用例，正/负样本覆盖）
- [ ] 睡眠历史记录页
- [ ] 自定义铃声
- [ ] 呼吸暂停检测优化

---

## 📃 License

MIT License © 2026
