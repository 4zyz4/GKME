package com.zyz4.gkme.model

/** 一体十字键 / 自定义按键盘的中心形状。
 *  SQUARE = 方形中心（沿用 3×3 网格 / 楔形划分）；
 *  CIRCLE = 圆形中心，周围区域改为径向扇环（扇环的判定范围与绘制一致）。 */
enum class CenterShape {
    SQUARE,
    CIRCLE;
}
