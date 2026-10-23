# Virtual Gamepad

Virtual Gamepad creates a local Xbox-compatible virtual input device through `/dev/uinput`. It uses a Shizuku UserService because ordinary applications cannot access that device node.

## Setup

1. Install and start [Shizuku](https://shizuku.rikka.app/) using Wireless debugging, ADB, or root.
2. Open Virtual Gamepad and tap **Start**.
3. Allow the Shizuku authorization prompt. If it was denied, open **Shizuku > Authorized apps** and allow Virtual Gamepad.
4. On Android 13+, allow notifications if you want start/stop events. The gamepad can still run if notifications are disabled.

The device kernel and SELinux policy must permit Shizuku's shell/root process to access `/dev/uinput`.

## Troubleshooting

- **Shizuku is not running:** Open Shizuku and start it, then tap Start again.
- **Permission permanently denied:** Open **Shizuku > Authorized apps** and allow Virtual Gamepad.
- **Service bind timeout:** Restart Shizuku. If two Shizuku servers may be running, reboot the device.
- **`errno=19`:** The native library did not load in the Shizuku process. Reinstall this APK and confirm the device ABI is included.
- **`errno=13`:** The shell process cannot open `/dev/uinput`; use a compatible ROM or Shizuku root mode.

Useful logs:

```bash
adb logcat -s VirtualGamepadManager GKME_RemoteGamepad
```

Confirm the created device:

```bash
adb shell dumpsys input | grep -i xbox
```

## Build

Requires JDK 17, Android SDK API 36, Android NDK, and CMake.

```bash
./gradlew :android:assembleDebug
```

The debug APK is written to `android/build/outputs/apk/debug/android-debug.apk`.

## License

This project is licensed under the GNU General Public License, version 3. See [LICENSE](LICENSE) for the complete GPL-3.0 text.
