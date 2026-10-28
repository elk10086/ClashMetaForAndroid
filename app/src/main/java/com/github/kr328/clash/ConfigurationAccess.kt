package com.github.kr328.clash

import android.content.Context
import com.github.kr328.clash.common.CloudFeatureAccess

internal fun Context.canViewConfigurationModule(): Boolean {
    return CloudFeatureAccess.canViewConfiguration(this, BuildConfig.DEBUG)
}
