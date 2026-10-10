package com.zyz4.gkme.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable

/**
 * 给手柄图标着色，同时保留图标自身的明暗与透明度。
 *
 * 图标在游戏内一律被视为绘制在黑色背景之上：
 * - 单色图标（形状本身只有一种亮度）用 [mono]，直接用所选颜色覆盖，源像素的透明度保持不变。
 * - 多明暗图标（如一体十字键、分享/触摸板网格图标的深色填充 + 灰色描边 + 半透明圆点）用
 *   [tint] 的 shaded 分支：先把图标合成到黑底上，再取合成后每个像素的亮度作为所选颜色的
 *   透明度。这样深色填充更透明、浅色描边更不透明，且原本半透明的部分也会按比例混合透明度。
 */
object IconTint {

    /** 单色图标：输出所选颜色，源像素的透明度（抗锯齿 + 半透明）保持不变。 */
    fun mono(color: Int): ColorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)

    /**
     * 按图标类型着色。[shaded] 为 true 时用「黑底 + 亮度作透明度」保留多明暗层次，
     * 否则用单色覆盖。
     */
    fun tint(icon: Drawable, color: Int, shaded: Boolean): Drawable =
        if (shaded) ShadedIconDrawable(icon, color) else icon.apply { colorFilter = mono(color) }
}

/**
 * 把 [inner] 先绘制到黑色背景上，再按每个像素的亮度给 [color] 赋透明度并缓存为位图。
 * 由于半透明部分在与黑底合成时已按比例变暗，其透明度也会被正确保留。
 */
private class ShadedIconDrawable(
    private val inner: Drawable,
    private val color: Int,
) : Drawable() {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var bitmap: Bitmap? = null
    private var bitmapW = 0
    private var bitmapH = 0

    override fun getIntrinsicWidth(): Int = inner.intrinsicWidth
    override fun getIntrinsicHeight(): Int = inner.intrinsicHeight
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun draw(canvas: Canvas) {
        val w = bounds.width()
        val h = bounds.height()
        if (w <= 0 || h <= 0) return
        val bmp = ensureBitmap(w, h) ?: return
        canvas.drawBitmap(bmp, null, bounds, paint)
    }

    private fun ensureBitmap(w: Int, h: Int): Bitmap? {
        bitmap?.let { if (bitmapW == w && bitmapH == h) return it }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        inner.setBounds(0, 0, w, h)
        inner.draw(c)

        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val cr = Color.red(color)
        val cg = Color.green(color)
        val cb = Color.blue(color)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            // 与黑底合成后的感知亮度即所选颜色应使用的透明度。
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            pixels[i] = (lum shl 24) or (cr shl 16) or (cg shl 8) or cb
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        bitmap?.recycle()
        bitmap = bmp
        bitmapW = w
        bitmapH = h
        return bmp
    }
}
