package com.github.kr328.clash.design.util

import com.github.kr328.clash.core.model.Proxy

/** Pin strategy entries after the core's default/title/delay sort, without reordering peers. */
internal fun List<Proxy>.withPinnedStrategies(): List<Proxy> = sortedBy { proxy ->
    when {
        proxy.name.trim() == "自动选择" -> 0
        proxy.name.trim() == "故障转移" -> 1
        proxy.isGroup && (proxy.type.equals("URLTest", ignoreCase = true) ||
            proxy.type.equals("url-test", ignoreCase = true)) -> 0
        proxy.isGroup && proxy.type.equals("Fallback", ignoreCase = true) -> 1
        else -> 2
    }
}
