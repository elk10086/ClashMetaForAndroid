package com.github.kr328.clash.service.clash

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeHandoverTest {
    @Test fun nextCoreWaitsForModuleTeardownAndNativeReset() = runBlocking {
        withTimeout(5_000) {
            val handover = RuntimeHandover()
            val moduleStarted = CompletableDeferred<Unit>()
            val moduleClosing = CompletableDeferred<Unit>()
            val releaseModule = CompletableDeferred<Unit>()
            val resetting = CompletableDeferred<Unit>()
            val releaseReset = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val old = launch {
                handover.run(cleanup = {
                    order += "reset"
                    resetting.complete(Unit)
                    releaseReset.await()
                }) {
                    launch {
                        try {
                            moduleStarted.complete(Unit)
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                moduleClosing.complete(Unit)
                                releaseModule.await()
                                order += "module closed"
                            }
                        }
                    }
                    awaitCancellation()
                }
            }
            moduleStarted.await()
            old.cancel()
            moduleClosing.await()
            val next = launch(start = CoroutineStart.UNDISPATCHED) {
                handover.run(cleanup = {}) { order += "next initialized" }
            }
            assertTrue(order.isEmpty())
            releaseModule.complete(Unit)
            resetting.await()
            assertEquals(listOf("module closed", "reset"), order)
            releaseReset.complete(Unit)
            joinAll(old, next)
            assertEquals(listOf("module closed", "reset", "next initialized"), order)
        }
    }

    @Test fun cancellingAQueuedStartNeverResetsTheActiveCore() = runBlocking {
        withTimeout(5_000) {
            val handover = RuntimeHandover()
            val started = CompletableDeferred<Unit>()
            var resets = 0
            val active = launch {
                handover.run(cleanup = { resets++ }) {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
            started.await()
            val queued = launch(start = CoroutineStart.UNDISPATCHED) {
                handover.run(cleanup = { fail("Queued start must not reset the active core") }) {
                    fail("Queued start must not initialize")
                }
            }
            queued.cancelAndJoin()
            assertEquals(0, resets)
            active.cancelAndJoin()
            assertEquals(1, resets)
        }
    }

    @Test fun normalStopCancelsModulesBeforeReset() = runBlocking {
        val handover = RuntimeHandover()
        val started = CompletableDeferred<Unit>()
        var closed = false
        handover.run(cleanup = { assertTrue(closed) }) {
            launch {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    closed = true
                }
            }
            started.await()
        }
        assertTrue(closed)
    }

    @Test fun initializationFailureStillCleansUpAndUnlocks() = runBlocking {
        val handover = RuntimeHandover()
        var resets = 0
        try {
            handover.run(cleanup = { resets++ }) { error("invalid configuration") }
            fail("Expected failure")
        } catch (_: IllegalStateException) {
            assertEquals(1, resets)
        }
        handover.run(cleanup = { resets++ }) { }
        assertEquals(2, resets)
    }

    @Test fun manyRapidStartsAndStopsNeverOverlapCoreOwnership() = runBlocking {
        withTimeout(5_000) {
            val handover = RuntimeHandover()
            var active = 0
            val jobs = List(100) {
                launch {
                    handover.run(cleanup = { active--; assertEquals(0, active) }) {
                        active++
                        assertEquals(1, active)
                        yield()
                    }
                }
            }
            yield()
            jobs.forEachIndexed { index, job -> if (index % 2 == 0) job.cancel() }
            jobs.joinAll()
            assertEquals(0, active)
        }
    }

    @Test fun cancellationDuringResourceOpenClosesItBeforeTheNextCoreStarts() = runBlocking {
        withTimeout(5_000) {
            val handover = RuntimeHandover()
            val opening = CompletableDeferred<Unit>()
            val releaseOpen = CompletableDeferred<Unit>()
            val closing = CompletableDeferred<Unit>()
            val releaseClose = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            var nextStarted = false
            val old = launch {
                handover.run(cleanup = { order += "reset" }) {
                    try {
                        // Model synchronous/native VPN establishment: cancellation
                        // cannot interrupt it, but its owner must still run finally.
                        withContext(NonCancellable) {
                            opening.complete(Unit)
                            releaseOpen.await()
                            order += "opened"
                        }
                        currentCoroutineContext().ensureActive()
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            closing.complete(Unit)
                            releaseClose.await()
                            order += "closed"
                        }
                    }
                }
            }
            opening.await()
            old.cancel()
            val next = launch(start = CoroutineStart.UNDISPATCHED) {
                handover.run(cleanup = {}) {
                    nextStarted = true
                    order += "next initialized"
                }
            }
            assertFalse(nextStarted)
            releaseOpen.complete(Unit)
            closing.await()
            assertEquals(listOf("opened"), order)
            assertFalse(nextStarted)
            releaseClose.complete(Unit)
            joinAll(old, next)
            assertEquals(listOf("opened", "closed", "reset", "next initialized"), order)
        }
    }

    @Test fun explicitResourceCleanupRunsEvenWhenCancelledBeforeModulesStart() = runBlocking {
        withTimeout(5_000) {
            val handover = RuntimeHandover()
            var resourceOpened = false
            var closes = 0
            var resets = 0
            val old = launch {
                handover.run(cleanup = { resets++ }) {
                    // A scheduled module is not guaranteed to enter its try/finally
                    // if its parent is cancelled before it starts.
                    launch(start = CoroutineStart.LAZY) {
                        fail("A cancelled, not-yet-started module must not run")
                    }
                    try {
                        coroutineContext.job.cancel()
                        currentCoroutineContext().ensureActive()
                        resourceOpened = true
                    } finally {
                        withContext(NonCancellable) { closes++ }
                    }
                }
            }
            old.join()
            assertFalse(resourceOpened)
            assertEquals(1, closes)
            assertEquals(1, resets)
            handover.run(cleanup = {}) { assertEquals(1, closes) }
        }
    }

    @Test fun ownedTunnelClosesWhileUncancellableConfigurationIsStillFinishing() = runBlocking {
        withTimeout(5_000) {
            val handover = RuntimeHandover()
            val loading = CompletableDeferred<Unit>()
            val releaseLoad = CompletableDeferred<Unit>()
            val tunnelClosed = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            var nextStarted = false
            val old = launch {
                handover.run(cleanup = { order += "reset" }) {
                    launch {
                        withContext(NonCancellable) {
                            loading.complete(Unit)
                            releaseLoad.await()
                            order += "configuration finished"
                        }
                    }
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            order += "tunnel closed"
                            tunnelClosed.complete(Unit)
                        }
                    }
                }
            }
            loading.await()
            old.cancel()
            tunnelClosed.await()
            assertFalse(old.isCompleted)
            assertEquals(listOf("tunnel closed"), order)
            val next = launch(start = CoroutineStart.UNDISPATCHED) {
                handover.run(cleanup = {}) {
                    nextStarted = true
                    order += "next initialized"
                }
            }
            assertFalse(nextStarted)
            releaseLoad.complete(Unit)
            joinAll(old, next)
            assertEquals(
                listOf("tunnel closed", "configuration finished", "reset", "next initialized"),
                order,
            )
        }
    }

    @Test fun initializationFailureClosesOwnedResourcesBeforeResetAndNextStart() = runBlocking {
        val handover = RuntimeHandover()
        val order = mutableListOf<String>()
        try {
            handover.run(cleanup = { order += "reset" }) {
                try {
                    order += "partial resource opened"
                    error("VPN establishment failed")
                } finally {
                    withContext(NonCancellable) { order += "resource closed" }
                }
            }
            fail("Expected failure")
        } catch (_: IllegalStateException) {
            handover.run(cleanup = {}) { order += "next initialized" }
        }
        assertEquals(
            listOf("partial resource opened", "resource closed", "reset", "next initialized"),
            order,
        )
    }
}
