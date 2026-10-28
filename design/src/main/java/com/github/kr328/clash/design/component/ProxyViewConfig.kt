package com.github.kr328.clash.design.component

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.getPixels
import com.github.kr328.clash.design.util.ThemeTypography
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.resolveThemedResourceId

class ProxyViewConfig(
    val context: Context,
    var proxyLine: Int,
    val showTechnicalDetails: Boolean = true,
) {
    private val uiStore = UiStore(context)
    val sakura = uiStore.cloudThemeStyle == 1
    private val colorSurface = context.resolveThemedColor(com.google.android.material.R.attr.colorSurface)
    private val colorPrimary = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
    private val colorOnSurface = context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)

    // Names and latency values share the selected skin's typography.
    val textTypeface: Typeface = ThemeTypography.typeface(context, emphasis = sakura)
    val secondaryTypeface: Typeface = ThemeTypography.typeface(context)
    val textScaleX: Float = when (uiStore.cloudThemeStyle) {
        1 -> 1f
        2 -> 1.03f
        else -> 1f
    }
    val titleLineSpacing = 3f * context.resources.displayMetrics.density
    private val textScale: Float = ThemeTypography.scale(context)

    val clickableBackground =
        context.resolveThemedResourceId(android.R.attr.selectableItemBackground)

    val selectedControl = if (uiStore.cloudThemeStyle == 1) {
        context.resolveThemedColor(com.google.android.material.R.attr.colorOnSecondaryContainer)
    } else colorOnSurface
    val selectedBackground = if (uiStore.cloudThemeStyle == 1) {
        context.resolveThemedColor(com.google.android.material.R.attr.colorSecondaryContainer)
    } else ColorUtils.blendARGB(colorSurface, colorPrimary, 0.16f)
    val selectedOutline = ColorUtils.setAlphaComponent(colorPrimary, 72)

    val unselectedControl = colorOnSurface
    val unselectedBackground = colorSurface
    val unselectedOutline = context.resolveThemedColor(com.google.android.material.R.attr.colorOutline)
    val cardTint = colorPrimary
    val secondaryControl = if (uiStore.cloudThemeStyle == 1) {
        context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
    } else ColorUtils.setAlphaComponent(colorOnSurface, 176)
    private val darkSurface = ColorUtils.calculateLuminance(colorSurface) < .5
    val delayGood = if (uiStore.cloudThemeStyle == 1) {
        Color.parseColor(if (darkSurface) "#91CDB4" else "#386A53")
    } else Color.rgb(46, 125, 50)
    val delaySlow = if (uiStore.cloudThemeStyle == 1) {
        Color.parseColor(if (darkSurface) "#D9BD80" else "#805D22")
    } else Color.rgb(197, 127, 10)
    val delayFailed = if (uiStore.cloudThemeStyle == 1) {
        context.resolveThemedColor(com.google.android.material.R.attr.colorError)
    } else Color.rgb(198, 40, 40)
    val delayUntested = secondaryControl

    val layoutPadding = if (sakura) 6f * context.resources.displayMetrics.density else context.getPixels(R.dimen.proxy_layout_padding).toFloat()
    val contentPadding
        get() = if (sakura) (if (proxyLine == 3) 10f else 14f) * context.resources.displayMetrics.density
        else if (proxyLine==2) context.getPixels(R.dimen.proxy_content_padding).toFloat() else context.getPixels(R.dimen.proxy_content_padding_grid3).toFloat()
    val textMargin
        get() = if (proxyLine==2) context.getPixels(R.dimen.proxy_text_margin).toFloat() else context.getPixels(R.dimen.proxy_text_margin_grid3).toFloat()
    val textSize
        get() = (if (proxyLine == 3) context.getPixels(R.dimen.proxy_text_size_grid3).toFloat() else context.getPixels(R.dimen.proxy_text_size).toFloat()) * textScale
    val secondaryTextSize
        get() = context.getPixels(R.dimen.proxy_secondary_text_size).toFloat() * textScale
    val delayTextSize
        get() = context.getPixels(R.dimen.proxy_delay_text_size).toFloat() * textScale

    val shadow = Color.argb(
        0x15,
        Color.red(Color.DKGRAY),
        Color.green(Color.DKGRAY),
        Color.blue(Color.DKGRAY),
    )

    val cardRadius = if (sakura) 20f * context.resources.displayMetrics.density else context.getPixels(R.dimen.proxy_card_radius).toFloat()
    var cardOffset = context.getPixels(R.dimen.proxy_card_offset).toFloat()
}
