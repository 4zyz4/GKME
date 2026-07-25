<p align="center">
  <img src="android/src/main/res/mipmap/icon.png" alt="Gamepad Emu" width="128"/>
</p>

# Gamepad Emu
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

把你的 Android 手机变成一台虚拟游戏手柄！支持 WiFi 局域网连接和蓝牙直连。

---

## ✨ 功能特性

| 功能 | 说明 |
|------|------|
| 🕹️ **触屏手柄** | 模拟完整手柄：ABXY 按键、摇杆、十字键、扳机、DS4触摸板 |
| 📶 **WiFi 无线** | 局域网内自动发现，也可手动连接 |
| 🔵 **蓝牙直连** | 手机模拟成蓝牙手柄，无体感或震动，但无需接收端 |
| 🎮 **外接手柄转发** | 插上支持Android的手柄到手机，自动转发到游戏设备 |
| 📱 **体感操控** | 支持用手机陀螺仪或者连接到手机的手柄陀螺仪 |
| 🔄 **按键布局自定义** | 布局完全可自定义，可以添加自定义组合键 |
| 🎨 **按键样式** | Xbox / PlayStation / Nintendo Switch 三种按键样式 |
| 💨 **振动反馈** | 按键震动反馈，系统效果、力度和时长可调 |
| 🔋 **电量同步** | 手机电量可被Steam识别（仅限Wifi DS4） |
| 📐 **横屏握持** | 支持左右Joycon布局 |
| 🎶 **音量键映射** | 支持为手机音量键设置组合键 |

---

## 🔌 快速上手

### 📶 WiFi 连接

手机和电脑连上同一个 WiFi，打开 App 即可被发现，电脑端点击连接即可。也可以手动输入IP连接。

### 🔵 蓝牙连接

打开 App 的蓝牙模式，在电脑或另一台Android手机的蓝牙设置中搜索并配对，手机就会变成一个真正的无线手柄。

---

## 🎮 外接手柄转发
把支持Android的手柄通过 OTG 线或蓝牙连到手机上，手机的触屏操作和实体手柄操作会一起发送到游戏设备：
可用于拉伸式手柄的无线连接和体感支持

- 支持市面上主流手柄
- 实体手柄的扳机、摇杆、按键兼容
- 支持手柄陀螺仪体感和Dualshock4/Dualsense触摸板
- 自定义振动反馈：强震动和弱震动可以自由映射到手机马达、手柄的两个马达
---

## 📥 下载
[![GitHub Releases](https://img.shields.io/badge/download-Releases-blue?logo=github)](https://github.com/4zyz4/gamepad-emu-android/releases)

或自行构建：

```bash
git clone https://github.com/4zyz4/gamepad-emu-android.git
cd gamepad-emu-android
./gradlew assembleDebug
```

---

## 📜 开源协议
本项目基于 [GNU General Public License v3.0](LICENSE) 发布。

---

## 🙏 鸣谢
- [**Moonlight**](https://github.com/moonlight-stream/moonlight-android) — DualSense 触摸板识别算法参考
- [Dagger Hilt](https://dagger.dev/hilt/) — 依赖注入框架

---

## 📱 兼容性
- Android **8.0 (API 26)** 及以上
- 推荐分辨率 1080p+
- 蓝牙模式需要 Android **8.0+**
