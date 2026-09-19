package com.zyz4.gkme.model

/**
 * 鼠标触控板手势动作。
 *
 * 点击类手势（单击 / 双指点击 / 三指点击）只使用 [NONE] + 三种鼠标点击；
 * 滑动类手势（双击后滑动 / 单指 / 双指 / 三指滑动）额外支持移动光标、拖拽与滚动。
 */
enum class MouseGestureAction(val displayName: String) {
    NONE("无"),
    MOVE_CURSOR("移动光标"),
    LEFT_CLICK("左键点击"),
    RIGHT_CLICK("右键点击"),
    MIDDLE_CLICK("中键点击"),
    LEFT_DRAG("左键拖拽"),
    RIGHT_DRAG("右键拖拽"),
    MIDDLE_DRAG("中键拖拽"),
    SCROLL("滚动");

    companion object {
        /** 点击手势可选动作（4 项）。 */
        val TAP_ACTIONS: List<MouseGestureAction> = listOf(
            NONE, LEFT_CLICK, RIGHT_CLICK, MIDDLE_CLICK,
        )

        /** 滑动手势可选动作（6 项）。 */
        val SWIPE_ACTIONS: List<MouseGestureAction> = listOf(
            NONE, MOVE_CURSOR, LEFT_DRAG, RIGHT_DRAG, MIDDLE_DRAG, SCROLL,
        )

        /** 解析持久化的动作名，未知值回退到 [NONE]。 */
        fun fromName(name: String?): MouseGestureAction =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NONE
    }
}
