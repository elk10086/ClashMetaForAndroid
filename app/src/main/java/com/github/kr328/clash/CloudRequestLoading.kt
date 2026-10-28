package com.github.kr328.clash

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import com.github.kr328.clash.design.util.ThemeTypography
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.github.kr328.clash.design.util.resolveThemedColor
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.github.kr328.clash.design.R as DesignR

/** A compact, theme-aware loading surface shared by foreground cloud requests. */
internal class CloudRequestLoading(
    private val activity: Activity,
    message: CharSequence = "正在处理请求…",
) {
    private val density = activity.resources.displayMetrics.density
    private val surface = activity.resolveThemedColor(com.google.android.material.R.attr.colorSurface)
    private val onSurface = activity.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
    private val primary = activity.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
    private val messageView = TextView(activity)
    private val contentView = buildContent(message)
    private val dialog = Dialog(activity).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        setContentView(contentView)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = .22f }
        }
    }

    private fun buildContent(message: CharSequence) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        minimumWidth = dp(176)
        setPadding(dp(22), dp(20), dp(22), dp(18))
        elevation = dp(12).toFloat()
        background = GradientDrawable().apply {
            setColor(surface)
            cornerRadius = dp(18).toFloat()
        }

        addView(CircularProgressIndicator(activity).apply {
            isIndeterminate = true
            indicatorSize = dp(28)
            trackThickness = dp(3)
            setIndicatorColor(primary)
            trackColor = blend(primary, surface, .80f)
            contentDescription = activity.getString(DesignR.string.loading)
        }, LinearLayout.LayoutParams(dp(30), dp(30)).apply {
            bottomMargin = dp(12)
        })

        addView(messageView.apply {
            text = message
            textSize = 14f
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 2
            maxWidth = dp(230)
            setLineSpacing(0f, 1.05f)
            setTextColor(onSurface)
            typeface = themedTypeface(Typeface.NORMAL)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun show() {
        if (!activity.isFinishing && !activity.isDestroyed && !dialog.isShowing) {
            dialog.show()
            dialog.window?.decorView?.let(ThemeTypography::install)
            dialog.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            contentView.alpha = 0f
            contentView.scaleX = .97f
            contentView.scaleY = .97f
            contentView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(120L)
                .start()
        }
    }

    fun update(message: CharSequence) {
        messageView.text = message
    }

    fun dismiss() {
        contentView.animate().cancel()
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun themedTypeface(style: Int): Typeface {
        return ThemeTypography.typeface(activity, emphasis = style == Typeface.BOLD)
    }

    private fun dp(value: Int) = (value * density).toInt()
    private fun blend(first: Int, second: Int, ratio: Float) = Color.rgb(
        (Color.red(first) * (1 - ratio) + Color.red(second) * ratio).toInt(),
        (Color.green(first) * (1 - ratio) + Color.green(second) * ratio).toInt(),
        (Color.blue(first) * (1 - ratio) + Color.blue(second) * ratio).toInt(),
    )
}

internal suspend fun <T> Activity.withCloudRequestLoading(
    message: CharSequence = "正在处理请求…",
    block: suspend () -> T,
): T {
    val loading = CloudRequestLoading(this, message)
    loading.show()
    return try {
        block()
    } finally {
        loading.dismiss()
    }
}
