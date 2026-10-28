package com.github.kr328.clash.service.clash.module

/** Owns successful registrations until the module, rather than its Service, has finished. */
internal class BroadcastRegistrations {
    private data class Registration(val unregister: () -> Unit, val closeChannel: () -> Unit)

    private val registrations = mutableListOf<Registration>()
    private var closed = false

    @Synchronized
    fun register(register: () -> Unit, unregister: () -> Unit, closeChannel: () -> Unit) {
        try {
            check(!closed) { "Module broadcasts are already closed" }
            register()
            registrations += Registration(unregister, closeChannel)
        } catch (e: Exception) {
            closeChannel()
            throw e
        }
    }

    fun close() {
        val pending = synchronized(this) {
            if (closed) return
            closed = true
            registrations.toList().also { registrations.clear() }
        }

        var failure: Exception? = null
        pending.forEach { registration ->
            try {
                registration.unregister()
            } catch (e: Exception) {
                // Finish releasing the remaining receivers, but do not hide a lifecycle error.
                if (failure == null) failure = e else failure!!.addSuppressed(e)
            } finally {
                registration.closeChannel()
            }
        }
        failure?.let { throw it }
    }
}
