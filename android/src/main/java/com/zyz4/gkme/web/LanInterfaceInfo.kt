package com.zyz4.gkme.web

/** 一个可选的局域网网卡（名称 + IPv4 地址），用于构造展示给用户的网页手柄地址。 */
data class LanInterfaceInfo(
    val name: String,
    val address: String,
) {
    val display: String get() = if (name.isEmpty()) address else "$name ($address)"

    override fun toString(): String = display
}
