## Description

<!-- Describe what this PR changes and why. Keep one PR focused on one thing. -->

## Related issue

<!-- Link the issue(s) this PR addresses, e.g. Closes #123 or Refs #123. -->

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Performance improvement
- [ ] Refactor (no behavior change)
- [ ] Documentation / translation
- [ ] Build / toolchain
- [ ] Tests
- [ ] Other (describe below)

## How has this been tested?

<!--
Describe your testing. State the device, Android version, connection method, and verified scenarios.
If you could not test a platform or transport path, say so explicitly.
-->

- **Device / Android version:**
- **Connection method:** <!-- WiFi / USB (ADB) / Bluetooth HID / DSU / Local (Shizuku) / Controlled device / Web gamepad -->
- **Verified scenarios:**
- **JVM unit tests:** <!-- e.g. `./gradlew :android:testDebugUnitTest` passed -->

## Screenshots / screen recording

<!-- Required for UI changes. Include light / dark theme and portrait / landscape where applicable. -->

## Compatibility

<!--
Explain any impact on:
- the protobuf wire protocol shared with the PC client (gamepad_state.proto)
- persisted data (AppSettings, LayoutPreset, layout JSON)
- HID descriptors / USB reports
Write "None" if not applicable.
-->

## Checklist

- [ ] I have read the [Contributing Guide](../CONTRIBUTING.md).
- [ ] The change is focused and does not include unrelated formatting or renames.
- [ ] I have added or updated tests where applicable.
- [ ] I have updated the affected documentation (`docs/`, `README.md`, `README.zh-CN.md`).
- [ ] The build and unit tests pass locally.
- [ ] I have not included build outputs (`build/`, `*.apk`, `*.aab`), `local.properties`, keystores, `*.log`, or crash dumps.
- [ ] I have not bumped `versionCode` / `versionName` (releases are handled by the maintainer).
