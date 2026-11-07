# 贡献指南

感谢你愿意为 GKME 做出贡献！无论是报告问题、改进文档、补充翻译，还是提交代码，都会让这个项目变得更好。

本文档以简体中文撰写，Issue 与 Pull Request 使用**中文或英文**均可。

<p align="center">
  <a href="CONTRIBUTING.md">English</a> | <strong>简体中文</strong>
</p>

> 本仓库当前只包含 Android 客户端（Gradle 模块 `:android`）。电脑端程序不在本仓库中；涉及协议兼容性的改动需要同时考虑电脑端。

## 目录

- [行为准则](#行为准则)
- [你可以如何贡献](#你可以如何贡献)
- [开发环境](#开发环境)
- [项目结构](#项目结构)
- [编码规范](#编码规范)
- [测试](#测试)
- [提交规范](#提交规范)
- [文档与翻译](#文档与翻译)
- [许可证](#许可证)
- [联系方式](#联系方式)

---

## 行为准则

- 尊重每一位参与者，围绕技术问题讨论，不进行人身攻击、歧视或骚扰。
- 提问前请先搜索已有的 Issue 和文档；描述问题时尽量提供可复现的信息。
- 维护者有权关闭不符合本指南、偏离项目方向或长期无响应的 Issue / PR。

---

## 你可以如何贡献

### 报告问题

请通过 [GitHub Issues](https://github.com/4zyz4/GKME/issues) 提交，并尽量包含：

| 信息 | 示例 |
|------|------|
| 应用版本 | 设置页显示的版本号，或 `versionName`（如 4.9.0） |
| 设备与系统 | 手机型号、Android 版本、ROM / 厂商系统版本 |
| 连接方式 | WiFi / USB (ADB) / 蓝牙 HID / DSU / 本机 (Shizuku) / 被控端 / 网页手柄 |
| 工作模式 | 控制端、被控端、本机模式、悬浮模式等 |
| 复现步骤 | 从启动应用到出现问题的完整操作顺序 |
| 期望与实际 | 你期望发生什么，实际发生了什么 |
| 日志 | `adb logcat` 中与 `com.zyz4.gkme` 相关的片段；若生成了 `gkme_error.log`，请一并附上 |
| 外设信息 | 使用实体手柄时注明型号与连接方式（OTG / 蓝牙）；本机或被控端模式注明 Shizuku 版本 |

注意事项：

- **请先搜索是否已有相同 Issue**，避免重复。
- 日志和截图请自行脱敏，勿公开 IP、MAC 地址、序列号、账号等隐私信息。
- 一个 Issue 只描述一个问题；不要把多个不相关的现象混在一起。

### 提出功能建议

- 先描述**使用场景**和**当前痛点**，再说明你期望的交互或行为。
- 较大的功能、UI 改版或架构调整，请先开 Issue 讨论，确认方向后再动手，避免做完后无法合并。
- 欢迎附上草图、参考实现或竞品行为说明，但请勿提交来源不明的素材。

### 提交代码

适合直接提交 PR 的改动：

- 明确的 Bug 修复、崩溃修复、性能优化；
- 文档修正、翻译补充、拼写与示例修正；
- 已有 Issue 讨论过并达成一致的功能；
- 测试补充与小范围重构。

请在 PR 中保持**一个 PR 只做一件事**。不要在一个 PR 中混入无关的格式化、重命名或版本号变更。

### 文档与翻译

- 架构与实现文档位于 [`docs/`](docs/README.md)，代码行为变化时应同步更新。
- 用户文档为 [`README.md`](README.md)（英文）与 [`README.zh-CN.md`](README.zh-CN.md)（简体中文），两者需保持内容同步。
- 应用内文案位于 `android/src/main/res/values*/strings_i18n.xml`，欢迎补充或修正翻译。

### 测试与反馈

即使不写代码，也可以：

- 在不同品牌、不同 Android 版本的设备上测试并反馈兼容性；
- 测试 WiFi / USB / 蓝牙 / DSU / 本机 / 被控端 / 网页手柄等不同链路；
- 测试实体手柄、震动、体感、自适应扳机等依赖硬件的功能；
- 在 Issue 中确认问题是否可以复现，或补充更多设备信息。

---

## 开发环境

### 环境要求

| 项目 | 要求 |
|------|------|
| JDK | **17**（Kotlin / Java 目标均为 17） |
| Android SDK | Platform **36**（`compileSdk` / `targetSdk`），并安装对应 Build-Tools |
| NDK | **30.0.16248370** |
| CMake | **4.4.3** |
| Gradle | 使用仓库自带的 Wrapper（**9.7.1**），无需单独安装 |
| IDE | Android Studio（支持当前 AGP 版本的最新稳定版）或 IntelliJ IDEA + Android 插件 |
| 其他 | Git；USB 模式调试需要 adb；本机 / 被控端模式需要设备上安装并启动 [Shizuku](https://shizuku.rikka.app/) |

最低运行环境为 Android 8.0（API 26），蓝牙 HID 模式需要 Android 9+（API 28）。

### 初始化

```bash
git clone https://github.com/4zyz4/GKME.git
cd GKME
```

首次用 Android Studio 打开项目时，会自动生成 `local.properties` 并写入 `sdk.dir`。该文件已被 `.gitignore` 忽略，**不要提交**。

### 常用命令

```bash
# 构建 debug APK
./gradlew :android:assembleDebug

# 构建并安装到已连接的设备
./gradlew :android:installDebug

# 运行 JVM 单元测试
./gradlew :android:testDebugUnitTest

# 构建 release APK（开启 R8 混淆与资源压缩）
./gradlew :android:assembleRelease
```

Windows（PowerShell 5.1）下使用：

```powershell
.\gradlew.bat :android:assembleDebug
```

说明：

- 项目只有 `:android` 一个模块，Gradle 任务请带模块前缀。
- native 代码由 CMake 构建，默认会为 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64` 四个 ABI 编译；修改 native 代码后请确认四个 ABI 都能构建通过。
- 首次构建需要联网下载 Gradle、依赖与 NDK / CMake 工具链，耗时较长。

### 本地签名（可选）

若 `local.properties` 中配置了以下四项，debug 变体会复用 release 密钥签名，便于直接覆盖安装正式版并保留应用数据与 Shizuku 授权：

```properties
GKME_RELEASE_STORE_FILE=相对仓库根目录的 keystore 路径
GKME_RELEASE_STORE_PASSWORD=...
GKME_RELEASE_KEY_ALIAS=...
GKME_RELEASE_KEY_PASSWORD=...
```

**请勿提交 keystore、`local.properties` 或任何密码。** 未配置时，debug 构建使用默认调试签名，不影响正常开发。

---

## 项目结构

```text
android/src/main/
├── aidl/                 # 被控端 AIDL 接口（IGamepadService）
├── assets/               # RichTap 预置 HD 效果等资源
├── cpp/                  # native：SDL 桥、uinput / uhid、音频输出、HID 描述符
├── java/com/zyz4/gkme/
│   ├── MainActivity*.kt  # Activity 的多个 partial
│   ├── GkViewModel.kt    # UI 状态中枢
│   ├── controlled/       # 被控端 Activity / Service / Manager、Shizuku 代理
│   ├── data/             # Settings / Layout / Pairing 仓库
│   ├── di/               # Hilt 绑定
│   ├── haptic/           # RichTap 引擎、HE 编解码、PCM 流
│   ├── i18n/             # 应用内语言选择与无 Context 字符串访问
│   ├── input/            # 输入处理、体感、物理手柄后端、USB 驱动
│   ├── model/            # AppSettings、LayoutPreset、GamepadState 等
│   ├── service/          # 连接管理、UDP / USB / 蓝牙 / DSU 传输、音频
│   ├── view/             # 自定义 View、布局渲染与编辑、输入分发策略
│   └── web/              # 被控端内置网页手柄
├── proto/                # protobuf 协议定义（gamepad_state.proto）
└── res/                  # 布局、图标与多语言字符串
android/src/test/         # 纯 JVM 单元测试
docs/                     # 开发者架构文档
```

更详细的模块职责、数据流与协议说明见 [`docs/README.md`](docs/README.md)。

### 架构约定

- **保持单模块**：除非有充分理由并在 Issue 中讨论过，不要新增 Gradle 模块。
- **UI 状态集中在 `GkViewModel`**：新的界面状态与业务状态应接入现有状态流，不要在 View 中另起一套状态。`MainActivity` 按 partial 拆分，新增页面逻辑请沿用该组织方式。
- **依赖注入使用 Hilt**：新增绑定写在 `di/AppModule.kt` 或已有的对应 Module 中。
- **输入链路**：输入应走 `GamepadState` → `GamepadInputProcessor` → `ConnectionManager` → 具体传输层，不要绕过现有管线直接发包。
- **传输状态**：连接、重连、握手等状态由 `ConnectionManager` 管理，避免在 UI 层重复维护。
- **被控端**：虚拟手柄通过 Shizuku UserService 配合 native `uinput` / `uhid` 创建；修改 AIDL 接口时需同步服务端与调用端。
- **协议兼容**：`gamepad_state.proto` 与电脑端（C#）共享。修改线格式时必须保持向后兼容，或明确说明版本升级策略，并同步更新 [`docs/protocol.md`](docs/protocol.md)。
- **HID 描述符**：`cpp/gkme_hid_descriptors.h` 为自动生成文件（来源不在本仓库），请勿手工修改；确需调整时请先联系维护者。
- **持久化兼容**：`AppSettings`、`LayoutPreset` 与布局 JSON 会被用户长期保存，新增字段请提供默认值，避免旧数据反序列化失败。

---

## 编码规范

### Kotlin / Java

- 遵循 `gradle.properties` 中声明的 Kotlin 官方代码风格（`kotlin.code.style=official`）。
- 使用 4 个空格缩进，保持与相邻文件一致的命名、导入顺序与空行习惯。
- 类名使用 `UpperCamelCase`，函数与属性使用 `lowerCamelCase`，常量使用 `UPPER_SNAKE_CASE`。
- 注释解释**为什么**，而不是复述代码在做什么；本仓库代码注释以中文为主，新增注释使用中文或英文均可，但请与所在文件保持一致。
- 输入、渲染、震动等热路径避免不必要的对象分配、阻塞调用和频繁日志输出。
- 日志中不要输出 IP、MAC、设备序列号等敏感信息；调试日志请在提交前删除或降级。
- 不要提交被注释掉的大段死代码；如确需保留说明，请写明原因。
- 使用 AI 辅助生成的代码，请自行审查、理解和测试，确保符合本指南并能够长期维护。

### Native（C / C++）

- C++ 使用 C++17，C 代码保持与现有文件一致的风格。
- 新增或修改 native 代码后，确认所有 ABI 均可编译，并在真机上验证相关功能。
- 涉及 Shizuku / root 权限、`/dev/uinput`、`/dev/uhid` 的代码，请明确失败路径与权限不足时的降级行为。

### 资源与国际化

- 所有用户可见文案都应放入资源文件，不要在 Kotlin / Java 中硬编码中文或英文。
- 简体中文为源语言，位于 `android/src/main/res/values/strings_i18n.xml`；现有翻译位于 `values-en`、`values-fr`、`values-ru`、`values-zh-rHK`、`values-zh-rTW`。
- 新增文案时至少补齐源语言与英文；其他语言可后续补充，缺失项会回退到默认资源。
- 保留占位符（如 `%1$s`、`%d`）及其顺序，不要翻译 `translatable="false"` 的条目和产品名、品牌名。
- 新增语言时，在 `values/arrays_i18n.xml` 的 `app_language_codes` 与 `app_language_labels` 中登记，并创建对应的 `values-xx/strings_i18n.xml`。
- 修改 XML 时注意转义 `&`、`<`、`'` 等字符，避免资源编译失败。

### 依赖与第三方代码

- 新增依赖前请先开 Issue 讨论，说明用途、体积影响与替代方案。
- 依赖许可证必须与 **GPLv3** 兼容；禁止引入闭源或授权不明的库、字体、图片与二进制文件。
- 引用或改编第三方代码时，请保留原始版权声明与许可证头，并在根目录 `README` 的鸣谢部分补充来源。

---

## 测试

### JVM 单元测试

纯逻辑改动请补充或更新 `android/src/test/java/com/zyz4/gkme/` 下的单元测试，例如：

- `haptic/`：RichTap 编解码、PCM→HE、仲裁；
- `input/`：灵敏度曲线、加速度、组合键时序、自适应扳机；
- `input/usb/`：DualSense 输出报文、HD rumble 编码；
- `model/`：布局预设序列化、组合键时序序列化；
- `service/`：音频 DSP、PCM 音高与 HD rumble 分析。

提交前请本地运行：

```bash
./gradlew :android:testDebugUnitTest
```

### 真机测试

以下功能无法在模拟器上完整验证，请在 PR 中注明你的测试设备与结果：

- 蓝牙 HID 连接；
- USB (ADB) 连接；
- 本机模式 / 被控端模式（Shizuku + uinput / uhid）；
- 实体手柄（OTG / 蓝牙）、体感、震动、自适应扳机、音频触觉；
- 悬浮模式与屏幕旋转。

若某个平台或链路无法测试，请在 PR 中明确说明，不要默认它“应该没问题”。

---

## 提交规范

### 分支

- 从最新的 `master` 切出分支，保持分支聚焦。
- 推荐命名：`feat/xxx`、`fix/xxx`、`docs/xxx`、`refactor/xxx`。
- 不要直接向 `master` 推送；所有改动通过 Pull Request 合并。

### Commit Message

采用 [Conventional Commits](https://www.conventionalcommits.org/) 风格，提交信息以中文为主，也接受英文，格式：

```text
<type>(<scope>): <subject>
```

常用 `type`：

| type | 用途 |
|------|------|
| `feat` | 新功能 |
| `fix` | Bug 修复 |
| `refactor` | 不改变行为的重构 |
| `perf` | 性能优化 |
| `docs` | 文档 |
| `build` | 构建脚本、依赖、工具链 |
| `chore` | 杂项维护、版本号 |
| `test` | 测试补充或修正 |

`scope` 可使用模块或功能名，例如 `audio`、`input`、`haptic`、`layout`、`settings`、`keyboard`、`floating`、`web`、`release`。

示例：

```text
feat(input): 鼠标加速度改为按速度计算，与发送间隔解耦
fix(floating): 修复悬浮层方向不跟随屏幕旋转
docs(readme): 新增英文 README 并默认显示英文
build(android): 升级 SDL3 到 3.4.20 输入专用构建
```

要求：

- 一个提交只做一件事，避免把格式化与功能改动混在一起。
- 标题简明扼要，正文按需说明**原因**、**影响范围**与**测试情况**。
- 关联 Issue 时在正文中使用 `Closes #123` 或 `Refs #123`。
- **不要在功能 PR 中修改 `versionCode` / `versionName`**，版本发布由维护者统一处理。

### Pull Request

提交 PR 时请：

1. 描述改动内容与动机，并链接相关 Issue；
2. 说明测试方式：设备型号、Android 版本、连接方式、验证过的场景；
3. UI 改动附上截图或录屏（亮色 / 深色、横竖屏如适用）；
4. 协议、持久化或 HID 报文有变化时，说明兼容性影响；
5. 同步更新受影响的代码、测试与文档；
6. 确保本地构建与单元测试通过，并解决所有 review 意见。

维护者可能会要求拆分 PR、补充测试或调整实现；合并方式由维护者决定。请尽量通过 `rebase` 保持分支与 `master` 同步，避免合并提交污染线性历史。

以下内容**不要**出现在 PR 中：

- 构建产物（`build/`、`*.apk`、`*.aab`）；
- `local.properties`、keystore、签名密码；
- `*.log`、崩溃转储、临时文件；
- 与本 PR 无关的格式化或重命名。

---

## 文档与翻译

- 架构、协议、数据流发生变化时，请更新 [`docs/`](docs/README.md) 中对应文档，并在 `docs/README.md` 的索引中登记新文档。
- 用户可见行为、构建方式、系统要求变化时，请同步更新 `README.md` 与 `README.zh-CN.md`。
- 翻译尽量符合游戏与外设领域的常用术语；不确定的术语可保留英文并在上下文中说明。
- 文档中的代码块、命令、路径与类名保持准确，避免使用已失效的截图或链接。

---

## 许可证

GKME 以 [GNU General Public License v3.0](LICENSE) 发布。

提交贡献即表示你同意将贡献以 **GPLv3** 授权给本项目。请确保：

- 你拥有提交内容的版权，或所用代码的许可证与 GPLv3 兼容；
- 引用第三方代码时保留原始版权与许可证声明，并在鸣谢中注明来源；
- 不提交任何闭源、专利受限或来源不明的代码、素材与二进制文件。

本项目不要求签署 CLA。

---

## 联系方式

- Bug 与功能建议：[GitHub Issues](https://github.com/4zyz4/GKME/issues)
- 代码与文档贡献：[GitHub Pull Requests](https://github.com/4zyz4/GKME/pulls)
- 交流群：QQ 群 **639317971**
- 开发者文档：[`docs/README.md`](docs/README.md)

如发现**安全漏洞**，请勿在公开 Issue 中披露细节。若仓库已启用 GitHub 私密漏洞报告，请使用该渠道；否则请通过维护者 GitHub 主页列出的联系方式私下联系。

再次感谢你的贡献！
