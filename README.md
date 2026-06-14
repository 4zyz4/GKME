# Gamepad Emu
<img width="3200" height="1440" alt="Screenshot_2026-06-14-17-01-26-243_com zyz4 gamepademu" src="https://github.com/user-attachments/assets/5dc80a24-fa4a-4a83-9582-2f29286008cc" />

将 Android 手机变成无线游戏手柄，用于 Windows、Android 或其他设备。

支持通过 Wi-Fi 或蓝牙经典模式模拟 Xbox 360 和 DualShock 4 手柄。

## 功能特性

- **Wi-Fi 模式** — TCP 连接，低延迟，通过 UDP 广播自动发现设备
- **蓝牙经典模式** — HID 设备配置文件，可直接与 Windows、macOS 或掌机配对
- **手柄模式** — 支持 Xbox 360 或 DualShock 4 模拟
- **陀螺仪与加速计** — 完整的 IMU 支持，支持校准（DS4 模式）
- **触摸板** — 触摸板输入映射（DS4 Wi-Fi 模式）
- **自定义布局** — 预设系统，支持导入/导出，拖拽编辑布局设计器
- **多种显示模式** — Xbox、PlayStation 或 Nintendo Switch 按键标签

## 架构

```
┌─────────────┐    TCP/UDP/BT     ┌─────────────────┐
│  Android App │ ◄──────────────► │  PC Host (WPF)  │
│  (gamepad)   │    BLE (可选)    │  (ViGEm虚拟手柄) │
└─────────────┘                    └─────────────────┘
```

### 组件说明

- **Android 应用** — 虚拟手柄，包含方向键、摇杆、扳机键和 IMU 传感器集成
- **PC 主机 (WPF)** — 通过 TCP 或蓝牙接收 Protocol Buffers 输入数据，通过 viGEm 驱动虚拟手柄

### 通信协议

使用 Protocol Buffers 定义的二进制协议，详见 `proto/gamepad_state.proto`。消息格式：4 字节长度前缀（大端序）+ proto 载荷。

## 环境要求

### Windows 主机
- Windows 10/11 (64-bit)
- [.NET 8.0 Runtime](https://dotnet.microsoft.com/download/dotnet/8.0)
- [viGEm 驱动](https://github.com/nefarius/viGEm) — NuGet 包会自动安装

### Android 设备
- Android 7.0+ (API 24+)
- 蓝牙（若使用蓝牙模式）
- 与主机 PC 相同的 Wi-Fi 网络

## 快速开始

1. 构建并运行 Windows 主机：
   ```
   cd ../gamepad_emu_windows
   dotnet run
   ```
2. 在手机上构建并安装 Android 应用
3. 将两个设备连接到同一 Wi-Fi 网络（Wi-Fi 模式）或通过蓝牙配对（蓝牙模式）
4. 主机将自动发现设备，即可开始模拟手柄

## 构建方法

### Windows 主机
```bash
cd ../gamepad_emu_windows
dotnet restore
dotnet build
dotnet run
```

### Android 应用
```bash
./gradlew assembleDebug
```

## 项目结构

```
gamepad_emu_android/          # Android 应用（本仓库）
├── android/src/main/java/    # Kotlin 源码 (MainActivity, services, viewmodels)
├── proto/                    # 协议 buffer 定义
└── build/                    # 编译产物

共享:
├── proto/gamepad_state.proto # 协议定义（共享）
```

## Protobuf 协议

与 Windows 主机项目共享。详见 `proto/gamepad_state.proto` 中的完整消息定义。

## 许可

本项目采用 [GNU Affero General Public License v3 (AGPLv3)](LICENSE)。

**重要条款：**

- 任何使用、修改或基于本软件的服务都必须开源，并同样使用 AGPLv3 协议。
- **原作者保留独家商业使用权。** 未经原作者明确书面许可，禁止将本软件或其衍生作品用于商业用途（包括但不仅限于应用商店上架、付费分发、订阅服务等）。
- 违反上述条款将导致授权自动终止，原作者保留追究 infringement 法律责任的权利。
