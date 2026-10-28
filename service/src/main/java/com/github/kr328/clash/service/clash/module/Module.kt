package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.github.kr328.clash.common.compat.registerReceiverCompat
import com.github.kr328.clash.common.constants.Permissions
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.SelectClause1
import kotlinx.coroutines.withContext

abstract class Module<E>(val service: Service) {
    private val events: Channel<E> = Channel(Channel.UNLIMITED)
    // A Service can be destroyed before asynchronous module teardown finishes. Android
    // automatically unregisters receivers owned by that Service Context at that point.
    // Keep both operations on the same application Context so the module owns their lifetime.
    private val broadcastContext = service.applicationContext
    private val receivers = BroadcastRegistrations()

    val onEvent: SelectClause1<E>
        get() = events.onReceive

    protected suspend fun enqueueEvent(event: E) {
        events.send(event)
    }

    protected fun receiveBroadcast(
        requireSelf: Boolean = true,
        capacity: Int = Channel.UNLIMITED,
        configure: IntentFilter.() -> Unit
    ): ReceiveChannel<Intent> {
        val filter = IntentFilter().apply(configure)
        val channel = Channel<Intent>(capacity)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent != null) channel.trySend(intent)
            }
        }

        receivers.register(
            register = {
                broadcastContext.registerReceiverCompat(
                    receiver,
                    filter,
                    if (requireSelf) Permissions.RECEIVE_SELF_BROADCASTS else null,
                    null
                )
            },
            unregister = { broadcastContext.unregisterReceiver(receiver) },
            closeChannel = { channel.cancel() }
        )

        return channel
    }

    suspend fun execute() {
        val moduleName = this.javaClass.simpleName

        try {
            Log.d("$moduleName: initialize")

            run()
        } finally {
            withContext(NonCancellable) {
                receivers.close()

                Log.d("$moduleName: destroyed")
            }
        }
    }

    protected abstract suspend fun run()
}
