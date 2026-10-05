<p align="center">
  <img src="android/src/main/res/mipmap/icon.png" alt="GKME" width="128"/>
</p>

<h1 align="center">GKME</h1>

<p align="center">
  <strong>G</strong>amepad <strong>K</strong>eyboard <strong>M</strong>ouse <strong>E</strong>mulator<br/>
  also. <strong>G</strong>eneral <strong>K</strong>ey <strong>M</strong>apping <strong>E</strong>ngine
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License: GPL v3"/></a>
  <a href="https://github.com/4zyz4/GKME/releases"><img src="https://img.shields.io/badge/download-Releases-blue?logo=github" alt="Download Releases"/></a>
</p>

把你的 Android 手机变成一台虚拟游戏手柄！也可以是虚拟键盘、鼠标！支持 WiFi 局域网、USB 直连与蓝牙直连。

- 💬 软件 QQ 群：**639317971**
- 📖 开发者内部架构文档见 [`docs/`](docs/README.md)（协议、传输、输入、UI、震动、被控端与原生等）

## 目录

- [功能特性](#功能特性)
- [快速上手](#快速上手)
- [下载与构建](#下载与构建)
- [系统要求](#系统要求)
- [开源协议](#开源协议)
- [鸣谢](#鸣谢)

---

## 功能特性

### 🔌 连接方式

| 功能 | 说明 |
|------|------|
| **WiFi 连接** | 局域网内自动发现，也可手动输入 IP 连接，支持自动重连 |
| **USB 连接 (ADB)** | 用数据线连接电脑，电脑端经由 adb 转发与手机通信，无需局域网，延迟更低；电脑端 USB 发现与 WiFi 广播对齐 |
| **蓝牙直连** | 手机模拟为蓝牙 HID 手柄，可以连接电脑或另一台手机 |
| **蓝牙目标平台** | 支持 Windows / Android / Linux，并可选择「仅手柄」或「通用键鼠」模式 |
| **Emotion (DSU)** | 兼容 DecideX Emotion 软件 |
| **本机模式 (Shizuku)** | 无需电脑，经 Shizuku + uinput 在当前设备创建虚拟手柄并进入悬浮模式，服务运行时最小化应用会自动进入悬浮模式 |
| **被控端模式 (WiFi)** | 手机作为被控端接收电脑端下发的输入，经 Shizuku + uinput 创建本地虚拟手柄，支持注入模拟键盘与鼠标，以前台服务常驻 |
| **WiFi 控制类型** | 可切换「作为控制端」（默认）或「作为被控端」，被控端扫描到同一设备多个 IP 时使用合并卡片 |
| **设备标识** | 广播与握手携带设备标识 (ANDROID_ID)，便于多设备区分与连接 |

### 🎮 设备模拟

| 功能 | 说明 |
|------|------|
| **触屏手柄** | 完整模拟：ABXY 按键、摇杆、十字键、线性扳机、肩键、DS4 触摸板 |
| **悬浮模式** | 可悬浮于其他应用之上的悬浮操控模式，显示按钮继承透明度，边玩边操控 |
| **外接手柄转发** | 通过 OTG 或蓝牙连接实体手柄，基于 SDL3 + HIDAPI，支持多手柄，全部按键均可自定义映射，与触屏操作一起转发 |
| **体感操控** | 支持手机/外接手柄的陀螺仪与加速度计，可映射为鼠标/摇杆/手柄输入，支持死区/反死区与加速度计转向 |
| **键盘和鼠标** | 完整键盘映射和鼠标控制，触摸板支持多点触控、滚轮与 7 种可自定义手势，滑动判定距离可配置；自定义按键盘支持四向 / 八向模式 |
| **音量键映射** | 支持为手机音量键设置组合键 |

### 🎨 自定义与外观

| 功能 | 说明 |
|------|------|
| **按键布局自定义** | 布局完全可自定义，支持拖拽调整位置和大小，可导入导出 JSON 布局 |
| **按键显示样式** | Xbox / PlayStation / Nintendo Switch 三种按键样式，Shift 按下时显示上档字符 |
| **外观定制** | 背景、按钮、摇杆、触摸板等均支持纯色或图片自定义，控件不透明度可调（默认 100%），可导出为 ZIP 配置，颜色可绑定手柄 LED |
| **布局预设系统** | 新建、复制、重命名、删除预设，图形化预览 |
| **布局全局设置** | 陀螺仪设置升级为全屏全局设置页，实体手柄与音量键映射迁入其中，配置随布局预设保存 |
| **过渡动画** | 设置页切换、编辑布局浮窗与参数切换均带过渡动画 |

### ⚡ 高级功能

| 功能 | 说明 |
|------|------|
| **振动反馈** | 按键按下/释放振动，游戏震动可映射到手机/手柄马达并交换左右马达 |
| **Switch Pro HD 震动** | 手机本地合成 Switch Pro HD 震动，并将电脑端 PCM 转为 Switch HD 震动 |
| **Switch Pro / Joy-Con 模拟** | 电脑端可经 USB 模拟 Switch Pro 手柄与双 Joy-Con，双 Joy-Con 支持 HD 震动，震动参数由手机端本地合成 |
| **自适应扳机** | 接收 PC 下发的 DualSense 自适应扳机效果，经 USB 转发到实体手柄 |
| **手柄音频/音圈马达** | 手机扬声器与控制器音频走 SDL3 多设备低延迟播放；实体 DualSense 扬声器播放 PC 音频，支持音圈马达震动 |
| **LED 同步** | 转发手柄 LED 颜色与玩家指示灯，外观颜色可绑定 LED 实时跟随 |
| **多手柄支持** | 输入、体感、音频、震动与自适应扳机均可分别选择设备 |
| **息屏模式** | 进入息屏模式后屏幕关闭，服务继续运行以省电 |
| **电量同步** | 手机电量和充电状态可被 Steam 识别（仅限 WiFi DS4 和 DS5 模式） |
| **可变轮询率** | 支持 30-1000 Hz 可选轮询率 |
| **多平台兼容** | Windows / Android / Linux HID 报告映射 |
| **体感映射支持** | 支持陀螺仪转鼠标，陀螺仪转摇杆 |
| **灵敏度曲线** | 陀螺仪/加速度计转摇杆与陀螺仪转鼠标共用灵敏度曲线，支持多点编辑与全屏放大编辑 |

---

## 快速上手

### WiFi 连接

手机和电脑连上同一个 WiFi，打开 App，点击启动服务即可被发现，电脑端点击连接即可。也可以手动输入 IP 连接。

### 蓝牙连接

打开 App 的蓝牙模式，在电脑或另一台 Android 手机的蓝牙设置中搜索并配对，手机就会变成一个真正的无线手柄。

### USB 连接 (ADB)

用数据线把手机连到电脑，在 App 中选择 USB 模式。首次使用需在手机上开启 USB 调试：

1. 打开「设置 → 关于手机」，连续点击「版本号」7 次，开启开发者选项；
2. 返回「设置 → 系统 → 开发者选项」，打开「USB 调试」；
3. 用数据线连接电脑，在手机上允许该电脑进行 USB 调试。

电脑端会优先使用正在运行的 adb，其次使用 PATH / Android SDK 中的 adb，最后使用程序目录 `platform-tools` 下的内置 adb（内置 `platform-tools` 已精简为仅含 adb）。电脑端 USB 发现与 WiFi 广播对齐，检测到 USB 设备后点击「连接」即可。

### 本机模式 (Shizuku)

连接方式选择「本机」，点击「启动服务」后，App 会经 Shizuku 授权在当前设备创建虚拟手柄并进入悬浮模式，无需任何电脑或其他设备。

- 首次使用需安装并启动 [Shizuku](https://shizuku.rikka.app/)，连接页会引导完成授权；未就绪时会持续轮询并在授权后自动继续启动。
- 服务运行期间每次最小化应用都会自动回到悬浮模式。
- 本机模式不创建虚拟鼠标以避免与屏幕触摸冲突，也不暴露震动能力以避免手机震动与虚拟手柄形成死循环。

### 被控端模式

在 WiFi「控制类型」中选择「作为被控端」，点击「启动服务」进入被控连接页面（布局与操作对标电脑端主机）。被控端扫描并连接远端控制端，接收其下发的输入，经 Shizuku + uinput 在本地创建虚拟手柄，并可注入模拟键盘与鼠标；运行期间以前台服务常驻，返回时会弹出确认框提示将停止服务。

### 悬浮模式

进入悬浮模式后 App 会最小化，游戏手柄以悬浮窗形式浮在其他应用之上，可边看攻略/视频边操控；显示按钮会继承所设透明度。首次使用需授予「显示在其他应用上层」权限。

### 外接手柄转发

把支持 Android 的手柄通过 OTG 线或蓝牙连到手机上，可用于拉伸式手柄的无线连接和体感支持：

- 支持 Xbox 360 / Xbox One / PlayStation (DS4/DS5) / Nintendo Switch Pro 等主流手柄
- 基于 SDL3 + HIDAPI，支持多手柄同时接入，可查看驱动、震动、体感、触摸板、扳机能力并重新检测
- 支持 DualSense 自适应扳机透传、手柄 LED/玩家灯透传，以及手柄扬声器音频与音圈马达震动
- 实体手柄全部按键均可自定义映射
- 支持自定义振动反馈：强震动和弱震动可自由映射到手机马达或手柄马达，并可交换左右马达

---

## 下载与构建

### 下载发行版

前往 [GitHub Releases](https://github.com/4zyz4/GKME/releases) 下载最新版本。

### 自行构建

```bash
git clone https://github.com/4zyz4/GKME.git
cd GKME
./gradlew assembleDebug
```

---

## 系统要求

| 项目 | 要求 |
|------|------|
| 基础 | Android **8.0 (API 26)** 及以上，推荐分辨率 1080p+ |
| 蓝牙模式 | Android **9+ (API 28)** |
| 多马达支持 | Android S+ (API 31) |
| 悬浮模式 | 「显示在其他应用上层」（悬浮窗）权限 |
| USB 模式 | 电脑端具备 adb（Android SDK platform-tools）；也可把 `platform-tools` 放到电脑端程序目录作为内置 adb（内置版仅保留 adb） |
| 本机模式 / 被控端模式 | 安装并启动 [Shizuku](https://shizuku.rikka.app/)（通过 Shizuku + uinput 创建虚拟手柄） |

---

## 开源协议

本项目基于 [GNU General Public License v3.0](LICENSE) 发布。

---

## 鸣谢

| 项目 | 用途 |
|------|------|
| [**HIDMaestro**](https://github.com/hifihedgehog/HIDMaestro) | 虚拟手柄驱动框架 |
| [**usbip-win2**](https://github.com/vadimgrn/usbip-win2) | 电脑端虚拟 HID 设备桥接驱动 |
| [**Moonlight**](https://github.com/moonlight-stream/moonlight-android) | DualSense 触摸板识别算法参考 |
| [**Axixi2233/moonlight-android**](https://github.com/Axixi2233/moonlight-android) | 实体手柄 USB 驱动来源 |
| [**SDL**](https://libsdl.org/) | 实体手柄输入与 HIDAPI 支持 |
| [Dagger Hilt](https://dagger.dev/hilt/) | 依赖注入框架 |
