package com.github.kr328.clash.util

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Main-thread confined: a newer user command always supersedes an automatic restart. */
internal class PowerRequestGeneration {
    private var generation = 0L

    fun onUserRequest() {
        generation++
    }

    suspend fun restartIfUnchanged(
        stop: () -> Unit,
        awaitStop: suspend () -> Unit,
        start: () -> Unit,
    ) {
        val capturedGeneration = generation
        stop()
        awaitStop()
        currentCoroutineContext().ensureActive()
        if (capturedGeneration == generation) start()
    }
}
