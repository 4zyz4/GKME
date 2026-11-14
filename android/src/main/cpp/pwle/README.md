# PCM2PWLE (vendored)

Android 16+ 的包络震动（PWLE）需要一个把 PCM 转成 `(amplitude, frequencyHz, duration)`
控制点的转换器。这里直接复用 Google 的
[`android/haptics-tools`](https://github.com/android/haptics-tools) 的 `pcm2pwle` 核心算法，
而不是自己实现希尔伯特包络 / RDP / JND 简化。

## 来源与版本

- 上游仓库：<https://github.com/android/haptics-tools>
- 固定提交：`786f39da20e8f53a7f660ad60dc5855e5ec9d0c1`（2026-09-18）
- 仅保留转换所需的文件（未包含 CLI / 可视化 / 音频文件读取）：
  - `pcm2pwle/common_utils.*`
  - `pcm2pwle/dsp_utils.*`
  - `pcm2pwle/frequency_amplitude_extraction.*`
  - `pcm2pwle/control_point_extraction.*`
  - `pcm2pwle/preset_detection.*`
  - `pcm2pwle/beating_detection.*`
  - `pcm2pwle/converter.*`

## 本仓库新增

- `pwle_jni.cpp`：JNI 入口 `PwleNative.nativeConvert`，输入单声道 PCM（-1..1），
  输出 advanced PWLE 三元组；按分块峰值还原绝对幅度。
- `absl/log/log.h`、`absl/strings/numbers.h`：最小兼容头。上游只用到
  `LOG(...)` 与 `absl::SimpleAtod`，这里用 `std::cerr` / `std::strtod` 替代，
  避免引入整个 Abseil。
- `pocketfft_hdronly.h`：PocketFFT 单头文件（BSD-3-Clause），供希尔伯特变换 FFT 使用。
- 构建见 `android/src/main/cpp/CMakeLists.txt` 的 `gkme_pwle` target（C++20）。

## 许可

- `pcm2pwle/*`：Apache License 2.0，见 `LICENSE.apache-2.0`。
- `pocketfft_hdronly.h`：BSD-3-Clause，许可头保留在文件内。

上游版权归 Google / Android Open Source Project 所有。本项目仅做构建裁剪与 JNI 包装，
算法本身未修改。
