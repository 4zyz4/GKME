# RichTap / HD 震动实验附件

> 本目录持久化 [../richtap-hd-vibration.md](../richtap-hd-vibration.md) 中引用的实验附件，
> 使其随仓库版本化，不再依赖 `%TEMP%` 或本机绝对路径。
>
> 来源：Xiaomi `23117RK66C`（codename `manet`，Snapdragon 8 Gen 3，Android 16 / API 36，HyperOS）
> 的真机实验，未 root、SELinux Enforcing、`adb shell` uid=2000。

---

## 目录结构

| 目录/文件 | 内容 | 文件数 |
|-----------|------|--------|
| `probes/` | 反射调用隐藏 API 的 `app_process` 探针源码（`.java`） | 17 |
| `scripts/` | 生成 HE、构建探针 jar、分析录音的脚本（`.py` / `.ps1`） | 7 |
| `he-samples/` | HE 1.0/2.0 效果样例（`.json` / `.he`） | 56 |
| `decompiled/` | RichTap ASDK 2.2.0 反编译 Java（CFR） | 38 |
| `framework-dumps/` | 框架隐藏类的成员 dump（文本） | 7 |
| `richtapdynamics-extract/` | `RichTapDynamics` 仓库 `app/libs/extract` 逆向产物 | 65 |
| `Hapticsconfig.xml` | 设备 `/vendor/etc/Hapticsconfig.xml`（Qualcomm 预置效果表） | 1 |

---

## probes/

`app_process` 下以 shell 身份运行的探针（不受 hidden API 限制）。统一用法见
[../richtap-hd-vibration.md §3.5](../richtap-hd-vibration.md)。

| 探针 | 作用 |
|------|------|
| `HapticProbe.java` | 主力探针：`DynamicEffect.create(HE)` + `HapticPlayer.start(loop,interval,amp,freq)`，打印 `mEffects` 与 `encapsulate()` |
| `HeInfo.java` | dump HE JSON 解析后的 `DynamicEffect` 内部字段/`encapsulate()` |
| `HeRec.java` | 播放 HE JSON 并用麦克风录 16bit mono PCM |
| `HeStream.java` | 流式播放 HE 并录音（配合 `pcm_to_he.py`） |
| `CurveProbe.java` | 验证 Curve `Frequency` 偏移是否随时间改变音高 |
| `GapProbe.java` | 每 500ms `stop+start`，观察边界是否掉幅 |
| `ReplaceProbe.java` | 连续 `start` 新 effect（不 `stop`）是否替换旧 effect |
| `RestartProbe.java` | 单次长效果 vs 高频 `stop+start` 重投递的响度对比 |
| `UpdateProbe.java` | 验证 `HapticPlayer.updateParameter` 能否不重启改幅度 |
| `HdPitchProbe.java` | 播放 HD 连续效果 + 麦克风 Goertzel 分析真实音高 |
| `AccProbe.java` | 加速度计/传感器相关探针 |
| `HapticGenProbe.java` | `HapticGenerator` / `HapticPlayback`（音频触觉）能力探针 |
| `HapticMaskProbe.java` | `AudioTrack` channel mask（HAPTIC_A 等）探测 |
| `HgMini.java` | `HapticGenerator` 最小可用性检查 |
| `MicProbe.java` | `AudioRecord` 最小缓冲/录音可用性 |
| `ApiDump.java` / `ApiDump2.java` | 反射列出隐藏类的构造器/方法/字段 |

> 部分源码注释在原始文件里即为乱码（历史编码问题），复制时保持原样。

## scripts/

| 脚本 | 作用 |
|------|------|
| `mkjar.ps1` | 用 JDK + Android build-tools 的 `javac`/`d8` 把探针编译为 `classes.dex` 并打包 jar |
| `gen_he.py` | 生成各类 HE 1.0 效果 JSON（多事件、曲线点数、扫频等） |
| `mk_sci.py` | 生成科学计数法曲线（小数强度/频率）的 HE JSON |
| `pcm_to_he.py` | 把 PCM 的包络+主导音高转成多事件 HE（真机标定 `Hz≈170·2^((HE-56)/65)`） |
| `analyze.py` | 对 `.f32` 源信号做频谱/频带能量分析 |
| `analyze_rec.py` | 对录音 PCM 做带通 + 分帧 RMS 分析 |
| `corr.py` | 对比源信号与录音包络的相关性 |

> **注意**：脚本中的路径仍指向原始 `%TEMP%\opencode\haptic\` 与绝对路径，仓库中运行前需按需修改。

## he-samples/

HE 1.0/2.0 效果样例，用于验证引擎约束（事件数、曲线点数、频率偏移、HE 2.0 不被接受等）。
命名大致对应实验变量，例如 `he/n15.json`、`he/n16.json`、`he/n17.json`（事件数）、
`he/v1_2ev_*.json`、`he/v1_32events.json`、`he/v2_patternlist.json`（HE 2.0）、
根目录 `v1_4p.he`、`sweep.he`、`f0.he`/`f50.he`/`f100.he`、`t_*.he`（时长）等。

## decompiled/

RichTap ASDK 2.2.0（`RichTap_ASDK_2.2.0_20250313_NETWORK_release.aar`）的 CFR 反编译结果，
含 `com/apprichtap/haptic/**`、`android/os/DynamicEffect.java`、`android/os/HapticPlayer.java` 等
stub，以及 `summary.txt`。是 [../haptic.md](../haptic.md) 中 type2/type1 backend 对照的来源。

## framework-dumps/

`fw/` 中**小体积**的隐藏类成员 dump（`cls_*.txt`）。原始全量 `alldump.txt`（约 330MB）、
`miui-framework.dis.txt`（约 35MB）、`framework.jar`、`.dex` 等因体积过大**未纳入本仓库**。

## richtapdynamics-extract/

来自逆向工程仓库 `RichTapDynamics/app/libs/extract`：`classes.jar`、`AndroidManifest.xml`、
各 `.class`。是 `decompiled/` 的二进制对照物。原始 `.aar` 未纳入。

---

## 未纳入仓库的附件

以下体积较大或为中间产物，仍保留在原始临时目录：

- `haptic/` 下的录音与中间信号：`*.pcm`、`*.f32`、`feat_*.npy`（合计约 55MB）。
- 编译产物：`*.jar`、`*.dex`、`*.class`、`out_*/`。
- `fw/` 全量 dump 与框架 jar（约 488MB）。

如需完整复现实验，可按 [../richtap-hd-vibration.md §6](../richtap-hd-vibration.md) 的命令重跑探针。
