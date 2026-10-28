package com.github.kr328.clash.common.util

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class HttpRaceTest {
    private class Connection : HttpURLConnection(URL("http://localhost/")) {
        val closed = CountDownLatch(1)
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { closed.countDown() }
    }

    @Test fun winnerDoesNotWaitForUninterruptibleLoser() = runBlocking {
        val slow = Connection()
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        try {
            val result = withTimeout(2_000) {
                firstSuccessfulHttp(listOf("slow", "fast")) { candidate ->
                    if (candidate == "fast") {
                        started.await()
                        "fast"
                    } else slow.readCancellable {
                        started.complete(Unit)
                        // Simulate a socket implementation that ignores interruption.
                        while (release.count > 0) {
                            try { release.await() } catch (_: InterruptedException) { }
                        }
                        "slow"
                    }
                }
            }
            assertEquals("fast", result)
            assertTrue(slow.closed.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
        }
    }

    @Test fun failedAndEmptyResponsesDoNotWin() = runBlocking {
        val result = firstSuccessfulHttp(listOf(0, 1, 2)) {
            when (it) {
                0 -> throw IOException("offline")
                1 -> null
                else -> "valid"
            }
        }
        assertEquals("valid", result)
        assertNull(firstSuccessfulHttp(listOf(0, 1)) { _: Int -> throw IOException("offline") })
    }

    @Test fun cancellingCallerDisconnectsRequestWithoutWaitingForRead() = runBlocking {
        val connection = Connection()
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        try {
            val task = async {
                connection.readCancellable {
                    started.complete(Unit)
                    while (release.count > 0) {
                        try { release.await() } catch (_: InterruptedException) { }
                    }
                    "late response"
                }
            }
            withTimeout(2_000) {
                started.await()
                task.cancel()
                task.join()
            }
            assertTrue(task.isCancelled)
            assertTrue(connection.closed.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
        }
    }
}
