// 被控端虚拟手柄的用户服务接口。
// 该接口的 Stub 由 Shizuku 在 shell/root 进程中实例化，App 进程通过 Binder 调用。
package com.zyz4.gkme.controlled;

interface IGamepadService {
    /**
     * 创建虚拟手柄，返回 0 表示成功，负数表示 -errno。
     * backend：0 = uinput（伪装 Xbox One S），1 = uhid（真实 HID 身份）。
     * profile：backend=uhid 时选择身份，1 = DualShock 4，2 = DualSense，3 = Switch Pro。
     * rumbleEnabled：是否把虚拟手柄的震动数据转发回控制端/手机。FF 能力始终暴露，
     *   本机模式传 false 以忽略震动数据，避免“手机震动 ↔ 虚拟手柄”回环。
     */
    int create(int backend, int profile, boolean rumbleEnabled) = 1;

    /**
     * 写入一次手柄状态；buttons 使用 XInput wButtons 掩码。
     * gyro/accel 为运动传感器数据（rad/s、m/s²），仅 uhid 手柄使用；uinput 忽略。
     */
    void update(int buttons, int leftTrigger, int rightTrigger,
                int leftX, int leftY, int rightX, int rightY,
                float gyroX, float gyroY, float gyroZ,
                float accelX, float accelY, float accelZ) = 2;

    /** 读取待回传的震动，高 16 位为左马达、低 16 位为右马达（0..32767）。无震动返回 0。 */
    long pumpRumble() = 3;

    /** 销毁虚拟手柄。 */
    void release() = 4;

    /** 创建虚拟键盘，返回 0 表示成功，负数表示 -errno。 */
    int createKeyboard() = 5;

    /**
     * 写入一次全量键盘状态。
     * modifiers 为 HID 修饰键位掩码（bit0=LCtrl … bit7=RGui）；
     * usages 为 HID Keyboard/Keypad 键位用法（0x04..0xFF，修饰键 0xE0..0xE7 除外）。
     */
    void updateKeyboard(int modifiers, in int[] usages) = 6;

    /** 创建虚拟鼠标，返回 0 表示成功，负数表示 -errno。 */
    int createMouse() = 7;

    /**
     * 写入一次鼠标帧。buttons 为按键掩码（bit0 左键、bit1 右键、bit2 中键、
     * bit3 后退、bit4 前进）；dx/dy 为相对位移；wheel/pan 为垂直/横向滚轮。
     */
    void updateMouse(int dx, int dy, int wheel, int pan, int buttons) = 8;

    /** 销毁虚拟键盘与鼠标。 */
    void releaseKeyboardMouse() = 9;

    /**
     * 用户服务退出（Shizuku 约定的事务号，见 Shizuku-API 文档：
     * "The transaction code for that method is 16777115 (use 16777114 in aidl)"）。
     */
    void exitService() = 16777114;
}
