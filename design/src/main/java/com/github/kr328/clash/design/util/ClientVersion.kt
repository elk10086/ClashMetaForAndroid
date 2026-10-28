package com.github.kr328.clash.design.util

/** Display-only edition label; never use it for update comparisons or package metadata. */
object ClientVersion {
    @JvmStatic
    fun display(version: String?): String {
        val number = version.orEmpty().trim().removePrefix("v").removePrefix("V")
        return "v${number.ifBlank { "—" }} Pro"
    }
}
