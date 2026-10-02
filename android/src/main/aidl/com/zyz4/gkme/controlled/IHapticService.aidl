// 高清震动（RichTap 动态效果）用户服务接口。
// Stub 由 Shizuku 在 shell/root 进程中实例化；该进程不受 hidden API 限制，
// 因此可以反射调用 android.os.HapticPlayer / android.os.DynamicEffect。
package com.zyz4.gkme.controlled;

interface IHapticService {
    /** 本机是否支持 RichTap 隐藏 API（android.os.HapticPlayer.isAvailable()）。 */
    boolean isAvailable() = 1;

    /** RichTap core 版本号（android.os.HapticPlayer.getVersion()），不支持时为空串。 */
    String getVersion() = 2;

    /**
     * 播放一段 HE 1.0 动态效果。
     * @param heJson     HE 1.0 JSON（顶层 Pattern，曲线裁成 4 点，单事件时长 ≤ 5000ms）。
     * @param loop       -1 表示无限循环；其他值只播放一遍。
     * @param interval   循环间隔（ms）。
     * @param amplitude  全局振幅 0-255。
     * @param freq       全局频率 0-100。
     * @return 是否成功提交。
     */
    boolean startPattern(String heJson, int loop, int interval, int amplitude, int freq) = 3;

    /** 停止当前动态效果。 */
    void stop() = 4;

    /**
     * 播放一段 HE 1.0 效果，使用**效果自身的参数**（相当于无参 `HapticPlayer.start()`，
     * 不传全局 amplitude/freq）。用于多事件分块效果：`Pattern` 里每个事件自带
     * `Parameters.Frequency` 与 4 点 `Curve`，不应被全局参数覆盖。
     * @return 是否成功提交。
     */
    boolean startEffect(String heJson) = 5;

    /**
     * 当前选用的 RichTap backend 类型（对齐 SDK `PlayerType`）：
     * 0 = GooglePerformer（普通 VibrationEffect）、1 = TencentPerformer（DynamicEffect/HapticPlayer）、
     * 2 = RichTapPerformer（RichTapVibrationEffect/PhonyVibrationEffect）。
     */
    int getPlayerType() = 6;

    /** 是否支持 type 2 的实时调参（core 主版本 ≥ 32）。 */
    boolean supportsRealtimeAdjustment() = 7;

    /**
     * 实时调整当前效果的全局强度/频率（type 2 专用，`createHapticParameter`）。
     * @param intensity 强度 0-100；@param frequency 频率 0-100。
     * @return 是否成功提交。
     */
    boolean updateParameter(int intensity, int frequency) = 8;

    /** 用户服务退出（Shizuku 约定事务号）。 */
    void exitService() = 16777114;
}
