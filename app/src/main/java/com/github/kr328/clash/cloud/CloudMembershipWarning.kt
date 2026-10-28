package com.github.kr328.clash.cloud

internal enum class CloudMembershipWarning {
    EXPIRING,
    LOW_TRAFFIC,
    TRAFFIC_EXHAUSTED,
}

/** Match the dashboard's expiry semantics and binary (1024-based) traffic units. */
internal fun CloudSubscription.dashboardWarnings(nowMillis: Long): List<CloudMembershipWarning> {
    if (planId == null) return emptyList()
    val warnings = mutableListOf<CloudMembershipWarning>()
    if (expiresAt > 0) {
        val remainingMillis = expiresAt * 1_000L - nowMillis
        // Expired plans keep the existing expired card, rather than an imminent-expiry hint.
        if (remainingMillis <= 0) return emptyList()
        if (remainingMillis < 3 * 24 * 60 * 60 * 1_000L) {
            warnings += CloudMembershipWarning.EXPIRING
        }
    }
    // Traffic is independent of expiry: timed and untimed plans can both run low.
    if (total >= 0) {
        val remainingBytes = (total - used.coerceAtLeast(0)).coerceAtLeast(0)
        when {
            remainingBytes == 0L -> warnings += CloudMembershipWarning.TRAFFIC_EXHAUSTED
            remainingBytes < 3 * 1_024 * 1_024 * 1_024L -> warnings += CloudMembershipWarning.LOW_TRAFFIC
        }
    }
    return warnings
}
