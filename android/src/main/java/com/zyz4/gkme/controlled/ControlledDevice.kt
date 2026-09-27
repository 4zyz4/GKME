package com.zyz4.gkme.controlled

/** 被控端发现的一个控制端设备。 */
data class ControlledDevice(
    val ip: String,
    val name: String = "",
    val mac: String = "",
    val lastSeen: Long = System.currentTimeMillis(),
) {
    val displayName: String get() = name.ifBlank { ip }
    val subtitle: String get() = if (mac.isNotBlank()) "$ip  ·  $mac" else ip
}
