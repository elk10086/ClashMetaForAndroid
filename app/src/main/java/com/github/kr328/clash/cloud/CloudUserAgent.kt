package com.github.kr328.clash.cloud

import com.github.kr328.clash.BuildConfig

/** Matches free_time_new's PackageInfo.ua client identity. */
object CloudUserAgent {
    val value: String
        get() = "${BuildConfig.BRAND_DISPLAY_NAME}/v${BuildConfig.BRAND_VERSION_NAME} clash-verge Platform/android"
}
