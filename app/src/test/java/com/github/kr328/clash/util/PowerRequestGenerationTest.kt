package com.github.kr328.clash.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerRequestGenerationTest {
    @Test fun unchangedUserIntentCompletesTheAutomaticRestart() = runBlocking {
        val guard = PowerRequestGeneration()
        val events = mutableListOf<String>()

        guard.restartIfUnchanged(
            stop = { events += "stop" },
            awaitStop = { events += "wait" },
            start = { events += "start" },
        )

        assertEquals(listOf("stop", "wait", "start"), events)
    }

    @Test fun userStopDuringTheWaitPreventsAutomaticRestart() = runBlocking {
        withTimeout(5_000) {
            val guard = PowerRequestGeneration()
            val releaseStop = CompletableDeferred<Unit>()
            var starts = 0
            val restart = launch(start = CoroutineStart.UNDISPATCHED) {
                guard.restartIfUnchanged({}, { releaseStop.await() }, { starts++ })
            }

            guard.onUserRequest()
            releaseStop.complete(Unit)
            restart.join()

            assertEquals(0, starts)
        }
    }

    @Test fun newerUserRequestInvalidatesAllOlderRestartsButNotAFutureOne() = runBlocking {
        withTimeout(5_000) {
            val guard = PowerRequestGeneration()
            val releaseOld = CompletableDeferred<Unit>()
            val starts = mutableListOf<String>()
            val oldRestarts = List(2) { index ->
                launch(start = CoroutineStart.UNDISPATCHED) {
                    guard.restartIfUnchanged({}, { releaseOld.await() }, { starts += "old $index" })
                }
            }

            guard.onUserRequest()
            guard.onUserRequest()
            releaseOld.complete(Unit)
            oldRestarts.forEach { it.join() }
            assertTrue(starts.isEmpty())

            guard.restartIfUnchanged({}, {}, { starts += "current" })
            assertEquals(listOf("current"), starts)
        }
    }

    @Test fun cancellationDuringTheWaitNeverRestarts() = runBlocking {
        withTimeout(5_000) {
            val guard = PowerRequestGeneration()
            var stopped = false
            var started = false
            val restart = launch(start = CoroutineStart.UNDISPATCHED) {
                guard.restartIfUnchanged(
                    stop = { stopped = true },
                    awaitStop = { awaitCancellation() },
                    start = { started = true },
                )
            }

            restart.cancelAndJoin()

            assertTrue(stopped)
            assertTrue(restart.isCancelled)
            assertFalse(started)
        }
    }

    @Test fun cancellationIsCheckedEvenIfTheWaitFinishesNonCancellably() = runBlocking {
        withTimeout(5_000) {
            val guard = PowerRequestGeneration()
            val releaseStop = CompletableDeferred<Unit>()
            var started = false
            val restart = launch(start = CoroutineStart.UNDISPATCHED) {
                guard.restartIfUnchanged(
                    stop = {},
                    awaitStop = { withContext(NonCancellable) { releaseStop.await() } },
                    start = { started = true },
                )
            }

            restart.cancel()
            releaseStop.complete(Unit)
            restart.join()

            assertTrue(restart.isCancelled)
            assertFalse(started)
        }
    }
}
