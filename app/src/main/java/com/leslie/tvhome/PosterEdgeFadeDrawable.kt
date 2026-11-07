package com.leslie.tvhome

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** Wide edge fades that blend a shrunk match poster into its duplicated backdrop. */
class PosterEdgeFadeDrawable : Drawable() {
    private val vignette = Paint(Paint.ANTI_ALIAS_FLAG)
    private val horizontal = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vertical = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return

        vignette.shader = RadialGradient(
            b.exactCenterX(),
            b.top + b.height() * 0.38f,
            maxOf(b.width(), b.height()) * 0.68f,
            intArrayOf(0x00000000, 0x00000000, 0x58000000, 0xE0000000.toInt()),
            floatArrayOf(0f, 0.42f, 0.72f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(b, vignette)

        horizontal.shader = LinearGradient(
            b.left.toFloat(), 0f, b.right.toFloat(), 0f,
            intArrayOf(
                0xF5000000.toInt(), 0xB0000000.toInt(), 0x00000000,
                0x00000000, 0x00000000, 0x4A000000, 0xA8000000.toInt(), 0xE8000000.toInt(), 0xF8000000.toInt(),
            ),
            floatArrayOf(0f, 0.04f, 0.12f, 0.58f, 0.66f, 0.78f, 0.87f, 0.94f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(b, horizontal)

        vertical.shader = LinearGradient(
            0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            intArrayOf(0xB8000000.toInt(), 0x30000000, 0x00000000, 0x00000000, 0x4A000000, 0xC0000000.toInt()),
            floatArrayOf(0f, 0.07f, 0.18f, 0.62f, 0.82f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(b, vertical)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
