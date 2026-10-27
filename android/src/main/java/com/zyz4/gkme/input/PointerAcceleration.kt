package com.zyz4.gkme.input

/**
 * 鼠标指针加速度：按**时间归一化的速度**放大位移。
 *
 * 旧实现用「本次位移」当作速度（`1 + accel × hypot(dx, dy)`），倍率会随
 * 事件批处理粒度变化：同一手势在 120Hz / 240Hz 触控下位移块大小不同，倍率
 * 也不同；WiFi/USB 又把鼠标位移合并进手柄发送帧，调低轮询率会让单个报文
 * 携带更大的位移。结果就是加速度手感被触控采样率与网络发送间隔绑架。
 *
 * 这里改为接收 `px/ms` 速度，并换算到固定 [REFERENCE_HZ] 下的“参考帧位移”：
 * - 同一物理速度永远得到同一倍率，与事件批处理、发送间隔、采样率均无关；
 * - 滑块手感以 120Hz 为基准标定，旧实现在 120Hz 触控下的行为保持不变。
 */
object PointerAcceleration {

    /** 参考采样率（Hz）：仅用于把速度换算成每帧位移，保持既有滑块手感。 */
    const val REFERENCE_HZ = 120f

    /**
     * 计算位移倍率。
     *
     * @param coefficient 布局中的指针加速度（0..0.5），0 表示关闭。
     * @param speedPxPerMs 指针速度（px/ms）；应来自时间维度的速度估计，
     *   而不是单个事件/单个网络报文的位移。
     * @return 位移倍率，恒 >= 1。
     */
    fun multiplier(coefficient: Float, speedPxPerMs: Float): Float {
        if (coefficient <= 0f || speedPxPerMs <= 0f) return 1f
        // 与采样率解耦：统一换算到参考采样率下的每帧位移再套用加速度。
        val pxPerReferenceFrame = speedPxPerMs * 1000f / REFERENCE_HZ
        return 1f + coefficient * pxPerReferenceFrame
    }
}
