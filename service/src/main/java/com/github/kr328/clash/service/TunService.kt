package com.github.kr328.clash.service

import android.annotation.TargetApi
import android.app.PendingIntent
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.service.clash.clashRuntime
import com.github.kr328.clash.service.clash.module.*
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.parseCIDR
import com.github.kr328.clash.service.util.sendClashStarted
import com.github.kr328.clash.service.util.sendClashStopped
import com.github.kr328.clash.service.util.ServiceStopReceiver
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

class TunService : VpnService(), CoroutineScope by CoroutineScope(Dispatchers.Default) {
    private val self: TunService
        get() = this

    private var reason: String? = null
    private var ownsRuntime = false
    private val stopReceiver = ServiceStopReceiver(this, ::requestStop)

    private val runtime = clashRuntime {
        val store = ServiceStore(self)

        val tun = TunModule(self)
        try {
            val config = install(ConfigurationModule(self))
            val network = install(NetworkObserveModule(self))

            if (store.dynamicNotification)
                install(DynamicNotificationModule(self))
            else
                install(StaticNotificationModule(self))

            install(AppListCacheModule(self))
            install(TimeZoneModule(self))
            install(SuspendModule(self))

            config.awaitReady()
            currentCoroutineContext().ensureActive()
            tun.open()
            currentCoroutineContext().ensureActive()
            Log.i("TunService: tunnel ready after profile and selections restored")
            // Keep READY/STOPPED broadcasts ordered with Android lifecycle callbacks.
            withContext(Dispatchers.Main) {
                if (this@TunService.isActive && ownsRuntime) sendClashStarted()
            }

            while (isActive) {
                val quit = select<Boolean> {
                    config.onEvent {
                        reason = it.message

                        true
                    }
                    network.onEvent { n ->
                        if (Build.VERSION.SDK_INT in 22..28) @TargetApi(22) {
                            setUnderlyingNetworks(n?.let { arrayOf(it) })
                        }

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
                tun.close()

                if (this@TunService.isActive) stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Every startForegroundService request must be acknowledged, even duplicates.
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

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        // The runtime's finally closes native resources on its dispatcher, under the
        // handover lock. Doing it here can block the next service's foreground deadline.
        cancel()
        stopReceiver.unregister()
        if (ownsRuntime) {
            StatusProvider.serviceRunning = false
            sendClashStopped(reason)
        }

        Log.i("TunService destroyed: ${reason ?: "successfully"}")

        super.onDestroy()
    }

    private fun requestStop() {
        // Cancellation starts tunnel cleanup even while Android still binds this VPN.
        cancel()
        stopSelf()
    }

    override fun onRevoke() {
        Log.i("TunService: VPN permission revoked")
        requestStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        runtime.requestGc()
    }

    private fun TunModule.open() {
        val store = ServiceStore(self)

        val device = with(Builder()) {
            // Interface address
            addAddress(TUN_GATEWAY, TUN_SUBNET_PREFIX)
            if (store.allowIpv6) {
                addAddress(TUN_GATEWAY6, TUN_SUBNET_PREFIX6)
            }

            // Route
            if (store.bypassPrivateNetwork) {
                resources.getStringArray(R.array.bypass_private_route).map(::parseCIDR).forEach {
                    addRoute(it.ip, it.prefix)
                }
                if (store.allowIpv6) {
                    resources.getStringArray(R.array.bypass_private_route6).map(::parseCIDR).forEach {
                        addRoute(it.ip, it.prefix)
                    }
                }

                // Route of virtual DNS
                addRoute(TUN_DNS, 32)
                if (store.allowIpv6) {
                    addRoute(TUN_DNS6, 128)
                }
            } else {
                addRoute(NET_ANY, 0)
                if (store.allowIpv6) {
                    addRoute(NET_ANY6, 0)
                }
            }

            // Access Control
            when (store.accessControlMode) {
                AccessControlMode.AcceptAll -> Unit
                AccessControlMode.AcceptSelected -> {
                    (store.accessControlPackages + packageName).forEach {
                        runCatching { addAllowedApplication(it) }
                    }
                }
                AccessControlMode.DenySelected -> {
                    (store.accessControlPackages - packageName).forEach {
                        runCatching { addDisallowedApplication(it) }
                    }
                }
            }

            // Blocking
            setBlocking(false)

            // Mtu
            setMtu(TUN_MTU)

            // Session Name
            setSession(getString(R.string.clash_meta_for_android))

            // Virtual Dns Server
            addDnsServer(TUN_DNS)
            if (store.allowIpv6) {
                addDnsServer(TUN_DNS6)
            }

            // Open MainActivity
            setConfigureIntent(
                PendingIntent.getActivity(
                    self,
                    R.id.nf_vpn_status,
                    Intent().setComponent(Components.MAIN_ACTIVITY),
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
                )
            )

            // Metered
            if (Build.VERSION.SDK_INT >= 29) {
                setMetered(false)
            }

            // System Proxy
            if (Build.VERSION.SDK_INT >= 29 && store.systemProxy) {
                listenHttp()?.let {
                    setHttpProxy(
                        ProxyInfo.buildDirectProxy(
                            it.address.hostAddress,
                            it.port,
                            HTTP_PROXY_BLACK_LIST + if (store.bypassPrivateNetwork) HTTP_PROXY_LOCAL_LIST else emptyList()
                        )
                    )
                }
            }

            if (store.allowBypass) {
                allowBypass()
            }

            TunModule.TunDevice(
                fd = establish()?.detachFd()
                    ?: throw NullPointerException("Establish VPN rejected by system"),
                stack = store.tunStackMode,
                gateway = "$TUN_GATEWAY/$TUN_SUBNET_PREFIX" + if (store.allowIpv6) ",$TUN_GATEWAY6/$TUN_SUBNET_PREFIX6" else "",
                portal = TUN_PORTAL + if (store.allowIpv6) ",$TUN_PORTAL6" else "",
                dns = if (store.dnsHijacking) NET_ANY else (TUN_DNS + if (store.allowIpv6) ",$TUN_DNS6" else ""),
            )
        }

        attach(device)
    }

    companion object {
        private const val TUN_MTU = 9000
        private const val TUN_SUBNET_PREFIX = 30
        private const val TUN_GATEWAY = "172.19.0.1"
        private const val TUN_SUBNET_PREFIX6 = 126
        private const val TUN_GATEWAY6 = "fdfe:dcba:9876::1"
        private const val TUN_PORTAL = "172.19.0.2"
        private const val TUN_PORTAL6 = "fdfe:dcba:9876::2"
        private const val TUN_DNS = TUN_PORTAL
        private const val TUN_DNS6 = TUN_PORTAL6
        private const val NET_ANY = "0.0.0.0"
        private const val NET_ANY6 = "::"

        private val HTTP_PROXY_LOCAL_LIST: List<String> = listOf(
            "localhost",
            "*.local",
            "127.*",
            "10.*",
            "172.16.*",
            "172.17.*",
            "172.18.*",
            "172.19.*",
            "172.2*",
            "172.30.*",
            "172.31.*",
            "192.168.*"
        )
        private val HTTP_PROXY_BLACK_LIST: List<String> = listOf(
            "*zhihu.com",
            "*zhimg.com",
            "*jd.com",
            "100ime-iat-api.xfyun.cn",
            "*360buyimg.com",
        )
    }
}
