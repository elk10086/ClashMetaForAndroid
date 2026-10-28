package com.github.kr328.clash.design.util

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.github.kr328.clash.design.R

/** A complete rounded Chinese family, shared by text, numbers and dialog content. */
object SakuraTypography {
    private var rounded: Typeface? = null

    fun typeface(context: Context): Typeface = rounded ?: requireNotNull(
        ResourcesCompat.getFont(context, R.font.chill_round_m),
    ).also { rounded = it }

    fun apply(view: TextView) {
        view.typeface = typeface(view.context)
        // The original family has one weight. Preserve its rounded strokes instead
        // of synthesizing heavy bold; headings keep their existing size/color hierarchy.
        view.paint.isFakeBoldText = false
        view.textScaleX = 1f
        view.letterSpacing = 0f
        // Ask for tabular figures when provided by the bundled family.
        view.fontFeatureSettings = "tnum"
        // Only expand freely sized labels; never inflate fixed-height controls/inputs.
        if (view !is EditText && view.maxLines > 1 &&
            view.layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
            view.setLineSpacing(0f, 1.08f)
        }
    }
}
