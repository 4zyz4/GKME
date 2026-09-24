package com.zyz4.gkme.view

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader

object ShapeImageUtil {
    fun applyCenterCrop(paint: Paint, bitmap: Bitmap, targetW: Float, targetH: Float) {
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val sx = targetW / bitmap.width
        val sy = targetH / bitmap.height
        val scale = maxOf(sx, sy)
        val dx = (targetW - bitmap.width * scale) / 2f
        val dy = (targetH - bitmap.height * scale) / 2f
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        shader.setLocalMatrix(matrix)
        paint.shader = shader
    }

    fun applyFitCenter(paint: Paint, bitmap: Bitmap, targetW: Float, targetH: Float) {
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val sx = targetW / bitmap.width
        val sy = targetH / bitmap.height
        val scale = minOf(sx, sy)
        val dx = (targetW - bitmap.width * scale) / 2f
        val dy = (targetH - bitmap.height * scale) / 2f
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        shader.setLocalMatrix(matrix)
        paint.shader = shader
    }
}
