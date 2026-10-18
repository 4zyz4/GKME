package com.zyz4.gkme.model

import androidx.annotation.StringRes
import com.zyz4.gkme.R

/**
 * 鼠标触控板手势动作。
 *
 * 点击类手势（单击 / 双指点击 / 三指点击）只使用 [NONE] + 三种鼠标点击；
 * 滑动类手势（双击后滑动 / 单指 / 双指 / 三指滑动）额外支持移动光标、拖拽与滚动。
 */
enum class MouseGestureAction(@StringRes val displayNameRes: Int) {
    NONE(R.string.i18n_none),
    MOVE_CURSOR(R.string.i18n_move_cursor),
    LEFT_CLICK(R.string.i18n_left_click),
    RIGHT_CLICK(R.string.i18n_right_click),
    MIDDLE_CLICK(R.string.i18n_middle_click),
    LEFT_DRAG(R.string.i18n_left_button_drag),
    RIGHT_DRAG(R.string.i18n_right_button_drag),
    MIDDLE_DRAG(R.string.i18n_middle_button_drag),
    SCROLL(R.string.i18n_scroll);

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
