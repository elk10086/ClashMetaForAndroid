package com.github.kr328.clash.design.view

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.DecelerateInterpolator
import com.github.kr328.clash.design.util.resolveThemedColor
import kotlin.math.cos
import kotlin.math.sin

/** Decorative transition, not a percentage of VPN startup work. */
class PowerChargeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tint = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
    private var animator: ValueAnimator? = null
    private var phase = 0f
    private var charge = 0f
    private var starting = true
    private val dp = resources.displayMetrics.density

    fun begin(starting: Boolean) {
        stop()
        this.starting = starting
        visibility = VISIBLE
        alpha = 0f
        animate()
            .alpha(1f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
        val began = android.os.SystemClock.uptimeMillis()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            // Keep the charging motion calm; the ring completes one orbit in 2s.
            duration = 2_000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                val elapsed = (android.os.SystemClock.uptimeMillis() - began) / 1000f
                val progress = DecelerateInterpolator(1.4f)
                    .getInterpolation((elapsed / 1.5f).coerceIn(0f, 1f))
                charge = if (starting) progress * .86f else 1f - progress * .86f
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
        visibility = GONE
        alpha = 1f
        scaleX = 1f
        scaleY = 1f
    }

    fun finish() {
        animator?.cancel()
        val initial = charge
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                val fraction = it.animatedValue as Float
                charge = initial + ((if (starting) 1f else 0f) - initial) * fraction
                alpha = 1f - fraction * .85f
                scaleX = 1f + (if (starting) .04f else -.08f) * fraction
                scaleY = scaleX
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = GONE
                }
            })
            start()
        }
    }

    /** Smoothly dismiss an interrupted transition instead of hiding the ring abruptly. */
    fun dismiss() {
        animator?.cancel()
        animator = null
        animate().cancel()
        animate()
            .alpha(0f)
            .scaleX(.96f)
            .scaleY(.96f)
            .setDuration(280)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .withEndAction {
                visibility = GONE
                alpha = 1f
                scaleX = 1f
                scaleY = 1f
                charge = 0f
            }
            .start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) - 5 * dp
        paint.color = tint
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 2 * dp
        paint.alpha = 35
        canvas.drawCircle(cx, cy, radius, paint)
        paint.strokeWidth = 4 * dp
        paint.alpha = 220
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
            -90f, charge * 360f, false, paint)
        paint.strokeWidth = 1.5f * dp
        paint.alpha = 110
        val orbit = radius - 7 * dp
        val angle = phase * 360f * if (starting) 1 else -1
        canvas.drawArc(cx - orbit, cy - orbit, cx + orbit, cy + orbit,
            angle, 65f, false, paint)
        paint.style = Paint.Style.FILL
        repeat(3) { index ->
            val radians = Math.toRadians((angle + index * 120).toDouble())
            paint.alpha = 220 - index * 55
            canvas.drawCircle(cx + cos(radians).toFloat() * radius,
                cy + sin(radians).toFloat() * radius, (2.5f - index * .4f) * dp, paint)
        }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
