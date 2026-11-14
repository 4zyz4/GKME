package com.zyz4.gkme.input

import com.zyz4.gkme.model.PhysicalStickDirection
import com.zyz4.gkme.model.PhysicalStickMode
import kotlin.math.abs

/**
 * 实体摇杆的纯路由逻辑：把「塑形之后」的摇杆向量按每根摇杆的 [PhysicalStickMode]
 * 分配到输出轴、鼠标位移或 4/8 向方向。
 *
 * 这里不依赖 Android，方便 JVM 单元测试。
 */
object PhysicalStickRouting {

    /** 摇杆满量程（`Short.MAX_VALUE`）。 */
    const val FULL_SCALE = 32767f

    /** 4/8 向按钮的方向判定阈值（归一化后的轴向绝对值）。 */
    const val DIRECTION_THRESHOLD = 0.5f

    /**
     * 两根实体摇杆的路由结果。
     *
     * [leftX]/[leftY]/[rightX]/[rightY] 是要叠加到对应输出轴上的贡献值（十字交叉映射
     * 时可能来自另一根摇杆）；[mouseX]/[mouseY] 是归一的鼠标速度分量（含
     * 各自的鼠标灵敏度），Y 轴与摇杆一致：向上为负（HID 鼠标向上也是负）。
     */
    data class Routed(
        val leftX: Int = 0,
        val leftY: Int = 0,
        val rightX: Int = 0,
        val rightY: Int = 0,
        val mouseX: Float = 0f,
        val mouseY: Float = 0f,
    )

    /** 归一化鼠标速度的限幅，避免灵敏度叠加后产生异常大的单帧位移。 */
    private const val MOUSE_VELOCITY_LIMIT = 10f

    private enum class Target { LEFT, RIGHT, MOUSE, NONE }

    /** 该摇杆的输出目标：[isLeftStick] 只影响原生模式下默认写入的轴。 */
    private fun targetOf(mode: PhysicalStickMode?, isLeftStick: Boolean): Target = when (mode) {
        PhysicalStickMode.LEFT_STICK -> Target.LEFT
        PhysicalStickMode.RIGHT_STICK -> Target.RIGHT
        PhysicalStickMode.MOUSE -> Target.MOUSE
        PhysicalStickMode.FOUR_WAY, PhysicalStickMode.EIGHT_WAY -> Target.NONE
        null -> if (isLeftStick) Target.LEFT else Target.RIGHT
    }

    /**
     * 按模式把两根摇杆的塑形值分配到输出轴 / 鼠标。
     *
     * 两根摇杆可以映射到同一根轴（例如一根原生左摇杆 + 一根映射到左摇杆的右摇杆），
     * 贡献值相加后按 `Short` 范围限幅。鼠标模式下 [leftMouseSensitivity] /
     * [rightMouseSensitivity] 是每根摇杆各自的倍率（1f = 100%）。
     */
    fun route(
        leftMode: PhysicalStickMode?, leftX: Int, leftY: Int,
        rightMode: PhysicalStickMode?, rightX: Int, rightY: Int,
        leftMouseSensitivity: Float = 1f,
        rightMouseSensitivity: Float = 1f,
    ): Routed {
        var lx = 0
        var ly = 0
        var rx = 0
        var ry = 0
        var mouseX = 0f
        var mouseY = 0f

        fun add(target: Target, x: Int, y: Int, mouseSensitivity: Float) {
            when (target) {
                Target.LEFT -> { lx += x; ly += y }
                Target.RIGHT -> { rx += x; ry += y }
                Target.MOUSE -> {
                    mouseX += x / FULL_SCALE * mouseSensitivity
                    mouseY += y / FULL_SCALE * mouseSensitivity
                }
                Target.NONE -> Unit
            }
        }

        add(targetOf(leftMode, isLeftStick = true), leftX, leftY, leftMouseSensitivity)
        add(targetOf(rightMode, isLeftStick = false), rightX, rightY, rightMouseSensitivity)

        return Routed(
            leftX = lx.coerceIn(-32768, 32767),
            leftY = ly.coerceIn(-32768, 32767),
            rightX = rx.coerceIn(-32768, 32767),
            rightY = ry.coerceIn(-32768, 32767),
            mouseX = mouseX.coerceIn(-MOUSE_VELOCITY_LIMIT, MOUSE_VELOCITY_LIMIT),
            mouseY = mouseY.coerceIn(-MOUSE_VELOCITY_LIMIT, MOUSE_VELOCITY_LIMIT),
        )
    }

    /**
     * 4 向判定：取绝对值更大的轴作为主导轴，超过 [DIRECTION_THRESHOLD] 才算按下。
     * 返回 null 表示在中心区域。
     */
    fun fourWayDirection(x: Int, y: Int): PhysicalStickDirection? {
        val ax = abs(x / FULL_SCALE)
        val ay = abs(y / FULL_SCALE)
        return if (ax >= ay) {
            if (ax < DIRECTION_THRESHOLD) null
            else if (x < 0) PhysicalStickDirection.LEFT else PhysicalStickDirection.RIGHT
        } else {
            if (ay < DIRECTION_THRESHOLD) null
            else if (y < 0) PhysicalStickDirection.UP else PhysicalStickDirection.DOWN
        }
    }

    /**
     * 8 向判定：两根轴各自过 [DIRECTION_THRESHOLD]；同时过阈值即为斜向。
     * 返回 null 表示在中心区域。
     */
    fun eightWayDirection(x: Int, y: Int): PhysicalStickDirection? {
        val ax = abs(x / FULL_SCALE)
        val ay = abs(y / FULL_SCALE)
        val horizontal = if (ax >= DIRECTION_THRESHOLD) (if (x < 0) -1 else 1) else 0
        val vertical = if (ay >= DIRECTION_THRESHOLD) (if (y < 0) -1 else 1) else 0
        return when {
            vertical < 0 && horizontal < 0 -> PhysicalStickDirection.UP_LEFT
            vertical < 0 && horizontal > 0 -> PhysicalStickDirection.UP_RIGHT
            vertical > 0 && horizontal < 0 -> PhysicalStickDirection.DOWN_LEFT
            vertical > 0 && horizontal > 0 -> PhysicalStickDirection.DOWN_RIGHT
            vertical < 0 -> PhysicalStickDirection.UP
            vertical > 0 -> PhysicalStickDirection.DOWN
            horizontal < 0 -> PhysicalStickDirection.LEFT
            horizontal > 0 -> PhysicalStickDirection.RIGHT
            else -> null
        }
    }

    /** 按模式判定当前方向；非 4/8 向或处于中心区域时返回 null。 */
    fun directionOf(mode: PhysicalStickMode?, x: Int, y: Int): PhysicalStickDirection? =
        when (mode) {
            PhysicalStickMode.FOUR_WAY -> fourWayDirection(x, y)
            PhysicalStickMode.EIGHT_WAY -> eightWayDirection(x, y)
            else -> null
        }
}
