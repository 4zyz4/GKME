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

<p align="center">
  <strong>English</strong> | <a href="README.zh-CN.md">简体中文</a>
</p>

Turn your Android phone into a virtual gamepad — or a virtual keyboard and mouse! Supports WiFi LAN, direct USB, and direct Bluetooth connections.

- 💬 QQ group: **639317971**
- 📖 Internal developer architecture docs are in [`docs/`](docs/README.md) (protocol, transport, input, UI, haptics, controlled side, native, etc.)

## Table of Contents

- [Core Concepts](#core-concepts)
- [Features](#features)
- [Quick Start](#quick-start)
- [Download & Build](#download--build)
- [System Requirements](#system-requirements)
- [License](#license)
- [Acknowledgements](#acknowledgements)

---

## Core Concepts

GKME organizes its functionality along two dimensions: the **connection method** determines how input reaches the target device, and the **working mode** determines the role the phone plays in the link.

### Connection Methods

| Connection | Description | Requirements |
|------------|-------------|--------------|
| **WiFi LAN** | Auto-discovery or manual IP entry, UDP + protobuf communication, automatic reconnection; can switch between "as controller" (default) and "as controlled device" | Phone and PC on the same LAN |
| **USB (ADB)** | Direct cable connection, the PC communicates with the phone via `adb forward`, no LAN needed and lower latency; USB discovery aligns with WiFi broadcast | USB debugging enabled on the phone; adb available on the PC |
| **Bluetooth HID** | The phone emulates a Bluetooth HID gamepad and connects directly to a PC or another phone; supports Windows / Android / Linux, with optional "gamepad only" or "generic keyboard & mouse" modes | Android 9+ |
| **Emotion (DSU)** | Compatible with the DSU UDP protocol used by DecideX Emotion | — |
| **Local (Shizuku)** | No PC or other device needed; creates a virtual gamepad on the current device via Shizuku + uinput | Shizuku installed and running |

### Working Modes

| Mode | Phone role | Description |
|------|------------|-------------|
| **Controller** | Input source | Sends touch, motion, and physical gamepad input to the PC (WiFi / USB / Bluetooth / DSU) |
| **Controlled device** | Receiver | Receives input from a remote controller, creates a local virtual gamepad via Shizuku + uinput, supports injecting simulated keyboard and mouse, and runs as a foreground service |
| **Local mode** | Input source + receiver | Acts as both controller and controlled device, creates a local virtual gamepad, and enters floating mode |
| **Floating mode** | Overlay | The app minimizes and the gamepad floats above other apps as an overlay window, so you can play and control at the same time |
| **Web gamepad** | Browser as controller | The controlled device runs a built-in HTTP + WebSocket server (port 37285); open the address in a browser on the LAN or scan the QR code to control it, no PC client needed |

> Multi-device scenarios: broadcasts and handshakes carry a device identifier (ANDROID_ID) for easier identification and connection; when the controlled device scans multiple IPs for the same device, a merged card is used.

---

## Features

### 🎮 Device Emulation & Input

| Feature | Description |
|---------|-------------|
| **On-screen gamepad** | Full emulation: ABXY buttons, sticks, D-pad, analog triggers, shoulder buttons, DS4 touchpad |
| **Keyboard & mouse** | Full keyboard mapping and mouse control; the touchpad supports multi-touch, scrolling, and 7 customizable gestures with configurable swipe thresholds; custom keypads support 4-way / 8-way modes |
| **Volume key mapping** | Set key combinations for the phone's volume keys |
| **Motion control** | Uses the gyroscope and accelerometer of the phone or an external gamepad, mappable to mouse/stick/gamepad input, with deadzone/anti-deadzone and accelerometer steering |
| **Sensitivity curves** | The gyro/accelerometer-to-stick and gyro-to-mouse curves share the same sensitivity curve, with multi-point and full-screen magnified editing |
| **External gamepad forwarding** | Connect physical gamepads via OTG or Bluetooth (based on SDL3 + HIDAPI), with multiple gamepads supported at once; every button can be remapped and forwarded together with touch input |
| **Screen-off mode** | Turns the screen off while the service keeps running to save power |

### 🎨 Customization & Appearance

| Feature | Description |
|---------|-------------|
| **Custom button layout** | Fully customizable layout with drag-to-resize and drag-to-move; import/export layouts as JSON |
| **Layout preset system** | Create, copy, rename, and delete presets with graphical previews |
| **Button display styles** | Xbox / PlayStation / Nintendo Switch button styles; shows the shifted character when Shift is held |
| **Appearance customization** | Backgrounds, buttons, sticks, and touchpads support solid colors or custom images, adjustable control opacity (100% by default), exportable as ZIP configs, and colors can bind to gamepad LEDs |
| **Global layout settings** | Gyro settings are upgraded to a full-screen global settings page, with physical gamepad and volume key mapping moved into it; settings are saved with layout presets |
| **Transition animations** | Animated transitions for settings page switches, the layout editing overlay, and parameter changes |

### ⚡ Haptics & Feedback

| Feature | Description |
|---------|-------------|
| **Vibration feedback** | Vibrate on button press/release; in-game vibration can be mapped to phone or gamepad motors, with freely assignable strong/weak vibration and left/right motor swapping |
| **Switch Pro HD rumble** | Synthesizes Switch Pro HD rumble locally on the phone and converts PC-side PCM into Switch HD rumble |
| **Gamepad audio / voice coil actuators** | Phone speaker and controller audio use SDL3 multi-device low-latency playback; a physical DualSense speaker plays PC audio and supports voice coil actuator haptics |
| **Adaptive triggers** | Receives DualSense adaptive trigger effects from the PC and forwards them to the physical gamepad over USB |
| **LED sync** | Forwards gamepad LED colors and player indicators; appearance colors can bind to the LED in real time |

### 🖥️ PC-side & Protocol Capabilities

| Feature | Description |
|---------|-------------|
| **Switch Pro / Joy-Con emulation** | The PC side can emulate a Switch Pro gamepad and dual Joy-Cons over USB; dual Joy-Cons support HD rumble, with rumble parameters synthesized locally on the phone |
| **Multi-platform compatibility** | HID report mapping for Windows / Android / Linux |
| **Variable polling rate** | Selectable polling rate from 30–1000 Hz |
| **Battery sync** | Phone battery level and charging state are recognized by Steam (WiFi DS4 and DS5 modes only) |
| **Multi-gamepad support** | Input, motion, audio, haptics, and adaptive triggers can each select a different device |

---

## Quick Start

### WiFi Connection

Connect the phone and PC to the same WiFi, open the app, and tap start service; the PC will discover it and can connect with one click. You can also enter the IP manually.

### Bluetooth Connection

Enable Bluetooth mode in the app, then search and pair from the Bluetooth settings of your PC or another Android phone, and the phone becomes a real wireless gamepad.

### USB Connection (ADB)

Connect the phone to the PC with a cable and select USB mode in the app. On first use, enable USB debugging on the phone:

1. Open "Settings → About phone" and tap "Build number" 7 times to enable developer options;
2. Go back to "Settings → System → Developer options" and enable "USB debugging";
3. Connect the phone to the PC with a cable and allow USB debugging for that PC on the phone.

The PC side prefers an already-running adb, then adb from PATH / Android SDK, and finally the bundled adb in the program directory under `platform-tools` (the bundled `platform-tools` has been trimmed to contain only adb). PC-side USB discovery aligns with WiFi broadcast; once a USB device is detected, just click "Connect".

### Local Mode (Shizuku)

Select "Local" as the connection method and tap "Start service"; the app will create a virtual gamepad on the current device via Shizuku authorization and enter floating mode, without needing any PC or other device.

- On first use, install and start [Shizuku](https://shizuku.rikka.app/); the connection page guides you through authorization, and if not ready it keeps polling and continues starting automatically after authorization.
- While the service is running, minimizing the app always returns to floating mode.
- Local mode does not create a virtual mouse (to avoid conflicts with screen touch) and does not expose haptics (to avoid a feedback loop between phone vibration and the virtual gamepad).

### Controlled Device Mode

Select "As controlled device" under the WiFi "Control type" and tap "Start service" to enter the controlled connection page (its layout and interaction mirror the PC-side host). The controlled device scans for and connects to a remote controller, receives its input, creates a local virtual gamepad via Shizuku + uinput, and can inject simulated keyboard and mouse; it runs persistently as a foreground service, and returning shows a confirmation dialog warning that the service will stop.

### Web Gamepad (Controlled Device)

After the controlled device starts its service, it runs a built-in HTTP + WebSocket server (port 37285) hosting the web gamepad. Any device on the same LAN can open the address shown on the "Web gamepad" card in a browser, or scan the card's QR code, to turn the browser into a controller: the web page sends `Hello` / `GamepadInput` over `/ws`, the controlled device bridges it to the local virtual gamepad and returns haptics, matching the logic of the PC-side web gamepad. The card lets you select a network interface and shows the current `IP:port` and a link QR code.

### Floating Mode

In floating mode the app minimizes and the gamepad floats above other apps as an overlay window, so you can consult guides or watch videos while playing; the displayed buttons inherit the configured opacity. On first use, grant the "Display over other apps" permission.

### External Gamepad Forwarding

Connect an Android-compatible gamepad to the phone via an OTG cable or Bluetooth, useful for wireless connectivity and motion support of telescopic controllers:

- Supports mainstream gamepads such as Xbox 360 / Xbox One / PlayStation (DS4/DS5) / Nintendo Switch Pro
- Based on SDL3 + HIDAPI, with multiple gamepads supported at once; view driver, rumble, motion, touchpad, and trigger capabilities and re-detect at any time
- Supports DualSense adaptive trigger passthrough, gamepad LED/player indicator passthrough, and gamepad speaker audio with voice coil actuator haptics
- Every button on physical gamepads can be remapped
- Customizable vibration feedback: strong and weak vibration can be freely mapped to the phone or gamepad motors, with left/right motor swapping

---

## Download & Build

### Download Releases

Go to [GitHub Releases](https://github.com/4zyz4/GKME/releases) to download the latest version.

### Build from Source

```bash
git clone https://github.com/4zyz4/GKME.git
cd GKME
./gradlew assembleDebug
```

---

## System Requirements

### Runtime Environment

| Item | Requirement |
|------|-------------|
| Base | Android **8.0 (API 26)** or later, 1080p+ resolution recommended |
| Bluetooth mode | Android **9+ (API 28)** |
| Multi-motor support | Android S+ (API 31) |
| PC side | HID report mapping for Windows / Android / Linux; USB mode requires adb (Android SDK platform-tools), or you can place `platform-tools` in the PC program directory as a bundled adb (the bundled version keeps only adb) |

### Permissions & Services as Needed

The following features require additional permissions or third-party service support:

| Feature | Requirement |
|---------|-------------|
| Floating mode | "Display over other apps" (overlay) permission |
| USB mode | "USB debugging" enabled on the phone (see [Quick Start](#usb-connection-adb)) |
| Local mode / Controlled device mode | [Shizuku](https://shizuku.rikka.app/) installed and running (creates a virtual gamepad via Shizuku + uinput) |
| Web gamepad | Browser and controlled device on the same LAN (port 37285) |

---

## License

This project is released under the [GNU General Public License v3.0](LICENSE).

---

## Acknowledgements

### Referenced & Derived Projects

| Project | Purpose |
|---------|---------|
| [**HIDMaestro**](https://github.com/hifihedgehog/HIDMaestro) | Virtual gamepad driver framework |
| [**usbip-win2**](https://github.com/vadimgrn/usbip-win2) | PC-side virtual HID device bridging driver |
| [**Moonlight**](https://github.com/moonlight-stream/moonlight-android) | Reference for DualSense touchpad recognition algorithm |
| [**Axixi2233/moonlight-android**](https://github.com/Axixi2233/moonlight-android) | Source of physical gamepad USB driver |
| [**qiin2333/moonlight-vplus**](https://github.com/qiin2333/moonlight-vplus) | Source of DS Bluetooth driver |

### Open-Source Libraries Used

| Library | Purpose |
|---------|---------|
| [SDL3 / HIDAPI](https://libsdl.org/) | Physical gamepad input and HID access |
| [Dagger Hilt](https://dagger.dev/hilt/) | Dependency injection framework |
| [Shizuku](https://shizuku.rikka.app/) | Authorization for creating virtual gamepads in local / controlled device modes |
