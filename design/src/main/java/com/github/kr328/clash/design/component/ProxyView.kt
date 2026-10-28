package com.github.kr328.clash.design.component

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.LinearGradient
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import android.text.TextUtils
import android.text.TextPaint
import android.view.View
import androidx.core.graphics.drawable.DrawableCompat
import com.github.kr328.clash.common.compat.getDrawableCompat
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.store.UiStore
import kotlin.math.roundToInt

class ProxyView(
    context: Context,
    config: ProxyViewConfig,
) : View(context) {
    private val untestedDrawable =
        context.getDrawableCompat(R.drawable.ic_baseline_bolt)?.mutate()

    init {
        background = context.getDrawableCompat(config.clickableBackground)
    }

    var state: ProxyViewState? = null
    private var cardShader: Shader? = null
    private var shaderColor: Int? = null
    private var shaderWidth = 0
    private var shaderHeight = 0
    constructor(context: Context) : this(context, ProxyViewConfig(context, 2))

    private fun lineHeight(paint: TextPaint): Float {
        val metrics = paint.fontMetrics
        return metrics.descent - metrics.ascent
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val state = state ?: return super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED ->
                resources.displayMetrics.widthPixels
            MeasureSpec.AT_MOST, MeasureSpec.EXACTLY ->
                MeasureSpec.getSize(widthMeasureSpec)
            else ->
                throw IllegalArgumentException("invalid measure spec")
        }

        val titleHeight = state.paint.apply {
            reset()
            isAntiAlias = true
            textSize = state.config.textSize
            typeface = state.config.textTypeface
            textScaleX = state.config.textScaleX
        }.let(::lineHeight) * 2 + state.config.titleLineSpacing

        val secondaryHeight = state.paint.apply {
            typeface = state.config.secondaryTypeface
            textSize = state.config.secondaryTextSize
        }.let(::lineHeight)

        val delayHeight = state.paint.apply {
            typeface = state.config.textTypeface
            textSize = state.config.delayTextSize
        }.let(::lineHeight)

        val detailsHeight = if (state.config.showTechnicalDetails) {
            (if (state.proxy.server.isBlank()) 0f else secondaryHeight + 4.dp) +
                secondaryHeight + 8.dp + 6.dp
        } else {
            8.dp
        }

        val exceptHeight = (
            state.config.layoutPadding * 2 +
                state.config.contentPadding * 2 +
                titleHeight + detailsHeight + delayHeight
            ).toInt()

        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.UNSPECIFIED ->
                exceptHeight
            MeasureSpec.AT_MOST, MeasureSpec.EXACTLY ->
                exceptHeight.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
            else ->
                throw IllegalArgumentException("invalid measure spec")
        }

        setMeasuredDimension(width, height)
    }

    override fun draw(canvas: Canvas) {
        val state = state ?: return super.draw(canvas)

        if (state.update(false))
            postInvalidate()

        val width = width.toFloat()
        val height = height.toFloat()

        val paint = state.paint

        paint.reset()
        paint.isAntiAlias = true

        paint.color = state.background
        paint.style = Paint.Style.FILL
        if (state.config.sakura) {
            if (shaderColor != state.background || shaderWidth != this.width || shaderHeight != this.height) {
                shaderColor = state.background
                shaderWidth = this.width
                shaderHeight = this.height
                cardShader = LinearGradient(0f, 0f, width, height, state.background,
                    ColorUtils.blendARGB(state.background, state.config.cardTint, .04f), Shader.TileMode.CLAMP)
            }
            paint.shader = cardShader
        }

        // Keep the same soft filled-card treatment used by free_time_new.
        canvas.apply {
            val path = state.path

            path.reset()
            path.addRoundRect(
                state.config.layoutPadding,
                state.config.layoutPadding,
                width - state.config.layoutPadding,
                height - state.config.layoutPadding,
                state.config.cardRadius,
                state.config.cardRadius,
                Path.Direction.CW,
            )

            drawPath(path, paint)

            paint.shader = null
            paint.color = state.outline
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = resources.displayMetrics.density * .5f
            drawPath(path, paint)

            clipPath(path)
        }

        super.draw(canvas)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val state = state ?: return

        val paint = state.paint
        val contentLeft = state.config.layoutPadding + state.config.contentPadding
        val contentRight = width - state.config.layoutPadding - state.config.contentPadding
        val contentWidth = (contentRight - contentLeft).toInt().coerceAtLeast(1)
        var y = state.config.layoutPadding + state.config.contentPadding

        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = state.config.textTypeface
        paint.textScaleX = state.config.textScaleX
        paint.textSize = state.config.textSize
        paint.color = state.controls

        drawTwoLines(canvas, state.title, contentLeft, y, contentWidth, paint)
        y += lineHeight(paint) * 2 + state.config.titleLineSpacing

        paint.typeface = state.config.secondaryTypeface
        paint.textSize = state.config.secondaryTextSize
        paint.color = state.config.secondaryControl

        if (state.config.showTechnicalDetails) {
            if (state.server.isNotBlank()) {
                y += 4.dp
                drawSingleLine(canvas, state.server, contentLeft, y, contentWidth, paint)
                y += lineHeight(paint)
            }

            y += 8.dp
            drawSingleLine(canvas, state.subtitle, contentLeft, y, contentWidth, paint)
            y += lineHeight(paint) + 6.dp
        } else {
            y += 8.dp
        }

        paint.typeface = state.config.textTypeface
        paint.textSize = state.config.delayTextSize
        paint.fontFeatureSettings = "tnum"
        paint.color = state.delayColor
        if (state.testing) {
            val size = lineHeight(paint)
            paint.color = state.config.delayUntested
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.dp
            paint.strokeCap = Paint.Cap.ROUND
            val angle = (android.os.SystemClock.uptimeMillis() % 900) * 360f / 900
            canvas.drawArc(contentLeft + 2.dp, y + 2.dp, contentLeft + size - 2.dp,
                y + size - 2.dp, angle, 270f, false, paint)
            postInvalidateOnAnimation()
        } else if (state.delayUntested) {
            val iconSize = lineHeight(paint).roundToInt()
            val left = contentLeft.roundToInt()
            val top = y.roundToInt()

            untestedDrawable?.let {
                DrawableCompat.setTint(it, state.config.delayUntested)
                it.setBounds(left, top, left + iconSize, top + iconSize)
                it.draw(canvas)
            }
        } else {
            drawSingleLine(canvas, state.delayText, contentLeft, y, contentWidth, paint)
        }
    }

    private fun drawSingleLine(
        canvas: Canvas,
        text: String,
        x: Float,
        top: Float,
        width: Int,
        paint: TextPaint,
    ) {
        val display = TextUtils.ellipsize(text, paint, width.toFloat(), TextUtils.TruncateAt.END)
        canvas.drawText(display.toString(), x, top - paint.fontMetrics.ascent, paint)
    }

    private fun drawTwoLines(
        canvas: Canvas,
        text: String,
        x: Float,
        top: Float,
        width: Int,
        paint: TextPaint,
    ) {
        val firstCount = paint.breakText(text, true, width.toFloat(), null)
        if (firstCount >= text.length) {
            canvas.drawText(text, x, top - paint.fontMetrics.ascent, paint)
            return
        }

        val rawBreak = text.lastIndexOf(' ', firstCount - 1)
        val breakAt = if (rawBreak >= firstCount / 2) rawBreak else firstCount
        val firstLine = text.substring(0, breakAt).trimEnd()
        val secondLine = text.substring(breakAt).trimStart()
        val displaySecond = TextUtils.ellipsize(
            secondLine,
            paint,
            width.toFloat(),
            TextUtils.TruncateAt.END,
        )
        val firstBaseline = top - paint.fontMetrics.ascent

        canvas.drawText(firstLine, x, firstBaseline, paint)
        canvas.drawText(displaySecond.toString(), x,
            firstBaseline + lineHeight(paint) + (state?.config?.titleLineSpacing ?: 0f), paint)
    }

    private val Int.dp: Float
        get() = this * resources.displayMetrics.density
}
