package com.zyz4.gkme.input

import android.view.InputDevice

/**
 * 识别 GKME 自己创建的虚拟手柄（uinput 与 uhid 后端，见 controlled/RemoteGamepadService、
 * cpp/uinput_gamepad.c 与 cpp/uhid_input.c）。
 *
 * 这些虚拟手柄会作为普通 Android 手柄/摇杆设备出现在系统里，因此 SDL 与实体手柄检测
 * 会把它当成真实手柄，形成“本机输入 → 虚拟手柄 → 又被当成实体手柄读回”的回环。
 * 这里集中给出匹配依据，供各检测点排除自己创建的设备。
 *
 * 名称与 vendor/product 需与 cpp/uinput_gamepad.c、cpp/uhid_input.c 保持一致。
 * 注意：Switch Pro 经内核 hid-nintendo 驱动后 input 名称会被改写为
 * "Nintendo Switch Pro Controller"（不是 uhid create2 传入的 "Pro Controller"）。
 */
object VirtualGamepad {

    // uinput 后端伪装 Xbox One S；uhid 后端伪装真实的 DualShock 4 / DualSense /
    // Switch Pro，因此会复用这些真实手柄的 vendor/product。
    private val VIRTUAL_VENDOR_PRODUCTS = setOf(
        0x045E to 0x02FD, // uinput: Xbox One S
        0x054C to 0x09CC, // uhid: DualShock 4
        0x054C to 0x0CE6, // uhid: DualSense
        0x057E to 0x2009, // uhid: Switch Pro
    )

    /**
     * 仅在 GKME 确实创建了虚拟手柄时为 true。
     *
     * 由于虚拟手柄复用真实手柄的 vendor/product，若常态排除会连带隐藏同型号的真实
     * 手柄，因此把排除限定在虚拟手柄运行期间（由 GamepadInjector 维护）。
     */
    @Volatile
    var virtualGamepadActive: Boolean = false

    /**
     * True when [device] is a virtual gamepad GKME created via uinput or uhid.
     *
     * 只依据 vendor/product 判断：SDL 会改写设备名，名称不可靠。
     */
    fun matches(device: InputDevice): Boolean =
        virtualGamepadActive &&
            (device.vendorId to device.productId) in VIRTUAL_VENDOR_PRODUCTS
}
