package com.github.kr328.clash.service

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.service.clash.clashRuntime
import com.github.kr328.clash.service.clash.module.*
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.sendClashStarted
import com.github.kr328.clash.service.util.sendClashStopped
import com.github.kr328.clash.service.util.ServiceStopReceiver
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class ClashService : RuntimeService() {
    private val self: ClashService
        get() = this

    private var reason: String? = null
    private var ownsRuntime = false
    private val stopReceiver = ServiceStopReceiver(this) {
        cancel()
        stopSelf()
    }

    private val runtime = clashRuntime {
        val store = ServiceStore(self)

        val config = install(ConfigurationModule(self))
        val network = install(NetworkObserveModule(self))

        if (store.dynamicNotification)
            install(DynamicNotificationModule(self))
        else
            install(StaticNotificationModule(self))

        install(AppListCacheModule(self))
        install(TimeZoneModule(self))
        install(SuspendModule(self))

        try {
            while (isActive) {
                val quit = select<Boolean> {
                    config.onEvent {
                        reason = it.message

                        true
                    }
                    network.onEvent {
                        false
                    }
                }

                if (quit) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Create clash runtime: ${e.message}", e)

            reason = e.message
        } finally {
            withContext(NonCancellable) {
                if (this@ClashService.isActive) stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        StaticNotificationModule.createNotificationChannel(this)
        StaticNotificationModule.notifyLoadingNotification(this)

        if (StatusProvider.serviceRunning)
            return stopSelf()

        ownsRuntime = true
        StatusProvider.serviceRunning = true
        stopReceiver.register()
        stopService(PreviewService::class.intent)

        runtime.launch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        StaticNotificationModule.notifyLoadingNotification(this)
        if (ownsRuntime && isActive) sendClashStarted() else stopSelf(startId)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return Binder()
    }

    override fun onDestroy() {
        stopReceiver.unregister()
        if (ownsRuntime) {
            StatusProvider.serviceRunning = false
            sendClashStopped(reason)
        }

        Log.i("ClashService destroyed: ${reason ?: "successfully"}")

        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        runtime.requestGc()
    }
}
