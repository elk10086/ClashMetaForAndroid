package com.github.kr328.clash.common.util

import java.net.HttpURLConnection
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine

// Blocking socket reads must not keep a cancelled race's coroutine scope alive.
// The bounded pool and connection timeouts limit the lifetime of remaining I/O.
private val httpWorkers = Executors.newFixedThreadPool(16) { task ->
    Thread(task, "cloud-http").apply { isDaemon = true }
}
private val httpCleanup = Executors.newFixedThreadPool(2) { task ->
    Thread(task, "cloud-http-cleanup").apply { isDaemon = true }
}

suspend fun <T> HttpURLConnection.readCancellable(read: (HttpURLConnection) -> T): T =
    suspendCancellableCoroutine { continuation ->
        val task = httpWorkers.submit {
            try {
                if (continuation.isActive) continuation.resume(read(this))
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                disconnect()
            }
        }
        continuation.invokeOnCancellation {
            task.cancel(true)
            // disconnect() can itself wait on an implementation's socket lock.
            // Never run it on the UI thread or delay the winning response.
            httpCleanup.execute { disconnect() }
        }
    }

suspend fun <I, T : Any> firstSuccessfulHttp(
    candidates: List<I>,
    request: suspend (I) -> T?,
): T? = coroutineScope {
    val pending = candidates.map { candidate ->
        async {
            try {
                request(candidate)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
    }.toMutableList()
    try {
        while (pending.isNotEmpty()) {
            val (index, value) = select<Pair<Int, T?>> {
                pending.forEachIndexed { index, task -> task.onAwait { index to it } }
            }
            pending.removeAt(index)
            if (value != null) return@coroutineScope value
        }
        null
    } finally {
        pending.forEach { it.cancel() }
    }
}
