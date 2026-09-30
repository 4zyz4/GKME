package com.zyz4.gkme.input

import android.view.InputDevice

/**
 * 识别 GKME 自己通过 uinput 创建的虚拟手柄（见 controlled/RemoteGamepadService 与
 * cpp/uinput_gamepad.c）。
 *
 * 该虚拟手柄会作为普通 Android 手柄/摇杆设备出现在系统里，因此 SDL 与实体手柄检测
 * 会把它当成真实手柄，形成“本机输入 → 虚拟手柄 → 又被当成实体手柄读回”的回环。
 * 这里集中给出匹配依据，供各检测点排除自己创建的设备。
 *
 * 名称与 product 需与 cpp/uinput_gamepad.c 保持一致。
 */
object VirtualGamepad {

    const val NAME = "Xbox One S Controller"
    const val VENDOR_ID = 0x045E
    const val PRODUCT_ID = 0x02FD

    /** True when [device] is the virtual gamepad GKME created via uinput. */
    fun matches(device: InputDevice): Boolean =
        device.vendorId == VENDOR_ID &&
            device.productId == PRODUCT_ID &&
            device.name == NAME
}
