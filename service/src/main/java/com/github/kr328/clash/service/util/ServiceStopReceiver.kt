package com.github.kr328.clash.service.util

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.github.kr328.clash.common.compat.registerReceiverCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.constants.Permissions
import com.github.kr328.clash.common.log.Log

/** Registered synchronously on the service main thread, before any core work is queued. */
internal class ServiceStopReceiver(private val service: Service, private val stop: () -> Unit) {
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intents.ACTION_CLASH_REQUEST_STOP) {
                Log.i("${service.javaClass.simpleName}: stop requested")
                stop()
            }
        }
    }

    fun register() {
        if (registered) return
        service.registerReceiverCompat(
            receiver,
            IntentFilter(Intents.ACTION_CLASH_REQUEST_STOP),
            Permissions.RECEIVE_SELF_BROADCASTS,
        )
        registered = true
    }

    fun unregister() {
        if (!registered) return
        service.unregisterReceiver(receiver)
        registered = false
    }
}
