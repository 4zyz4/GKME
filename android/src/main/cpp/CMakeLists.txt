cmake_minimum_required(VERSION 3.22.1)

project(gkme_sdl CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# SDL3 is provided by the SDL3-3.4.16.aar prefab package.
find_package(SDL3 REQUIRED CONFIG)

add_library(gkme_sdl SHARED
        sdl_bridge.cpp)

target_include_directories(gkme_sdl PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/sdl/include)

target_link_libraries(gkme_sdl PRIVATE
        SDL3::SDL3
        android
        log)
