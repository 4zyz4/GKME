# RichTap HD 震动（通过 adb `app_process` 直接驱动线性马达）

> 本文记录一次完整实验：**在不 root 的情况下，用 adb 直接驱动手机线性马达（LRA）播放 RichTap 动态效果（HD 震动）**。
> 目标是给后续集成（GKME / RichTapDynamics）提供可直接复用的实现逻辑。
>
> 实验设备：Xiaomi `23117RK66C`（codename `manet`，Snapdragon 8 Gen 3 `pineapple`，Android 16 / API 36，HyperOS）。
> 未 root，SELinux Enforcing，`adb shell` uid=2000。
> 参考逆向工程：`C:\Users\4zyz4\Desktop\DEV\RichTapDynamics`（RichTap ASDK 2.2.0 AAR）。

---

## 0. TL;DR（结论先行）

- **`adb shell cmd vibrator_manager`**：能驱动马达，支持任意振幅波形（**5ms/step**）、循环、RichTap 预置效果 ID；但**不支持真正的频率控制**（本机 `frequencyProfile=NaN`，`primitives`/`envelope -a` 均 `ignored_unsupported`）。
- **真正的 HD 震动**（音频级、可自定义振幅+频率）走的是 **RichTap 动态效果**，入口是两个 **hidden framework 类**（定义在 `miui-framework.jar`）：
  - `android.os.DynamicEffect`（继承 `android.os.CombinedVibration`）：描述一段带 `Intensity`/`Frequency` 曲线的事件序列。
  - `android.os.HapticPlayer`：`start(loop, interval, amplitude, freq)` 把它交给厂商 HAL。
- 从 adb 触发的方式：**`app_process` + 一个反射调用的小 dex**（shell 不受 hidden API 限制）。普通 APK 受 hidden API 限制，需要豁免（RichTap ASDK 自带）。
- **最大的坑**：设备端 `DynamicEffect.create(String)` 只认 **HE 1.0**（顶层 `Pattern`），喂 **HE 2.0**（顶层 `PatternList`）会得到空效果（`mEffects=[]`），日志显示 "running" 但马达不动。RichTap SDK 内部用 `base.b.a(json, true)` 做 **HE2.0→HE1.0 转换 + 曲线裁成 4 点**。
- 链路（logcat 实测）：`vendor.hardware.vibratorfeature`(AAC RichTap, `AACTrack`/`DynamicEffectThread`) 合成 **PCM 48kHz/16bit** → `AGM StreamRX_Haptics_Playback` → SoundWire → DSP → LRA。

---

## 1. 硬件 / 软件栈

### 1.1 硬件
- Qualcomm **HV-Haptics**：`qcom-hv-haptics` 驱动，PM8550B `qcom,hv-haptics@f000`；SoundWire 从设备 `swr-haptics`（`pm8550b-swr-haptics.2f0170220`）。
- 字符设备 `/dev/qcom_haptic`（major 10, minor 109，权限 `crw-rw-rw-`，SELinux 类型 `aac_richtap_dev_device`）。
  - 虽 world-writable，但 **shell 无法 open**（`dd: /dev/qcom_haptic: Permission denied`，SELinux 拒绝）。
- sysfs：`/sys/class/qcom-haptics/*`（`lra_frequency_hz`、`lra_impedance`、`primitive_duration`、小米私有 `mi_comp_lra`/`mi_f0_ctrl`/`mi_vmax_ctrl` 等）对 shell **全部 Permission denied**（读和写都拒绝）。

### 1.2 框架 / 厂商服务
```
adb shell service list | grep -iE 'vibrat|haptic'
  android.frameworks.vibrator.IVibratorControlService/default
  android.hardware.vibrator.IVibrator/vibratorfeature        # Xiaomi AIDL HAL
  external_vibrator_service
  vibrator_manager                                           # 框架 IVibratorManagerService
```
- 厂商服务进程：`vendor.xiaomi.hardware.vibratorfeature.service`（uid system）。
- 厂商 HIDL：`vendor.hardware.vibratorfeature.IVibratorExt`（见 `miui-services.jar`）。
- 关键属性：`sys.haptic.motor=linear`、`sys.haptic.dynamiceffect=true`、`sys.haptic.dynamiceffect.richtap=true`、`sys.haptic.version=2.0`。
- 预置效果表：`/vendor/etc/Hapticsconfig.xml`（Qualcomm predefined effect 0-5 = CLICK/DOUBLE_CLICK/TICK/THUD/POP/HEAVY_CLICK 的固件波形参数）。

### 1.3 框架振动能力（`dumpsys vibrator_manager`）
```
VibratorInfo:
  capabilities = [ON_CALLBACK, PERFORM_CALLBACK, AMPLITUDE_CONTROL]
  supportedEffects = [CLICK, DOUBLE_CLICK, TICK, THUD, POP, HEAVY_CLICK, 6,7,...,521]   # 6..521 是 RichTap 效果目录
  supportedPrimitives = []            # 不支持 primitives
  frequencyProfile = {NaN...}         # 无频率控制
```
浏览器/游戏已在使用振幅波形，例如 `com.limelight`（Moonlight）`Step=80ms(amplitude=0.86)`、`com.hypergryph.endfield` 5/10ms 细粒度步进。

---

## 2. 方案 A：`cmd vibrator_manager`（框架级，无需 root，无频率）

`vibrator_manager` 注册了 shell 命令（注意：`oneshot/waveform/...` 是 `synced/combined/sequential` 的子命令；`-B`/`-f` 放在 `synced` 之后、效果之前）：

```sh
adb shell cmd vibrator_manager list
adb shell cmd vibrator_manager synced oneshot -a <时长ms> <振幅1-255>
adb shell cmd vibrator_manager synced waveform -a <时长> <振幅> <时长> <振幅> ...
adb shell cmd vibrator_manager synced waveform -c -a ...      # continuous，自动插值成 5ms 步进
adb shell cmd vibrator_manager synced waveform -r 0 -a ...    # 循环
adb shell cmd vibrator_manager synced prebaked <id>           # RichTap 预置效果（6..521）
adb shell cmd vibrator_manager feedback <constant>
adb shell cmd vibrator_manager cancel
```

实测（dumpsys 回读）：
| 输入 | 实际播放 |
|---|---|
| `oneshot -a 120 200` | `Step=120ms(amplitude=0.78)` |
| `waveform -a 40 60 40 140 ...` | 每段 `Step=40ms(amplitude=...)` |
| `waveform -c -a ...` | 自动拆成 **`Step=5ms`** 序列（Ramp→5ms 插值） |
| 手工 5ms×100 段 | 517ms 完整播放（**100 段上限**） |
| `waveform -r 0 ... -B` | 循环，`cancel` 才停 |
| `prebaked 8` | `Prebaked=8(MEDIUM, no fallback)` → 原生 RichTap |
| `primitives` / `envelope -a` | `ignored_unsupported` ✗ |

**局限**：振幅 1-255、5ms 步进、循环可用；但 **无频率控制**（`frequencyProfile=NaN`，`-f`/PWLE 被忽略）。这是"高清振幅波形"，但不是"音频级 HD"。

---

## 3. 方案 B：RichTap 动态效果（音频级 HD，可自定义振幅+频率）★

### 3.1 设备隐藏 API（`miui-framework.jar`）

```java
// android.os.DynamicEffect extends android.os.CombinedVibration implements Parcelable
static DynamicEffect create();                       // 空
static DynamicEffect create(String heJson);          // 从 HE JSON（需 HE1.0，见 §3.3）
static DynamicEffect create(String heJson, float scale);
static DynamicEffect create(int[] encapsulate);      // 从序列化 int[]
static DynamicEffect.PrimitiveEffect createContinuous(float intensity, float sharpness, float freq);
static DynamicEffect.PrimitiveEffect createTransient(float intensity, float sharpness);
static DynamicEffect.Parameter createParameter(int type, float[] times, float[] values); // INTENSITY=0, SHARPNESS=1
DynamicEffect addPrimitive(float relTime, PrimitiveEffect);
DynamicEffect addParameter(float relTime, Parameter);
int[] encapsulate();
long  getDuration();
// 字段: globalIntensity, globalSharpness, mLoop, mInterval, version, mEffects, mGlobalCurve
// 常量: MAX_INTENSITY=100, MAX_FREQ=100, MAX_POINT_COUNT=16, MAX_EVENT_COUNT=16, PARCEL_TOKEN_DYNAMIC=5

// android.os.HapticPlayer
static boolean isAvailable();
static String  getVersion();      // 本机 "1.0"
HapticPlayer();
HapticPlayer(DynamicEffect);
void setDataSource(DynamicEffect);
void start();
void start(int times);
void start(int loop, int interval, int amplitude);            // amplitude 0-255
void start(int loop, int interval, int amplitude, int freq);  // ← 自定义振幅+频率入口
void start(DynamicEffect);
void stop();
// 字段: mService(IVibratorManagerService), mToken(Binder), mEffect, mPackageName, displayId, mAttributes, mAvailable
// 常量: USAGE_DYNAMICEFFECT = -2
```

> 另有旧封装 `miui.os.HapticPlayer` / `miui.os.DynamicEffect`（`start()` / `start(DynamicEffect)`，无参数化）。

### 3.2 运行要求与坑
1. **hidden API**：`android.os.*` 这些类 `hiddenapi: BLOCKED`。普通 APK 调用会被拦；**`app_process`（shell uid）不受限，实测可直调**。App 内需 hidden-API 豁免或直接用 RichTap ASDK。
2. **`mPackageName` 必须非空**：`app_process` 下构造 `HapticPlayer` 得到 `mPackageName=null`，`start()` 会在 `HapticPlayer.java:146` 抛 NPE。**启动前用反射把 `mPackageName` 字段设为非空**（如 `"com.android.shell"`）。
3. `stop()` 在 `HapticPlayer.java:166`（`HapticPlayer$3` → `cancelVibrate`）会打印一个 NPE 栈，但不会阻止停止（实测 `dynamic effect canceled`）。

### 3.3 HE 格式：设备只认 HE 1.0（最关键）
设备 `DynamicEffect.create(String)` 只解析 **HE 1.0**。喂 HE 2.0 会得到空效果：

| 输入 | `mEffects` | `encapsulate()` | 结果 |
|---|---|---|---|
| HE2.0（顶层 `PatternList`, `Metadata.Version=2`） | `[]` | 6（仅表头） | ❌ 马达不动 |
| HE1.0（顶层 `Pattern`, `Metadata.Version=1`, 曲线 4 点） | `[PrimitiveEffect]` | 34/90（含曲线） | ✅ 震，且频率可感知 |

**HE 1.0 模板**（顶层 `Pattern`；每个 `continuous` 事件曲线裁成 **4 点**；`Intensity` 0-100，`Curve[].Intensity` 0-1，`Curve[].Frequency` 为偏移）：
```json
{
  "Metadata": { "Created": "gkme", "Description": "hd", "Version": 1 },
  "Pattern": [
    { "Event": {
        "Type": "continuous", "RelativeTime": 0, "Duration": 800,
        "Parameters": {
          "Frequency": 60, "Intensity": 100,
          "Curve": [
            { "Frequency": 0, "Intensity": 0.0, "Time": 0 },
            { "Frequency": 0, "Intensity": 1.0, "Time": 120 },
            { "Frequency": 0, "Intensity": 1.0, "Time": 600 },
            { "Frequency": 0, "Intensity": 0.0, "Time": 800 }
          ] } } }
  ]
}
```

**HE 2.0 → HE 1.0 转换**（SDK `com.apprichtap.haptic.base.b.a(String, boolean)` 的逻辑）：
1. 曲线裁点：`trim16pTo4p` —— 每条 `Curve` 压成 4 点。
2. 把 `PatternList[].AbsoluteTime` 加到该段内每个事件的 `RelativeTime` 上。
3. 顶层从 `PatternList[].Pattern` 拍平成 `Pattern`；写 `Metadata.Version=1`。
4. 若事件 `Frequency == -1` 则设为默认 56。
5. 丢弃 `Index`。

### 3.4 encapsulate() 序列化格式（实测观测，字段名部分为推断）
以 3 段扫频（`sweep.he`）为例：
```
[90, 1, 100, 50, 0,  3,
   1, 0, 100, 20, 800, 2, 0, 0, 4,  0,0, 120,100, 600,100, 800,0,  1, 0, 4,  0,0, 120,0, 600,0, 800,0,
   1, 900, 100, 100, 800, <同上>,
   1, 1800, 100, 60, 800, <同上> ]
```
推断：
- 全局头：`[总长度, 格式版本=1, globalIntensity, globalSharpness, loop, 事件数]`
  - `globalIntensity = amplitude/255*100`（实测 100→39、200→78、255→100）
  - `globalSharpness` 默认 50（`start(...,freq)` 未改变它）
- 每事件：`[type(1=continuous), relativeTime, intensity(0-100), frequency(0-100), duration, 2, 0, 0, 点数, (强度曲线: t,v …), 1, 0, 点数, (频率曲线: t,v …)]`

### 3.5 从 adb 触发的完整流程
**（1）编译探针 dex（Windows PowerShell，JDK + Android SDK build-tools）**
```powershell
javac --release 8 -nowarn -d classes HapticProbe.java
d8 --release --lib "$env:LOCALAPPDATA\Android\Sdk\platforms\android-36\android.jar" `
   --output . classes\HapticProbe.class
# 把 classes.dex 打进 hp.jar（可用 [System.IO.Compression.ZipArchive] 写入名为 classes.dex 的条目）
```
**（2）推送并运行**
```sh
adb push hp.jar /data/local/tmp/
adb push sweep.he /data/local/tmp/
adb shell CLASSPATH=/data/local/tmp/hp.jar app_process /system/bin HapticProbe @/data/local/tmp/sweep.he 1 0 255 60 3200
#              参数: <@HE文件> <loop> <interval> <amplitude 0-255> <freq 0-100> <等待ms>
```
**（3）探针源码（HapticProbe.java，反射实现，免编译期依赖）**
```java
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class HapticProbe {
    static void log(String s){ System.out.println("[HapticProbe] "+s); }
    static void setField(Object o,String n,Object v){
        try{ Field f=o.getClass().getDeclaredField(n); f.setAccessible(true); f.set(o,v); }
        catch(Throwable t){ log("set "+n+" failed: "+t); }
    }
    public static void main(String[] a) throws Exception {
        Class<?> hp=Class.forName("android.os.HapticPlayer");
        Class<?> de=Class.forName("android.os.DynamicEffect");
        log("isAvailable="+hp.getMethod("isAvailable").invoke(null)
              +" version="+hp.getMethod("getVersion").invoke(null));

        String json=new String(Files.readAllBytes(Paths.get(a[0].substring(1))), StandardCharsets.UTF_8);
        Object effect=de.getMethod("create",String.class).invoke(null,json);
        log("effect="+effect+" mEffectsSize="
              +((java.util.List<?>)de.getField("mEffects").get(effect)).size());
        log("encapsulate.len="+((int[])de.getMethod("encapsulate").invoke(effect)).length);

        Object player=hp.getConstructor(de).newInstance(effect);
        setField(player,"mPackageName","com.android.shell");   // ★ 必须，否则 start() NPE

        int loop=Integer.parseInt(a[1]), interval=Integer.parseInt(a[2]);
        int amp=Integer.parseInt(a[3]),  freq=Integer.parseInt(a[4]);
        int wait=Integer.parseInt(a[5]);
        hp.getMethod("start",int.class,int.class,int.class,int.class)
          .invoke(player,loop,interval,amp,freq);
        Thread.sleep(wait);
        try{ hp.getMethod("stop").invoke(player);}catch(Throwable t){log("stop "+t);}
    }
}
```

### 3.6 验证证据（logcat / dumpsys）
```
D vendor.hardware.vibratorfeature: play effect : 0x... loop: 0 interval: 0 amplitude -1
D vendor.hardware.vibratorfeature: idx :0..33 , effect: <encapsulate 内容>
D vendor.hardware.vibratorfeature: set Global intensity 1.000000, sharpness 0.500000
D vendor.hardware.vibratorfeature: merge intensity curve and freq curve to HedTrack
D vendor.hardware.vibratorfeature: num of point 4        # 曲线点数
D DynamicEffectThread: wake / continue
I AGM: graph: print_graph_alias: ... StreamRX_Haptics_Playback_DeviceRX_Haptics_Device
D AGM: graph_module: configure_hw_ep_media_config: rate 48000 bw 16 ch 1   # PCM 48k/16bit
I gsl: gsl_send_spf_cmd: ... opcode 0x1001002            # graph_start 到 DSP
D vendor.xiaomi.hardware.vibratorfeature.service: Hed process Ok / AACTrack created id:N
D vendor.xiaomi.hardware.vibratorfeature.service: no track has data left, len : <字节数>
```
dumpsys：`usage: MEDIA | com.android.shell | reason: DynamicEffect`。

> 注意：**只看 "running" 会误判**。HE2.0 时同样显示 running，但 `vendor ... Hed no bytes`、`no track has data left, len:0`（没数据 → 不震）。判断是否真有效，看 `mEffects` 是否非空 + `HedBuffer size`/`genBytes` 是否 > 0。

---

## 4. RichTap ASDK 2.2.0 逆向要点（`RichTapDynamics/app/libs`）

反编译：`java -jar cfr.jar extract/classes.jar --outputdir out`（CFR 0.152）。

`RichTapUtils`（`com/apprichtap/haptic/RichTapUtils.java`）按优先级选 backend：
| type | 类 | 底层 |
|---|---|---|
| 2 `RichTapPerformer` | `player/d.java` | 反射 `android.os.RichTapVibrationEffect` / `richtap.os.PhonyVibrationEffect` 的 `createPatternHeWithParam/createPatternHeParameter/createHapticParameter/createEnvelope` |
| 1 `TencentPerformer` | `player/e.java` | **`DynamicEffect.create(base.b.a(json,true))` + `new HapticPlayer(effect)` + `start(1,0,255)`** |
| 0 `GooglePerformer` | `player/b.java` | 降级 `VibrationEffect.createWaveform/composition`（频率丢失） |

- 本机 **没有 `RichTapVibrationEffect`**（framework 全量字符串搜索为 0），走 **type 1**。
- HE 解析/转换在 `com/apprichtap/haptic/base/b.java`：`create` 前会 `a(heString,true)`（§3.3 的 2.0→1.0）。
- SDK 直接引用 `android.os.HapticPlayer`/`DynamicEffect`（编译用的 stub 在 `extract/android/os/*.class`，真实现在 ROM）。

---

## 5. 集成到 GKME 的建议

### 5.1 运行位置的选择
- **adb/`app_process`（shell）**：免 hidden-API 限制，最简单，适合调试/电脑端下发（GKME 的 USB/ADB 模式天然契合）。
- **App 内**（GKME 本机模式/被控端）：
  - 方案①：直接集成 RichTap ASDK（`app/libs` 的 AAR），但它对 hidden API 的访问依赖 ROM 支持，且是闭源商业 SDK。
  - 方案②：App 内用反射调用 `android.os.DynamicEffect`/`HapticPlayer`，**需先做 hidden-API 豁免**（`VMRuntime.setHiddenApiExemptions` 等，本身也是隐藏 API，常规做法是用 `Unsafe` 绕过），并保证 `mPackageName` 有值（App 内天然有包名，无需手动设）。
  - 方案③：把这套驱动放进一个 **Shizuku user service**（以 shell 身份运行）里，由 App 通过 Shizuku 调用——与 GKME 现有 uinput 方案一致，且 shell 身份可直调隐藏 API。

### 5.2 待实现模块
1. **HE2.0→HE1.0 转换器**：把 `Car Ignite.he` 这类真实效果的曲线裁成 4 点、`PatternList`→`Pattern`。
2. **参数映射**：
   - 振幅：游戏 rumble 强度 → `Parameters.Intensity`(0-100) 或 `Curve[].Intensity`(0-1)；帧级 → `start(..., amplitude, ...)`。
   - 频率：音频包络/谱心 → `Parameters.Frequency`(0-100) 或 `Curve[].Frequency` 偏移。
3. **流式播放**：`getDuration()` 返回 -1（厂商不报时长），需要按自算时长分块 `start()`，或借助 `addParameter` + `HapticPlayer` 的实时更新（本机 `HapticPlayer` 无 `updateParameter`，只能重启）。
4. **左右/多马达**：单马达设备（`vibratorIds=[0]`）；多马达需 `CombinedVibration` 组合。

### 5.3 关键常量速查
```
amplitude → globalIntensity = amplitude/255*100
HE Intensity: 事件 Parameters.Intensity 0-100 ; Curve.Intensity 0-1
HE Frequency: 事件 Parameters.Frequency 0-100 ; Curve.Frequency 偏移
HapticPlayer.getVersion() = "1.0" (RichTap core)
USAGE_DYNAMICEFFECT = -2
```

---

## 6. 可复现命令速查
```sh
# 设备信息
adb devices -l
adb shell getprop ro.product.device          # manet
adb shell service list | grep -iE 'vibrat|haptic'
adb shell dumpsys vibrator_manager

# 方案 A（无 root，无频率）
adb shell cmd vibrator_manager synced -f oneshot -a 2000 255
adb shell cmd vibrator_manager synced -f waveform -a 40 60 40 140 40 220 40 140 40 60
adb shell cmd vibrator_manager synced -f prebaked 8

# 方案 B（HD，需自备 hp.jar，见 §3.5）
adb push hp.jar /data/local/tmp/
adb shell CLASSPATH=/data/local/tmp/hp.jar app_process /system/bin HapticProbe @/data/local/tmp/sweep.he 1 0 255 60 3200

# 观察
adb shell "dumpsys vibrator_manager | grep -i -e dynamiceffect -e shell"
adb logcat -d | grep -iE 'vibratorfeature|DynamicEffectThread|AACTrack|AGM|gsl'
```

---

## 7. 附件（已随仓库持久化）
- 附件索引与说明：[`richtap/README.md`](richtap/README.md)
- 探针源码：`richtap/probes/`（`HapticProbe.java`、`HeInfo.java`、`HeRec.java`、`HeStream.java` 等）
- 脚本：`richtap/scripts/`（`gen_he.py`、`pcm_to_he.py`、`analyze_rec.py`、`mkjar.ps1` 等）
- HE 样例：`richtap/he-samples/`（`v1_4p.he`、`sweep.he`、`f0.he`/`f100.he`、`he/*.json` 等）
- 反编译源码：`richtap/decompiled/`
- 框架隐藏类 dump：`richtap/framework-dumps/`
- 逆向工程产物：`richtap/richtapdynamics-extract/`（原 `RichTapDynamics/app/libs/extract`）
- 设备预置效果表：`richtap/Hapticsconfig.xml`（原 `/vendor/etc/Hapticsconfig.xml`）

> 原始录音（`.pcm`/`.f32`，约 55MB）、编译产物（`jar/dex/class`）与框架全量 dump（约 488MB）体积过大，
> 未纳入仓库；复现命令见 §6。

---

## 8. 遗留问题 / 下一步
- [ ] 精确确认 `encapsulate()` 各字段语义（用 `createContinuous`/`createTransient`/`createParameter` 单变量对照）。
- [x] `start(loop, interval, amplitude, freq)` 第 4 参 `freq` 的作用路径（频率编码在事件 `Parameters.Frequency`；无参 `start()` 时 HAL 收到 `amplitude=-1`）。
- [ ] 尝试 `transient` 事件 + `createParameter(SHARPNESS, ...)` 做更锐利的"点击感"。
- [x] 评估 App 内 hidden-API 豁免可行性，或走 Shizuku user service（已走 Shizuku user service）。
- [x] 实时 PCM→HE 转为真正的流式（见 §9：多事件分块 + 无参 `start()` 投递）。

---

## 9. 多事件分块：从马达近乎无损播放 PCM（第二轮实验）

> 目标：把语音线圈 PCM 的**包络 + 主导音高**以尽可能高的保真度从 LRA 还原。
> 关键结论全部来自真机 HAL 日志（`vendor.hardware.vibratorfeature`）与逐变量对照。

### 9.1 物理前提
LRA 是**窄带共振器**（本机 f0≈170Hz，Q≈10），无法复现宽带音频；可忠实复现的只有
**幅度包络**（机械时间常数约 20–50ms）与**有限范围内的音高/锐度**（约 89–271Hz）。
因此"近乎无损"= 把 PCM 的**包络→Intensity**、**带内主频→HE Frequency**，以高时间分辨率
投递给引擎；超出马达频段的音高按八度搬入（`RichTapFrequency.shiftIntoRange`）。

### 9.2 引擎的硬约束（逐条验证）
| 约束 | 证据 |
|---|---|
| 单条效果**最多 16 个事件** | 16 事件正常（`HedBuffer size 272`）；17/20/32/80 事件被 HAL **整条丢弃**（无 `merge`/`HedBuffer`，直接 `Vibrator off`）= `MAX_EVENT_COUNT` |
| 每个事件 `Curve` **必须恰好 4 个点** | 2 点曲线 → `generate_disp_envelope_wav Invalid time param` → `Hed first frame get fail` → `no track has data left` → **马达不转**；4 点正常 |
| 单个事件 `Duration ≤ 5000ms` | 6000ms → 反序列化后 0 事件 |
| 顶层 `PatternList`（HE 2.0）不支持 | 解析后 `mEffects.size=0`，只有 HE 1.0 `Pattern[]` |
| 多事件由引擎内部调度 | 16 事件扫频效果主频随编程平滑变化，段间无 `stop()+start()` 凹陷 |

`DynamicEffect` 常量：`MAX_EVENT_COUNT=16`、`MAX_POINT_COUNT=16`（本机仍裁到 4）、
`MAX_PATERN_EVENT_LAST_TIME=5000`、`MAX_PATERN_LAST_TIME=50000`、`MAX_INTENSITY/FREQ=100`。

### 9.3 集成实现（GKME）
- `haptic/PcmHeEncoder.kt`：把逐帧 `(amp01, HE)` 按 `EVENT_MS` 切成事件（每块事件数由调用方给出），
  每事件压成 **4 点曲线**（幅度均值 + 相对事件基频的偏移）。纯 JVM，可单测。
- `haptic/RichTapHe.kt`：新增 `pattern(List<Event>)` 生成多事件 `Pattern` JSON。
- `haptic/RichTapFrequency.kt`：新增 `shiftIntoRange(hz)`（按八度搬入剖面）。
- `haptic/HdPcmStreamer.kt`：`submit(left,right,pitchHz)` 累计采样，满块后经
  `HapticInjector.startEffect(json)` 投递（**无参 `start()`**，避免全局 amplitude/freq 覆盖事件参数）。
  取 `EVENT_MS=50 × EVENTS_PER_CHUNK=4`，首个分块缓冲延迟 ≈ 0.2s，每块 16 点
  （每事件 **4 点 ≈ 16.7ms 间隔**分辨率）。
- `controlled/RemoteHapticService.kt` + `IHapticService.aidl`：新增 `startEffect(String)`
  （无参 `start()`）；`HapticInjector.startEffect` 在旧版用户服务上回退 `startPattern`。

### 9.4 实测手法
- **HAL 日志**是最可靠的结构验证：`play effect` / `merge … HedTrack` / `HedBuffer size: N`
  （N=17×事件数）/ `AACTrack created` / `Hed process Ok`；失败时看 `Invalid time param` /
  `Hed first frame get fail` / `no track has data left`。
- 麦克风录音信噪比差（系统性噪声，多次平均无效），**不足以验证细粒度时序**；以此交叉验证。
- 探针与产物：见 [`richtap/README.md`](richtap/README.md)（`probes/` 含 `HeInfo`、`HeRec`、`HeStream`；`scripts/` 含 `pcm_to_he.py`、`analyze_rec.py`）。

### 9.5 延迟、代价与边界
> 延迟是第一优先级：**分块时长 = 每次起振的首帧延迟**。原 `50 × 16 ≈ 0.8s` 太大，
> 现改为 `50 × 4 ≈ 0.2s`（起振延迟压到 1/4）。
- 控制分辨率 = 每事件 4 点（`EVENT_MS/3` ≈ 16.7ms 间隔）；与 `EVENTS_PER_CHUNK` 无关。
- 首个分块缓冲延迟 = `EVENT_MS × EVENTS_PER_CHUNK`。块越短延迟越低，但分块边界（内部
  `stop()+start()`，有轻微掉幅）越频繁：原每 0.8s 一次 → 现每 0.2s 一次。这是
  **延迟↔平滑取舍**，可按需调 `EVENTS_PER_CHUNK`/`EVENT_MS`。
- `MAX_DT_MS` 需 < `EVENT_MS`。`EVENT_MS=50` 是否可进一步缩短、以及 HAL 可接受的最短
  事件时长，待真机标定。

---

## 10. 低频震动：用「快速发送短促震动」模拟低于马达下限的频率

> 目标：让 HD 震动也能表现**低频**（如 20–80Hz 的“慢/闷”手感）。LRA 可用频段约
> 87–225Hz（见 §9.1），直接给引擎低于下限的 `Frequency` 会被截断成最低频，丢失低频。

### 10.1 方法

以目标低频 `f` 的**周期** `T = 1000/f`（ms）为间隔，重复发送**短促脉冲**；每个脉冲是一条很短
的 `continuous` 事件（快速起振后立即衰减）。脉冲的**重复频率即目标低频**，因此触感是
“每 `T` ms 来一下”，而不是连续的高频嗡嗡声。每个脉冲的载波用谐振点（最大能量），
强度按引擎的次方律（`RichTapEngine.amplitudeToCurve`）换算成曲线峰值。

```
目标 40Hz → 周期 25ms → 每 25ms 一个约 9ms 的脉冲（0→峰→衰减→0）
```

### 10.2 实现（GKME）

- `haptic/RichTapLowFreq.kt`（纯 JVM，可单测）：
  - `supports(hz)`：`hz < RichTapEngine.MIN_HZ`（≈86.7Hz）时走脉冲串。
  - `periodMs` / `pulseMs`（占空比 `PULSE_RATIO=0.35`，最小 3ms）/ `pulseCount` / `coverageMs`。
  - `pattern(freqHz, durationMs, strength, carrierHe)`：最多 `MAX_PULSES=16` 个脉冲事件，
    每事件 4 点曲线（`0 → 峰 → 0.35·峰 → 0`）。
- `haptic/PhoneHdHaptics.kt`：
  - `playMotors(..., frequencyHz)` 检测到 `frequencyHz` 低于下限时，改用脉冲串（`wantLowHz`），
    并经无参 `startEffect()` 投递（保留事件自身参数）。
  - 新增通用入口 `playLowFrequency(strength, frequencyHz, durationMs)`，供其他 HD 通路复用。
  - 游戏 rumble **不做低频脉冲串分段**（分段会让大小马达听感变成一顿一顿的脉冲）：低频马达
    （强震动）用 ≈140Hz、高频马达（弱震动）用 ≈210Hz 的**连续**效果（`frequencyForMotors`）。
  - 脉冲串单条覆盖时长 = `coverageMs`，调度线程按 `coverage×0.9` 定时重投递以延续播放。

### 10.3 约束与取舍

- 单条效果最多 16 个事件 → 单条脉冲串最多覆盖 16 个周期（如 40Hz ≈ 400ms、20Hz ≈ 750ms）；
  更长时长由 `PhoneHdHaptics` 定时重投递拼接。
- 目标频率越接近下限，周期越短、覆盖越短、重投递越频繁；过低的频率（长周期）反而最稳。
- 脉冲占空比 `PULSE_RATIO` 与载波 `carrierHe` 可按手感微调：占空比大→更接近连续震动，
  小→更“点状”。

---

## 11. 幅度-频率补偿：让不同频率下的实际振幅一致

> 现象（真机手感）：**同一个振幅数值，在不同 HE 频率下的实际振幅不同**。HE 56（≈170Hz，
> 谐振点）最大，更高或更低的频率都会变小。需要**增大高频和低频的驱动幅度**来平衡差异。

### 11.1 模型

LRA 是欠阻尼受迫谐振器。恒定驱动力下位移幅度

```
X(r) ∝ 1 / sqrt((1-r²)² + (2ζr)²)     r = f/f0,  2ζ = 1/Q
```

归一化到谐振（`r=1`）为 1.0，其余处 < 1。补偿增益取其倒数：

```
gain(he) = 1 / resonanceResponse(he)   ∈ [1, MAX_FREQ_COMPENSATION]
```

`f0 = RESONANCE_HZ`（170Hz），`f = heToHz(he)`，`Q = MECHANICAL_Q`（默认 10，真机可标定）。
谐振 HE 56 处 `gain = 1`，偏离时 >1、按曲线强度上限封顶。

### 11.2 实现（GKME）

- `haptic/RichTapEngine.kt`（纯 JVM）：
  - `resonanceResponse(he, q)`：归一化位移响应。
  - `frequencyCompensation(he, q, maxBoost)`：补偿增益。
  - `compensateNormalized(a, he)` / `compensate255(amp, he)`：对目标幅度做补偿（0..1 封顶）。
  - 常量 `MECHANICAL_Q`、`MAX_FREQ_COMPENSATION`。
- `haptic/PcmHeEncoder.kt`：每个控制点按 **事件基频 + 曲线偏移** 得到实际驱动 HE，补偿后再走
  `amplitudeToCurve`（谐振点 HE 56 不变，偏离时抬升）。
- `haptic/RichTapLowFreq.kt`：脉冲峰值按 `carrierHe` 补偿。
- `controlled/HapticInjector.kt` / `haptic/PhoneHdHaptics.kt`：连续效果补偿全局 amplitude，
  点击/`playEffect` 补偿强度，使所有 HD 通路口径一致。

### 11.3 取舍

- HE 曲线强度上限为 1.0：**谐振点满幅时没有余量**，偏离谐振无法再抬升（高幅附近补偿无效）。
  如需全频段都能补偿，可在调用处把非补偿基准整体压低（本轮未做，保持谐振点最大手感）。
- `Q` 越小，谐振越平、偏离衰减越慢、所需补偿越温和；`Q` 越大相反。两者均建议按真机手感标定。
- 预置效果（`playPrebaked`）的 HE JSON 由 SDK 提供，未参与本补偿。

---

## 12. 短促瞬态响应：≤50ms 的短音还原成单次强震

> 现象：PC 会向手机下发**时长极短（≤50ms）、且总是从静音突变而来**的音频（“咔哒/敲击”）。
> 按常规包络编码，它们会被 200ms 分块稀释成一次很弱的起振，手感发闷。

### 12.1 方法

在 `PcmHeEncoder` 内识别“**静音 → 短促有声 → 静音**”的瞬态，除常规包络外，再于分块内
对应时刻**额外并入一条满强度短事件**（与 §10 的低频脉冲同构：一条 4 点 `continuous` 曲线，
`RelativeTime` 为瞬态起点、时长很短），一次瞬态只出一条。

判定条件（`PcmHeEncoder.burstMs` > 0 时启用）：

- 静音阈值 `BURST_SILENCE_AMP = 0.04`：低于它视为静音，可**重新武装**检测；
- 最小峰值 `BURST_MIN_AMP = 0.06`：过滤本底噪声（真机录音实测短音 RMS 归一化后约
  0.10–0.15，早期取 0.15 会全部漏检）；
- 有效时长上限 `BURST_MAX_MS = 60`：从首次起振到**最后一个有效峰**，超过即判为持续音；
  目标音源 ≤50ms，多出的 10ms 是 RMS 帧量化余量；
- 内部凹陷容限 `BURST_GAP_MS = 40`：短音常为“主峰 → 短暂凹陷（真机约 20–35ms）→ 余响/回声”，
  凹陷不超此值仍算同一次瞬态，避免一次短音被拆成两条脉冲；
- `BURST_REFRACTORY_MS = 40`：抑制窗，避免一次瞬态被幅度抖动拆成多条。

关键状态机点：瞬态被判定为持续音后会**解除武装**，只有再次观察到静音才允许识别下一次，
避免持续音在每个分块边界被反复当作候选。`HdPcmStreamer` 在静音时若编码器仍处于瞬态
（`isBurstActive`），会**继续喂入静音样本**让编码器跨过内部凹陷，待其判定结束后再 `flush()`；
其余静音仍立即 `flush()`。

### 12.2 实现（GKME）

- `haptic/PcmHeEncoder.kt`：新增构造参数 `burstMs`（0 = 关闭）与 `detectBurst`/`registerBurst`/
  `finalizeBurst`；额外短事件与音量突增强调统一为 `Pulse` 列表，经 `appendPulses` 追加进
  同一 `Pattern`（受 `MAX_EVENTS=16` 约束）。纯 JVM，可单测。
- `haptic/HdPcmStreamer.kt`：`BURST_MS = 8`，传给编码器；静音时若 `encoder.isBurstActive`
  则继续喂入静音样本以跨过内部凹陷（否则立即冲刷）。

### 12.3 真机录音复核（一次 8 脉冲样例）

一段 5.9s 的手机录屏音频（8 次短音）分析结论：

- 每次短音总跨度约 **49ms**（主峰 + 约 20–35ms 凹陷 + 余响），与“≤50ms 单次瞬态”一致；
- 各次按 10ms 帧 RMS 归一化后峰值约 **0.10–0.46**（多数 0.10–0.15），低于早期门限 0.15 → 漏检；
- 早期“首个静音帧即 `flush()`”实现会在凹陷处结束瞬态，即使门限降低也会把一个事件拆成两条；
- 采用“门限 0.06 + 凹陷容限 40ms + 静音桥接”后，可稳定得到 **8** 条脉冲（见本节参数）。

### 12.4 取舍

- 满强度（曲线 1.0）优先“脆/强”，不跟随瞬态实际幅度；如需区分轻重可改为按峰值缩放。
- `burstMs` 越短越“点”，但过短可能不被 HAL 接受（需真机标定，参考 §10 的 `MIN_PULSE_MS`）。
- 跨 200ms 分块边界的瞬态（起点落在分块末尾 <50ms 内）会被丢弃：受既有分块机制限制，
  且 PC 侧这类短音总是从静音而来，实际不会出现。


