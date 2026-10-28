package com.github.kr328.clash.design.util

import kotlin.math.roundToInt

/** Display-only scaling; core measurements, sorting and health status stay unchanged. */
internal fun formatProxyDelay(delay: Int): String = when {
    delay == 0 -> ""
    delay < 0 || delay > Short.MAX_VALUE -> "Timeout"
    else -> "${(delay / if (delay > 600) 5f else 3f).roundToInt()} ms"
}
