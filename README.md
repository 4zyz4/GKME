# Virtual Gamepad

Virtual Gamepad creates a local Xbox, DualShock 4, or DualSense-compatible virtual input device. The Xbox profile uses `/dev/uinput`; the PlayStation profiles use `/dev/uhid`. It uses a Shizuku UserService because ordinary applications cannot access those device nodes.

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
- **UHID error:** The message includes its errno. "This ROM blocks `/dev/uhid` for the shell user" means the DS4 and DualSense profiles require a compatible ROM or Shizuku root mode.

Useful logs:

```bash
adb logcat -s VirtualGamepadManager GKME_RemoteGamepad
```

Confirm the chosen profile after starting it. The vendor/product IDs are from the native backends:

```bash
# xbox: 045e:02fd, Xbox Wireless Controller
adb shell dumpsys input | grep -Ei '045e.*02fd|Xbox Wireless Controller'

# ds4: 054c:09cc, Wireless Controller
adb shell dumpsys input | grep -Ei '054c.*09cc|Wireless Controller'

# dualsense: 054c:0ce6, DualSense Wireless Controller
adb shell dumpsys input | grep -Ei '054c.*0ce6|DualSense Wireless Controller'
```

## Which profile works with COD Mobile

Build each profile, start the gamepad, force-stop COD Mobile, open it, join a match, and check whether COD Mobile enters controller mode. The build only selects the virtual-controller identity; compatibility must be verified on a physical device.

| Profile | Build command | Native identity | COD Mobile result |
|---|---|---|---|
| xbox | `./gradlew :android:assembleDebug -PgamepadProfile=xbox` | `045e:02fd` — Xbox Wireless Controller | untested |
| ds4 | `./gradlew :android:assembleDebug -PgamepadProfile=ds4` | `054c:09cc` — Wireless Controller | untested |
| dualsense | `./gradlew :android:assembleDebug -PgamepadProfile=dualsense` | `054c:0ce6` — DualSense Wireless Controller | untested |

## Build

Requires JDK 17, Android SDK API 36, Android NDK, and CMake.

```bash
./gradlew :android:assembleDebug
```

`gamepadProfile` defaults to `xbox`. Override it with `-PgamepadProfile=ds4` or `-PgamepadProfile=dualsense`.

The debug APK is written to `android/build/outputs/apk/debug/android-debug.apk`.

## License

This project is licensed under the GNU General Public License, version 3. See [LICENSE](LICENSE) for the complete GPL-3.0 text.
