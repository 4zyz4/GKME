package com.zyz4.gkme.controlled

/** 被控端发现的一个控制端设备（单个 IP 端点）。 */
data class ControlledDevice(
    val ip: String,
    val name: String = "",
    val mac: String = "",
    val lastSeen: Long = System.currentTimeMillis(),
) {
    val displayName: String get() = name.ifBlank { ip }
    val subtitle: String get() = if (mac.isNotBlank()) "$ip  ·  $mac" else ip

    /** 同一物理设备的稳定标识：优先 MAC（跨 IP 稳定），否则退回 IP。
     *  用于把多张 IP 卡片合并成一张，行为对齐电脑端 DiscoveredDevice.GroupKey。 */
    val groupKey: String get() = mac.ifBlank { ip }
}
