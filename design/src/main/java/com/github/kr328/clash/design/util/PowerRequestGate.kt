package com.github.kr328.clash.design.util

/** Main-thread gate: reserve a toggle before it enters the asynchronous request queue. */
internal class PowerRequestGate {
    var pending = false
        private set

    fun acquire(): Boolean {
        if (pending) return false
        pending = true
        return true
    }

    fun release() {
        pending = false
    }
}
