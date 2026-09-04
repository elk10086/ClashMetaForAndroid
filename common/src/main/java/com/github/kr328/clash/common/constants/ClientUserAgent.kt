package com.github.kr328.clash.common.constants

import android.content.Context

object ClientUserAgent {
    @Suppress("DEPRECATION")
    fun value(context: Context): String {
        val brand = context.applicationInfo.loadLabel(context.packageManager).toString()
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        return "$brand/v$version clash-verge Platform/android"
    }
}
