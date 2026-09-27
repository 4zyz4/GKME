// 被控端虚拟手柄的用户服务接口。
// 该接口的 Stub 由 Shizuku 在 shell/root 进程中实例化，App 进程通过 Binder 调用。
package com.zyz4.gkme.controlled;

interface IGamepadService {
    /** 创建虚拟手柄，返回 0 表示成功，负数表示 -errno。 */
    int create(boolean rumbleEnabled) = 1;

    /** 写入一次手柄状态；buttons 使用 XInput wButtons 掩码。 */
    void update(int buttons, int leftTrigger, int rightTrigger,
                int leftX, int leftY, int rightX, int rightY) = 2;

    /** 读取待回传的震动，高 16 位为左马达、低 16 位为右马达（0..32767）。无震动返回 0。 */
    long pumpRumble() = 3;

    /** 销毁虚拟手柄。 */
    void release() = 4;

    /**
     * 用户服务退出（Shizuku 约定的事务号，见 Shizuku-API 文档：
     * "The transaction code for that method is 16777115 (use 16777114 in aidl)"）。
     */
    void exitService() = 16777114;
}
