package com.github.kr328.clash.cloud

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One foreground scheduler for all pages, independent of the dashboard/release request. */
internal object CloudMembershipRefresh {
    private val scope = MainScope()
    private var job: Job? = null

    fun setVisible(context: Context, visible: Boolean) {
        if (!visible) {
            job?.cancel()
            job = null
            return
        }
        if (job?.isActive == true) return
        val application = context.applicationContext
        job = scope.launch {
            while (isActive) {
                try {
                    CloudApi(application).refreshSubscriptionIfDue()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep the last snapshot; the cache persists a 15-minute retry deadline.
                }
                // Only check local deadlines here, not the API every 30 seconds.
                delay(30_000L)
            }
        }
    }
}
