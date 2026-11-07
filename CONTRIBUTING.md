# Contributing to GKME

Thank you for your interest in contributing to GKME! Whether you report a bug, improve the documentation, add translations, or submit code, you are helping make the project better.

This document is written in English. Issues and pull requests are welcome in **English or Chinese**.

<p align="center">
  <strong>English</strong> | <a href="CONTRIBUTING.zh-CN.md">简体中文</a>
</p>

> This repository currently contains the Android client only (Gradle module `:android`). The PC-side program lives outside this repository; changes that affect protocol compatibility must also consider the PC client.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Ways to Contribute](#ways-to-contribute)
- [Development Environment](#development-environment)
- [Project Layout](#project-layout)
- [Coding Guidelines](#coding-guidelines)
- [Testing](#testing)
- [Commit Conventions](#commit-conventions)
- [Documentation & Translations](#documentation--translations)
- [License](#license)
- [Contact](#contact)

---

## Code of Conduct

- Treat every participant with respect. Keep discussions focused on technical issues. Harassment, discrimination, and personal attacks are not tolerated.
- Search existing issues and documentation before asking. When reporting a problem, include enough information to reproduce it.
- Maintainers may close issues or pull requests that do not follow this guide, fall outside the project's scope, or remain unresponsive.

---

## Ways to Contribute

### Reporting Bugs

Please open an issue on [GitHub Issues](https://github.com/4zyz4/GKME/issues) and include as much of the following as possible:

| Information | Example |
|-------------|---------|
| App version | The version shown in Settings, or `versionName` (e.g. 4.9.0) |
| Device & OS | Phone model, Android version, ROM / OEM build |
| Connection | WiFi / USB (ADB) / Bluetooth HID / DSU / Local (Shizuku) / Controlled device / Web gamepad |
| Working mode | Controller, controlled device, local mode, floating mode, etc. |
| Steps to reproduce | The complete sequence from launching the app to the problem |
| Expected vs. actual | What you expected to happen and what actually happened |
| Logs | Relevant `adb logcat` output for `com.zyz4.gkme`; attach `gkme_error.log` if the app generated one |
| Peripherals | Controller model and connection (OTG / Bluetooth); Shizuku version for local / controlled modes |

Notes:

- **Search for an existing issue first** to avoid duplicates.
- Redact sensitive information such as IP addresses, MAC addresses, serial numbers, and account details from logs and screenshots.
- One issue should describe one problem. Do not mix unrelated symptoms in a single report.

### Feature Requests

- Describe the **use case** and the **current pain point** first, then explain the interaction or behavior you would like.
- For larger features, UI overhauls, or architectural changes, open an issue to discuss the direction before writing code. This avoids work that cannot be merged.
- Sketches, reference implementations, and comparisons with other apps are welcome. Do not submit assets of unknown origin.

### Contributing Code

Changes that are usually fine to submit directly as a pull request:

- Clear bug fixes, crash fixes, and performance improvements;
- Documentation corrections, translation updates, and fixes to examples;
- Features that have been discussed and agreed upon in an issue;
- Additional tests and small, well-scoped refactors.

Keep **one pull request focused on one thing**. Do not mix unrelated formatting, renames, or version bumps into a feature PR.

### Documentation & Translations

- Architecture and implementation documentation lives in [`docs/`](docs/README.md) and should be updated when behavior changes.
- User-facing documentation consists of [`README.md`](README.md) (English) and [`README.zh-CN.md`](README.zh-CN.md) (Simplified Chinese). Keep both in sync.
- In-app strings live in `android/src/main/res/values*/strings_i18n.xml`. Corrections and new translations are welcome.

### Testing & Feedback

You do not need to write code to contribute:

- Test on devices from different manufacturers and Android versions and report compatibility issues;
- Test different paths: WiFi, USB, Bluetooth, DSU, local mode, controlled device, and web gamepad;
- Test hardware-dependent features such as physical controllers, rumble, motion, and adaptive triggers;
- Confirm whether a reported issue is reproducible, or add more device details.

---

## Development Environment

### Requirements

| Item | Requirement |
|------|-------------|
| JDK | **17** (Kotlin and Java targets are both 17) |
| Android SDK | Platform **36** (`compileSdk` / `targetSdk`) plus the matching Build-Tools |
| NDK | **30.0.16248370** |
| CMake | **4.4.3** |
| Gradle | Use the bundled wrapper (**9.7.1**); no separate installation is needed |
| IDE | The latest stable Android Studio that supports the current AGP version, or IntelliJ IDEA with the Android plugin |
| Other | Git; adb for USB debugging; [Shizuku](https://shizuku.rikka.app/) installed and running on the device for local / controlled modes |

The minimum runtime environment is Android 8.0 (API 26). Bluetooth HID mode requires Android 9+ (API 28).

### Setup

```bash
git clone https://github.com/4zyz4/GKME.git
cd GKME
```

Opening the project in Android Studio for the first time generates `local.properties` with `sdk.dir`. This file is ignored by `.gitignore` and **must not be committed**.

### Common Commands

```bash
# Build a debug APK
./gradlew :android:assembleDebug

# Build and install on a connected device
./gradlew :android:installDebug

# Run JVM unit tests
./gradlew :android:testDebugUnitTest

# Build a release APK (R8 minification and resource shrinking enabled)
./gradlew :android:assembleRelease
```

On Windows (PowerShell 5.1):

```powershell
.\gradlew.bat :android:assembleDebug
```

Notes:

- The project has a single module, `:android`, so Gradle tasks need the module prefix.
- Native code is built with CMake for `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64` by default. After changing native code, make sure all four ABIs build successfully.
- The first build downloads Gradle, dependencies, and the NDK / CMake toolchains, so it takes longer and requires network access.

### Local Signing (Optional)

If the following four entries are present in `local.properties`, the debug variant reuses the release signing key. This allows debug builds to be installed over a release build while preserving app data and Shizuku authorization:

```properties
GKME_RELEASE_STORE_FILE=path/to/keystore/relative/to/repo/root
GKME_RELEASE_STORE_PASSWORD=...
GKME_RELEASE_KEY_ALIAS=...
GKME_RELEASE_KEY_PASSWORD=...
```

**Never commit a keystore, `local.properties`, or any password.** Without this configuration, debug builds use the default debug key, which is fine for normal development.

---

## Project Layout

```text
android/src/main/
├── aidl/                 # Controlled-device AIDL interface (IGamepadService)
├── assets/               # RichTap prebaked HD effects and other assets
├── cpp/                  # Native: SDL bridge, uinput / uhid, audio output, HID descriptors
├── java/com/zyz4/gkme/
│   ├── MainActivity*.kt  # Activity split into multiple partials
│   ├── GkViewModel.kt    # Central UI state holder
│   ├── controlled/       # Controlled-device activities / services / managers, Shizuku proxy
│   ├── data/             # Settings / layout / pairing repositories
│   ├── di/               # Hilt bindings
│   ├── haptic/           # RichTap engine, HE codec, PCM streaming
│   ├── i18n/             # In-app locale selection and context-less string access
│   ├── input/            # Input processing, motion, physical controller backends, USB drivers
│   ├── model/            # AppSettings, LayoutPreset, GamepadState, etc.
│   ├── service/          # Connection manager, UDP / USB / Bluetooth / DSU transports, audio
│   ├── view/             # Custom views, layout rendering and editing, input dispatch strategies
│   └── web/              # Built-in web gamepad for the controlled device
├── proto/                # Protobuf protocol definition (gamepad_state.proto)
└── res/                  # Layouts, icons, and localized strings
android/src/test/         # Pure JVM unit tests
docs/                     # Developer architecture documentation
```

See [`docs/README.md`](docs/README.md) for detailed module responsibilities, data flow, and protocol documentation.

### Architecture Conventions

- **Keep a single module.** Do not add Gradle modules unless there is a strong reason and it has been discussed in an issue.
- **Keep UI state in `GkViewModel`.** New screen state and business state should integrate with the existing state flow instead of starting a separate state holder inside a view. `MainActivity` is split into partials; follow the same organization for new screen logic.
- **Use Hilt for dependency injection.** Add new bindings to `di/AppModule.kt` or the relevant existing module.
- **Follow the input pipeline.** Input should flow through `GamepadState` → `GamepadInputProcessor` → `ConnectionManager` → the concrete transport. Do not bypass the existing pipeline and send packets directly.
- **Keep transport state in `ConnectionManager`.** Connection, reconnection, and handshake state is managed there; avoid duplicating it in the UI layer.
- **Controlled device.** The virtual gamepad is created through a Shizuku UserService together with native `uinput` / `uhid`. When changing an AIDL interface, update both the service side and the caller side.
- **Protocol compatibility.** `gamepad_state.proto` is shared with the C# PC client. Wire-format changes must remain backward compatible, or the version upgrade strategy must be explained explicitly. Update [`docs/protocol.md`](docs/protocol.md) at the same time.
- **HID descriptors.** `cpp/gkme_hid_descriptors.h` is auto-generated from a source outside this repository. Do not edit it by hand; contact the maintainer first if the descriptors need to change.
- **Persistence compatibility.** `AppSettings`, `LayoutPreset`, and layout JSON files are stored long-term by users. New fields need default values so that old data still deserializes correctly.

---

## Coding Guidelines

### Kotlin / Java

- Follow the official Kotlin style declared in `gradle.properties` (`kotlin.code.style=official`).
- Use 4-space indentation and match the naming, import ordering, and spacing conventions of the surrounding code.
- Use `UpperCamelCase` for classes, `lowerCamelCase` for functions and properties, and `UPPER_SNAKE_CASE` for constants.
- Comments should explain **why**, not restate what the code does. Comments in this repository are mainly in Chinese; new comments may be in Chinese or English, but should match the file they are in.
- Avoid unnecessary allocations, blocking calls, and frequent logging in hot paths such as input, rendering, and haptics.
- Do not log sensitive information such as IP addresses, MAC addresses, or device serial numbers. Remove or downgrade debug logging before submitting.
- Do not commit large blocks of commented-out dead code. If code must stay for reference, explain why.
- If you use AI assistance to generate code, review, understand, and test it yourself. Make sure it follows this guide and remains maintainable.

### Native (C / C++)

- Use C++17 for C++ code and follow the style of the surrounding C files.
- After changing native code, verify that all ABIs compile and test the affected functionality on a real device.
- For code involving Shizuku / root permissions, `/dev/uinput`, or `/dev/uhid`, define clear failure paths and graceful degradation when permissions are unavailable.

### Resources & Internationalization

- All user-visible text belongs in resource files. Do not hardcode Chinese or English strings in Kotlin / Java.
- Simplified Chinese is the source language and lives in `android/src/main/res/values/strings_i18n.xml`. Existing translations are in `values-en`, `values-fr`, `values-ru`, `values-zh-rHK`, and `values-zh-rTW`.
- When adding strings, provide at least the source language and English. Other languages can be added later; missing entries fall back to the default resources.
- Preserve placeholders such as `%1$s` and `%d`, including their order. Do not translate entries marked `translatable="false"` or product and brand names.
- To add a language, register it in `app_language_codes` and `app_language_labels` in `values/arrays_i18n.xml`, then create the corresponding `values-xx/strings_i18n.xml`.
- Escape characters such as `&`, `<`, and `'` correctly in XML to avoid resource compilation failures.

### Dependencies & Third-Party Code

- Open an issue to discuss new dependencies before adding them. Explain the purpose, size impact, and alternatives.
- Dependency licenses must be compatible with **GPLv3**. Do not add closed-source libraries, fonts, images, or binaries, or assets with unclear licensing.
- When referencing or adapting third-party code, keep the original copyright and license headers and add the source to the acknowledgements in the root `README`.

---

## Testing

### JVM Unit Tests

For pure-logic changes, add or update unit tests under `android/src/test/java/com/zyz4/gkme/`. Current coverage includes:

- `haptic/`: RichTap encoding/decoding, PCM→HE, arbitration;
- `input/`: sensitivity curves, acceleration, combo timing, adaptive triggers;
- `input/usb/`: DualSense output reports, HD rumble encoding;
- `model/`: layout preset serialization, combo timing serialization;
- `service/`: audio DSP, PCM pitch tracking, HD rumble analysis.

Run the tests locally before submitting:

```bash
./gradlew :android:testDebugUnitTest
```

### On-Device Testing

The following features cannot be fully verified on an emulator. State your test device and results in the pull request:

- Bluetooth HID connection;
- USB (ADB) connection;
- Local mode / controlled-device mode (Shizuku + uinput / uhid);
- Physical controllers (OTG / Bluetooth), motion, rumble, adaptive triggers, and audio haptics;
- Floating mode and screen rotation.

If you cannot test a platform or transport path, say so explicitly in the pull request. Do not assume it "should be fine".

---

## Commit Conventions

### Branches

- Create branches from the latest `master`, and keep each branch focused.
- Recommended names: `feat/xxx`, `fix/xxx`, `docs/xxx`, `refactor/xxx`.
- Do not push directly to `master`. All changes go through pull requests.

### Commit Messages

Use the [Conventional Commits](https://www.conventionalcommits.org/) format. Existing history uses Chinese subjects; Chinese and English subjects are both acceptable. The format is:

```text
<type>(<scope>): <subject>
```

Common `type` values:

| Type | Purpose |
|------|---------|
| `feat` | New feature |
| `fix` | Bug fix |
| `refactor` | Refactor without behavior changes |
| `perf` | Performance improvement |
| `docs` | Documentation |
| `build` | Build scripts, dependencies, toolchain |
| `chore` | Maintenance and version bumps |
| `test` | Test additions or fixes |

`scope` can be a module or feature name, for example `audio`, `input`, `haptic`, `layout`, `settings`, `keyboard`, `floating`, `web`, or `release`.

Examples:

```text
feat(input): calculate mouse acceleration from speed
fix(floating): keep overlay orientation in sync with rotation
docs(readme): add English README and make it the default
build(android): upgrade SDL3 to 3.4.20 input-only build
```

Requirements:

- One commit should do one thing. Do not mix formatting with functional changes.
- Keep the subject concise. Use the body to explain **why**, the **impact**, and **testing** when needed.
- Reference issues with `Closes #123` or `Refs #123` in the body.
- **Do not change `versionCode` / `versionName` in feature PRs.** Releases are handled by the maintainer.

### Pull Requests

When opening a pull request:

1. Describe what changed and why, and link the related issue.
2. Explain how you tested: device model, Android version, connection method, and verified scenarios.
3. Include screenshots or a screen recording for UI changes (light / dark theme and portrait / landscape where applicable).
4. Explain compatibility implications for protocol, persistence, or HID report changes.
5. Update the affected code, tests, and documentation.
6. Make sure the build and unit tests pass locally, and address all review comments.

Maintainers may ask you to split a PR, add tests, or change the implementation. The merge method is at the maintainer's discretion. Prefer rebasing your branch onto `master` to keep the history linear and avoid merge commits.

**Do not** include the following in a pull request:

- Build outputs (`build/`, `*.apk`, `*.aab`);
- `local.properties`, keystores, or signing passwords;
- `*.log`, crash dumps, or temporary files;
- Formatting or renames unrelated to the change.

---

## Documentation & Translations

- When architecture, protocol, or data flow changes, update the relevant document in [`docs/`](docs/README.md) and add new documents to the index in `docs/README.md`.
- When user-visible behavior, build steps, or system requirements change, update both `README.md` and `README.zh-CN.md`.
- Use terminology that is common in gaming and peripherals. If a term is ambiguous, keep the English term and explain it in context.
- Keep code blocks, commands, paths, and class names accurate. Avoid outdated screenshots and links.

---

## License

GKME is released under the [GNU General Public License v3.0](LICENSE).

By submitting a contribution, you agree to license it to this project under **GPLv3**. Please make sure that:

- You own the copyright of the submitted work, or its license is compatible with GPLv3;
- Original copyright and license notices are preserved when using third-party code, with the source noted in the acknowledgements;
- No closed-source, patent-restricted, or unlicensed code, assets, or binaries are submitted.

No CLA is required.

---

## Contact

- Bugs and feature requests: [GitHub Issues](https://github.com/4zyz4/GKME/issues)
- Code and documentation: [GitHub Pull Requests](https://github.com/4zyz4/GKME/pulls)
- Community: QQ group **639317971**
- Developer documentation: [`docs/README.md`](docs/README.md)

If you find a **security vulnerability**, do not disclose details in a public issue. If private vulnerability reporting is enabled for this repository, use it; otherwise contact the maintainer privately through the contact details on their GitHub profile.

Thank you again for your contribution!
