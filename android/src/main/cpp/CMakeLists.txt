cmake_minimum_required(VERSION 3.22.1)

project(gkme_sdl C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# SDL3 is provided by the SDL3-3.4.20-input.aar prefab package (input-only build).
find_package(SDL3 REQUIRED CONFIG)

add_library(gkme_sdl SHARED
        sdl_bridge.cpp
        haptic_native.c)

target_include_directories(gkme_sdl PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/sdl/include)

target_link_libraries(gkme_sdl PRIVATE
        SDL3::SDL3
        android
        log)

# 手机音频输出：直接使用 AAudio（不经过 SDL3）。Kotlin 层在打开失败时回退到 AudioTrack。
add_library(gkme_audio SHARED
        audio_out.cpp)

target_link_libraries(gkme_audio PRIVATE
        aaudio
        android
        log)

# 被控端虚拟手柄：运行在 Shizuku UserService 进程，直接操作 /dev/uinput 与 /dev/uhid。
# uinput 路径伪装 Xbox One S；uhid 路径用真实 HID 描述符创建 DS4/DualSense/
# Switch Pro 与键鼠。
add_library(gkme_uinput SHARED
        uinput_gamepad.c
        uhid_input.c)

target_link_libraries(gkme_uinput PRIVATE
        android
        log)

# Android 16+ PWLE 包络震动：把 PCM 转成 advanced PWLE 控制点。
# 算法来自 Google android/haptics-tools（Apache-2.0），见 pwle/README.md。
add_library(gkme_pwle SHARED
        pwle/pwle_jni.cpp
        pwle/pcm2pwle/beating_detection.cc
        pwle/pcm2pwle/common_utils.cc
        pwle/pcm2pwle/control_point_extraction.cc
        pwle/pcm2pwle/converter.cc
        pwle/pcm2pwle/dsp_utils.cc
        pwle/pcm2pwle/frequency_amplitude_extraction.cc
        pwle/pcm2pwle/preset_detection.cc)

# pwle/ 同时提供 pcm2pwle/*、absl/* 最小兼容头与 pocketfft 单头文件。
target_include_directories(gkme_pwle PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/pwle)

target_link_libraries(gkme_pwle PRIVATE
        android
        log)

# 上游 pcm2pwle 在 lambda 中捕获 structured binding（C++20 才标准化）。
set_target_properties(gkme_pwle PROPERTIES
        CXX_STANDARD 20
        CXX_STANDARD_REQUIRED ON)
