package com.github.kr328.clash.service

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.clash.clashRuntime
import com.github.kr328.clash.service.clash.module.AppListCacheModule
import com.github.kr328.clash.service.clash.module.ConfigurationModule
import com.github.kr328.clash.service.clash.module.NetworkObserveModule
import com.github.kr328.clash.service.clash.module.StaticNotificationModule
import com.github.kr328.clash.service.clash.module.SuspendModule
import com.github.kr328.clash.service.clash.module.TimeZoneModule
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/** Loads the active profile for browsing/selecting proxy groups without starting a VPN. */
class PreviewService : RuntimeService() {
    private val runtime = clashRuntime {
        val config = install(ConfigurationModule(this@PreviewService))
        val network = install(NetworkObserveModule(this@PreviewService))
        install(AppListCacheModule(this@PreviewService))
        install(TimeZoneModule(this@PreviewService))
        install(SuspendModule(this@PreviewService))

        try {
            while (isActive) {
                val quit = select<Boolean> {
                    config.onEvent { true }
                    network.onEvent { false }
                }
                if (quit) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Create preview runtime: ${e.message}", e)
        } finally {
            withContext(NonCancellable) {
                if (this@PreviewService.isActive) stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        StaticNotificationModule.createNotificationChannel(this)
        StaticNotificationModule.notifyLoadingNotification(this)
        if (StatusProvider.serviceRunning) {
            stopSelf()
            return
        }
        runtime.launch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        StaticNotificationModule.notifyLoadingNotification(this)
        if (StatusProvider.serviceRunning) stopSelf(startId)
        // A preview belongs to the visible client, not a VPN that should restart itself.
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder = Binder()
}
