package com.github.kr328.clash.design.util

import kotlin.math.abs

/** The rounded Sakura face reads slightly larger; keep every renderer on one scale. */
internal fun typographyScale(skin: Int, customScaleEnabled: Boolean, customScalePercent: Int): Float {
    val themeScale = if (skin == 1) .94f else 1f
    val customScale = if (customScaleEnabled) customScalePercent.coerceIn(80, 140) / 100f else 1f
    return themeScale * customScale
}

/** Reuse the unscaled baseline, unless the component explicitly sets a new size. */
internal fun typographyBaseSize(base: Float?, applied: Float?, current: Float): Float =
    if (base != null && applied != null && abs(current - applied) <= .01f) base else current
