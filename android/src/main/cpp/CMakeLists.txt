cmake_minimum_required(VERSION 3.22.1)

project(gkme_uinput C)

# Native virtual gamepad implementation: runs in Shizuku UserService process,
# operating directly on Linux /dev/uinput and /dev/uhid.
add_library(gkme_uinput SHARED
        uinput_gamepad.c
        uhid_input.c)

target_link_libraries(gkme_uinput PRIVATE
        android
        log)
