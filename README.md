<p align="center">
  <img src="android/src/main/res/mipmap/icon.png" alt="Gamepad Emu" width="128"/>
</p>

# GKME

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

把你的 Android 手机变成一台虚拟游戏手柄！也可以是虚拟键盘、鼠标！支持 WiFi 局域网连接、蓝牙直连。

软件QQ群：639317971

---

## 功能特性

### 连接方式

| 功能 | 说明 |
|------|------|
| **WiFi 连接** | 局域网内自动发现，也可手动输入 IP 连接，支持自动重连 |
| **蓝牙直连** | 手机模拟为蓝牙 HID 手柄，可以连接另一台手机 |
| **Emotion (DSU)** | 兼容 DecideX Emotion 软件 |

### 设备模拟

| 功能 | 说明 |
|------|------|
| **触屏手柄** | 完整模拟：ABXY 按键、摇杆、十字键、线性扳机、肩键、DS4 触摸板 |
| **外接手柄转发** | 通过 OTG 或蓝牙连接实体手柄，触屏和实体操作一起转发到游戏设备 |
| **体感操控** | 支持手机陀螺仪或外接手柄陀螺仪，可映射为鼠标/摇杆/手柄输入 |
| **键盘和鼠标** | 完整键盘映射和鼠标控制，触摸板支持多点触控和滚轮 |
| **音量键映射** | 支持为手机音量键设置组合键 |

### 自定义与外观

| 功能 | 说明 |
|------|------|
| **按键布局自定义** | 布局完全可自定义，支持拖拽调整位置和大小，可导入导出 JSON 布局 |
| **按键显示样式** | Xbox / PlayStation / Nintendo Switch 三种按键样式 |
| **外观定制** | 背景、按钮、摇杆、触摸板等均支持纯色或图片自定义，可导出为 ZIP 配置 |
| **布局预设系统** | 新建、复制、重命名、删除预设，图形化预览 |

### 高级功能

| 功能 | 说明 |
|------|------|
| **振动反馈** | 按键按下/释放振动，支持手机和手柄马达 |
| **电量同步** | 手机电量和充电状态可被 Steam 识别（仅限 WiFi DS4 和 DS5 模式） |
| **可变轮询率** | 支持 30-1000 Hz 可选轮询率 |
| **多平台兼容** | Windows / Android / Linux HID 报告映射 |
| **体感映射支持** | 支持陀螺仪转鼠标，陀螺仪转摇杆 |

---

## 快速上手

### WiFi 连接

手机和电脑连上同一个 WiFi，打开 App ，点击启动服务即可被发现，电脑端点击连接即可。也可以手动输入 IP 连接。

### 蓝牙连接

打开 App 的蓝牙模式，在电脑或另一台 Android 手机的蓝牙设置中搜索并配对，手机就会变成一个真正的无线手柄。

### 外接手柄转发

把支持 Android 的手柄通过 OTG 线或蓝牙连到手机上，可用于拉伸式手柄的无线连接和体感支持：

- 支持 Xbox / PlayStation (DS4/DS5) / Nintendo Switch Pro 等主流手柄
- 实体手柄的扳机、摇杆、按键、陀螺仪、触摸板兼容
- 支持自定义振动反馈：强震动和弱震动可自由映射到手机马达或手柄马达

---

## 下载

[![GitHub Releases](https://img.shields.io/badge/download-Releases-blue?logo=github)](https://github.com/4zyz4/gamepad-emu-android/releases)

或自行构建：

```bash
git clone https://github.com/4zyz4/gamepad-emu-android.git
cd gamepad-emu-android
./gradlew assembleDebug
```

---

## 系统要求

- Android **8.0 (API 26)** 及以上
- 蓝牙模式需要 Android **9+ (API 28)**
- 推荐分辨率 1080p+
- 多马达支持需要 Android S+ (API 31)

---

## 开源协议

本项目基于 [GNU General Public License v3.0](LICENSE) 发布。

---

## 鸣谢

- [**HIDMaestro**](https://github.com/hifihedgehog/HIDMaestro) - 虚拟手柄驱动框架
- [**usbip-win2**](https://github.com/vadimgrn/usbip-win2) - 电脑端虚拟 HID 设备桥接驱动
- [**Moonlight**](https://github.com/moonlight-stream/moonlight-android) - DualSense 触摸板识别算法参考
- [Dagger Hilt](https://dagger.dev/hilt/) - 依赖注入框架