# Virtual Gamepad for Android

[![Build and Release](https://github.com/orson391/GKME/actions/workflows/release.yml/badge.svg)](https://github.com/orson391/GKME/actions/workflows/release.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

> **Virtual Gamepad for Android** — Play Call of Duty: Mobile (CODM) with any controller (GameSir X5 Lite, generic Bluetooth, USB/OTG) by emulating an official Xbox/PS controller via [Shizuku](https://shizuku.rikka.app/).

> [!IMPORTANT]
> **This is a modified fork of [4zyz4/GKME](https://github.com/4zyz4/GKME)** (GKME — Gamepad Keyboard Mouse Emulator), originally created by [@4zyz4](https://github.com/4zyz4) and licensed under the GNU GPL v3.0.
>
> Modifications made in this fork (2026): reduced scope focused on CODM, custom CI/CD workflows, and a rewritten README. See [NOTICE.md](NOTICE.md) for details.

---

## Why Virtual Gamepad?

Call of Duty: Mobile (CODM) natively restricts controller support to official Microsoft Xbox and Sony PlayStation controllers. Third-party controllers—such as the **GameSir X5 Lite**, GameSir G8/X2, generic Bluetooth gamepads, or wired USB OTG controllers—are ignored or blocked by CODM's device check.

**Virtual Gamepad solves this:**
- It interacts directly with Android's kernel input drivers (`/dev/uinput` or `/dev/uhid`) using Shizuku.
- It instantiates a genuine virtual **Xbox Wireless Controller** (`045e:02fd`) or **PlayStation DualShock 4 / DualSense** device recognized system-wide.
- Once the virtual controller is initialized, CODM immediately detects a supported controller and switches into native controller mode.
- Any physical controller connected to your device (Bluetooth, USB-C, or OTG) can then be used seamlessly in-game.

---

## Features

- **Universal Controller Compatibility:** Use GameSir X5 Lite, generic Bluetooth controllers, USB wired pads, or any third-party gamepad with CODM.
- **Kernel-Level Emulation:** Creates legitimate virtual device nodes via `/dev/uinput` (Xbox) and `/dev/uhid` (DualShock 4 / DualSense).
- **No Root Required:** Operates via Shizuku (compatible with Wireless Debugging, ADB, or Root).
- **Simple One-Tap UI:** Clean and responsive interface with instant Start and Stop controls.
- **Automated CI/CD Builds:** Download pre-compiled APKs directly from GitHub Actions and Releases.

---

## Prerequisites

1. **Android 8.0+** device.
2. [**Shizuku**](https://shizuku.rikka.app/) installed and running.
   - Can be activated easily using **Wireless Debugging** (no PC needed on Android 11+), **ADB**, or **Root**.
3. Your physical controller (e.g., GameSir X5 Lite, Bluetooth pad, or USB controller).

---

## Setup & Usage Guide

### 1. Download & Install
Download the latest APK from the [Releases](../../releases) section or from GitHub Actions artifacts:
- **`VirtualGamepad-xbox.apk`** (Recommended for CODM)
- `VirtualGamepad-ds4.apk`
- `VirtualGamepad-dualsense.apk`

### 2. Connect Your Controller
Connect your physical controller (GameSir X5 Lite, Bluetooth controller, or USB OTG pad) to your Android device and confirm it is paired.

### 3. Start Shizuku
Open the Shizuku app and make sure the Shizuku service is running.

### 4. Start Virtual Gamepad
1. Open **Virtual Gamepad**.
2. Tap **Start**.
3. When prompted, grant Shizuku permission to Virtual Gamepad (or enable it under **Shizuku > Authorized apps**).
4. On Android 13+, allow notification permissions if requested.
5. The status indicator will turn to **`Running (xbox)`**.

### 5. Launch Call of Duty: Mobile
1. If CODM was already running in the background, **Force Stop** it first from Android App Info to ensure a fresh input scan.
2. Launch **Call of Duty: Mobile**.
3. Go to **Settings > Controller**. CODM will detect the connected controller and enable full gamepad gameplay and matchmaking!

---

## Gamepad Profiles

| Profile | Backend | Native Identity | Description |
|---|---|---|---|
| **xbox** *(Default)* | `/dev/uinput` | `045e:02fd` — Xbox Wireless Controller | **Recommended for CODM.** Best compatibility across standard Android kernels and ROMs. |
| **ds4** | `/dev/uhid` | `054c:09cc` — DualShock 4 Wireless Controller | Authentic DualShock 4 HID identity. Requires ROM permitting shell access to `/dev/uhid`. |
| **dualsense** | `/dev/uhid` | `054c:0ce6` — DualSense Wireless Controller | Authentic DualSense HID identity. Requires ROM permitting shell access to `/dev/uhid`. |

---

## Troubleshooting

- **Shizuku is not running:** Launch the Shizuku app and activate it via Wireless Debugging or ADB, then tap **Start** again in Virtual Gamepad.
- **Permission permanently denied:** Open **Shizuku > Authorized apps** and toggle **Virtual Gamepad** ON.
- **CODM does not detect controller:** Force-stop Call of Duty: Mobile in Android Settings, ensure Virtual Gamepad shows `Running (xbox)`, and re-open the game.
- **Service bind timeout:** Restart Shizuku. If multiple Shizuku instances are conflicting, reboot your Android device.
- **UHID error (errno=13):** Your Android ROM restricts `/dev/uhid` for the shell user. Switch to the default **Xbox** profile, which uses `/dev/uinput` and works on almost all Android devices.
- **Inspect device logs:**
  ```bash
  adb logcat -s VirtualGamepadManager GKME_RemoteGamepad
  ```
- **Check connected virtual controller:**
  ```bash
  adb shell dumpsys input | grep -Ei '045e.*02fd|Xbox Wireless Controller'
  ```

---

## Building from Source

### Requirements
- JDK 17 (Temurin recommended)
- Android SDK (API 36)
- Android NDK & CMake

### Build Commands
Clone the repository and build using Gradle:

```bash
# Build default Xbox profile
./gradlew :android:assembleDebug

# Build specific profiles
./gradlew :android:assembleDebug -PgamepadProfile=xbox
./gradlew :android:assembleDebug -PgamepadProfile=ds4
./gradlew :android:assembleDebug -PgamepadProfile=dualsense
```

The compiled APK will be located at:
`android/build/outputs/apk/debug/android-debug.apk`

---

## GitHub Releases & Downloads

Pre-built APKs are automatically generated for every commit and tag via GitHub Actions:
- Check the [Releases](../../releases) page for stable release APKs.
- Check the [Actions](../../actions) tab for the latest build artifacts from the master branch.

---

## License

This project is licensed under the GNU General Public License v3.0. See the [LICENSE](LICENSE) file for details.

It is a modified fork of [4zyz4/GKME](https://github.com/4zyz4/GKME), Copyright (C) 4zyz4,
distributed under the same license. See [NOTICE.md](NOTICE.md) for the required
statement of modification.
