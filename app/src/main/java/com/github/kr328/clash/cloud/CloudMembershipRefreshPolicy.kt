package com.github.kr328.clash.cloud

/** Wall-clock deadlines survive process restarts and include time spent in the background. */
internal object CloudMembershipRefreshPolicy {
    const val INTERVAL = 24 * 60 * 60 * 1_000L
    const val RETRY = 15 * 60 * 1_000L

    fun isDue(now: Long, lastSuccess: Long, retryAfter: Long): Boolean {
        if (lastSuccess > 0 && now >= lastSuccess && now - lastSuccess < INTERVAL) return false
        // Ignore impossible future deadlines after the system clock moves backwards.
        return retryAfter <= now || retryAfter - now > RETRY
    }
}
