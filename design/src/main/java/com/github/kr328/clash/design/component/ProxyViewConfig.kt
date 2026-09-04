package com.github.kr328.clash.design.component

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.getPixels
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.resolveThemedResourceId

class ProxyViewConfig(val context: Context, var proxyLine: Int) {
    private val uiStore = UiStore(context)
    private val colorSurface = context.resolveThemedColor(com.google.android.material.R.attr.colorSurface)

    val textTypeface: Typeface = Typeface.create(
        ResourcesCompat.getFont(context, when (uiStore.cloudThemeStyle) {
            0 -> R.font.skin_minimal
            1 -> R.font.skin_sakura
            2 -> R.font.skin_god
            else -> R.font.skin_anime
        }),
        Typeface.NORMAL,
    )
    val textScaleX: Float = when (uiStore.cloudThemeStyle) {
        1 -> .90f
        2 -> 1.03f
        else -> 1f
    }
    private val textScale: Float = if (uiStore.cloudTextScaleEnabled) {
        uiStore.cloudTextScalePercent.coerceIn(80, 140) / 100f
    } else 1f

    val clickableBackground =
        context.resolveThemedResourceId(android.R.attr.selectableItemBackground)

    val selectedControl = context.resolveThemedColor(com.google.android.material.R.attr.colorOnPrimary)
    val selectedBackground = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)

    val unselectedControl = context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
    val unselectedBackground: Int
        get() = if (proxyLine==1) Color.TRANSPARENT else colorSurface

    val layoutPadding = context.getPixels(R.dimen.proxy_layout_padding).toFloat()
    val contentPadding
        get() = if (proxyLine==2) context.getPixels(R.dimen.proxy_content_padding).toFloat() else context.getPixels(R.dimen.proxy_content_padding_grid3).toFloat()
    val textMargin
        get() = if (proxyLine==2) context.getPixels(R.dimen.proxy_text_margin).toFloat() else context.getPixels(R.dimen.proxy_text_margin_grid3).toFloat()
    val textSize
        get() = (if (proxyLine==2) context.getPixels(R.dimen.proxy_text_size).toFloat() else context.getPixels(R.dimen.proxy_text_size_grid3).toFloat()) * textScale

    val shadow = Color.argb(
        0x15,
        Color.red(Color.DKGRAY),
        Color.green(Color.DKGRAY),
        Color.blue(Color.DKGRAY),
    )

    val cardRadius = context.getPixels(R.dimen.proxy_card_radius).toFloat()
    var cardOffset = context.getPixels(R.dimen.proxy_card_offset).toFloat()
}
