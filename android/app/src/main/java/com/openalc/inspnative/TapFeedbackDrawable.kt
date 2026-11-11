package com.openalc.inspnative

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.hypot
import kotlin.math.max

class TapFeedbackDrawable(private val tint: Int, private val corner: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
    private var originX = 0f
    private var originY = 0f
    private var progress = 1f
    private var animation: ValueAnimator? = null

    fun pulse(x: Float, y: Float) {
        originX = x
        originY = y
        animation?.cancel()
        animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 440
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidateSelf()
            }
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        if (progress >= 1f) return
        val rect = RectF(bounds)
        val clip = Path().apply { addRoundRect(rect, corner, corner, Path.Direction.CW) }
        val radius = max(max(hypot(originX, originY), hypot(rect.right - originX, originY)),
            max(hypot(originX, rect.bottom - originY), hypot(rect.right - originX, rect.bottom - originY)))
        paint.alpha = (ColorAlpha * if (progress < .72f) 1f else (1f - progress) / .28f).toInt().coerceIn(0, 255)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawCircle(originX, originY, radius * progress, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object { const val ColorAlpha = 76f }
}
