cmake_minimum_required(VERSION 3.22.1)

project(gkme_sdl C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# SDL3 is provided by the SDL3-3.4.16.aar prefab package.
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

# 被控端虚拟手柄：运行在 Shizuku UserService 进程，直接操作 /dev/uinput。
add_library(gkme_uinput SHARED
        uinput_gamepad.c)

target_link_libraries(gkme_uinput PRIVATE
        android
        log)
