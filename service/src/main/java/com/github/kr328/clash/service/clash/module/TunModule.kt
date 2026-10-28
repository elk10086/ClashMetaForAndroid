package com.github.kr328.clash.service.clash.module

import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import androidx.core.content.getSystemService
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.util.parseInetSocketAddress
import java.net.InetSocketAddress
import java.security.SecureRandom

/** Owned by the runtime's try/finally, not by an asynchronously scheduled module. */
class TunModule(private val vpn: VpnService) {
    data class TunDevice(
        val fd: Int,
        var stack: String,
        val gateway: String,
        val portal: String,
        val dns: String,
    )

    private val connectivity = vpn.getSystemService<ConnectivityManager>()!!

    private fun queryUid(
        protocol: Int,
        source: InetSocketAddress,
        target: InetSocketAddress,
    ): Int {
        if (Build.VERSION.SDK_INT < 29)
            return -1

        return runCatching { connectivity.getConnectionOwnerUid(protocol, source, target) }
            .getOrElse { -1 }
    }

    fun listenHttp(): InetSocketAddress? {
        val r = { 1 + random.nextInt(199) }
        val listenAt = "127.${r()}.${r()}.${r()}:0"
        val address = Clash.startHttp(listenAt)

        return address?.let(::parseInetSocketAddress)
    }

    fun attach(device: TunDevice) {
        Clash.startTun(
            fd = device.fd,
            stack = device.stack,
            gateway = device.gateway,
            portal = device.portal,
            dns = device.dns,
            markSocket = vpn::protect,
            querySocketUid = this::queryUid
        )
    }

    fun close() {
        requestStop()
    }

    companion object {
        private val random = SecureRandom()

        fun requestStop() {
            // Release the VPN first; do not leave Android bound while HTTP drains.
            Clash.stopTun()
            Clash.stopHttp()
        }
    }
}
