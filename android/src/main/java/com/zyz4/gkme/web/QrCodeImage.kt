package com.zyz4.gkme.web

import android.graphics.Bitmap

/** 把 [QrCode] 模块矩阵渲染为 ARGB_8888 位图（白底黑块）。 */
object QrCodeImage {

    fun render(text: String, ecc: QrEcc = QrEcc.MEDIUM, scale: Int = 4, border: Int = 4): Bitmap {
        val qr = QrCode.encodeText(text, ecc)
        val size = qr.size
        val dim = (size + border * 2) * scale
        val pixels = IntArray(dim * dim)

        val darkColor = 0xFF000000.toInt()
        val lightColor = 0xFFFFFFFF.toInt()

        for (py in 0 until dim) {
            val my = py / scale - border
            val rowBase = py * dim
            for (px in 0 until dim) {
                val mx = px / scale - border
                val dark = mx in 0 until size && my in 0 until size && qr.getModule(mx, my)
                pixels[rowBase + px] = if (dark) darkColor else lightColor
            }
        }

        return Bitmap.createBitmap(pixels, dim, dim, Bitmap.Config.ARGB_8888)
    }
}
