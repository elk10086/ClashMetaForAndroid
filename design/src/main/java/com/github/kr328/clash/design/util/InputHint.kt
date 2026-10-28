package com.github.kr328.clash.design.util

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.github.kr328.clash.design.R
import com.google.android.material.textfield.TextInputLayout

fun CharSequence.compactInputHint(context: Context): CharSequence = SpannableString(toString()).apply {
    setSpan(
        AbsoluteSizeSpan(context.resources.getDimensionPixelSize(R.dimen.input_hint_text_size)),
        0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
}

fun View.applyCompactInputHints() {
    if (this is TextView) hint?.takeIf { it.isNotEmpty() }?.let { hint = it.compactInputHint(context) }
    if (this is TextInputLayout) hint?.takeIf { it.isNotEmpty() }?.let { hint = it.compactInputHint(context) }
    if (this is ViewGroup) repeat(childCount) { getChildAt(it).applyCompactInputHints() }
}
