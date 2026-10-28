package com.github.kr328.clash.design.util

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.github.kr328.clash.design.store.UiStore
import com.google.android.material.R

/** Three levels of surface: quiet information, featured membership, selected option. */
object SakuraCards {
    fun enabled(context: Context) = UiStore(context).cloudThemeStyle == 1

    fun background(context: Context, featured: Boolean = false, selected: Boolean = false,
                   radius: Float = if (featured) 24f else 20f): GradientDrawable {
        val surface = context.resolveThemedColor(R.attr.colorSurface)
        val primary = context.resolveThemedColor(R.attr.colorPrimary)
        val selection = context.resolveThemedColor(R.attr.colorSecondaryContainer)
        val start = if (selected) selection else surface
        val end = when {
            selected -> ColorUtils.blendARGB(selection, surface, .18f)
            featured -> selection
            else -> ColorUtils.blendARGB(surface, primary, .04f)
        }
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply {
            cornerRadius = radius * context.resources.displayMetrics.density
            if (selected) setStroke(
                (context.resources.displayMetrics.density * .75f).toInt().coerceAtLeast(1),
                ColorUtils.setAlphaComponent(primary, 100),
            )
        }
    }

    fun apply(view: View, featured: Boolean = false) {
        if (!enabled(view.context)) return
        val shape = background(view.context, featured)
        view.background = if (view.isClickable) RippleDrawable(
            ColorStateList.valueOf(view.context.resolveThemedColor(R.attr.colorControlHighlight)),
            shape, GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = shape.cornerRadius
            },
        ) else shape
        view.elevation = view.resources.displayMetrics.density * if (featured) 2f else 1f
        useReadableSecondaryText(view)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val shadow = ColorUtils.setAlphaComponent(view.context.resolveThemedColor(R.attr.colorPrimary), 45)
            view.outlineAmbientShadowColor = shadow
            view.outlineSpotShadowColor = shadow
        }
    }

    private fun useReadableSecondaryText(view: View) {
        if (view is TextView && view.alpha in .01f.. .99f) {
            view.setTextColor(view.context.resolveThemedColor(R.attr.colorOnSurfaceVariant))
            view.alpha = 1f
        }
        if (view is ViewGroup) repeat(view.childCount) { useReadableSecondaryText(view.getChildAt(it)) }
    }
}
