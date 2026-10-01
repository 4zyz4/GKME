package com.zyz4.gkme.haptic

/**
 * HE 来源仲裁器（纯 JVM，可单测）。
 *
 * 维护“当前驱动手机马达的来源”。规则：
 *  - 空闲时任意来源可获取；
 *  - 高优先级可抢占低优先级；
 *  - 低优先级在更高来源持有期间不能获取（调用方据此静默，而非回退系统震动）；
 *  - 同级可重复获取（同一来源刷新自身）。
 */
class HapticArbiter {

    private val lock = Any()
    private var active: HapticSource? = null

    /** 当前来源；空闲为 null。 */
    fun current(): HapticSource? = synchronized(lock) { active }

    /** 当前是否允许 [source] 驱动：空闲，或优先级不低于当前来源。 */
    fun canPlay(source: HapticSource): Boolean = synchronized(lock) {
        val current = active
        current == null || source.priority >= current.priority
    }

    /** 获取/抢占：空闲或优先级不低于当前时成功并成为当前来源。 */
    fun acquire(source: HapticSource): Boolean = synchronized(lock) {
        val current = active
        if (current != null && source.priority < current.priority) return@synchronized false
        active = source
        true
    }

    /** 仅当 [source] 为当前来源时清除（不停止正在播放的效果）。 */
    fun release(source: HapticSource) {
        synchronized(lock) { if (active === source) active = null }
    }

    /** 无条件清除当前来源。 */
    fun clear() {
        synchronized(lock) { active = null }
    }
}
