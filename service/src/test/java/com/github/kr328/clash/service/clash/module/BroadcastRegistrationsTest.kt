package com.github.kr328.clash.service.clash.module

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BroadcastRegistrationsTest {
    @Test fun successfulRegistrationIsUnregisteredAndItsChannelClosedExactlyOnce() {
        val receivers = BroadcastRegistrations()
        val events = mutableListOf<String>()
        receivers.register(
            register = { events += "register" },
            unregister = { events += "unregister" },
            closeChannel = { events += "close channel" }
        )

        receivers.close()
        receivers.close()

        assertEquals(listOf("register", "unregister", "close channel"), events)
    }

    @Test fun failedRegistrationClosesItsChannelWithoutUnregistering() {
        val receivers = BroadcastRegistrations()
        val error = SecurityException("Receiver permission denied")
        var channelClosed = false
        try {
            receivers.register(
                register = { throw error },
                unregister = { fail("A failed registration must not be unregistered") },
                closeChannel = { channelClosed = true }
            )
            fail("Expected registration failure")
        } catch (e: SecurityException) {
            assertSame(error, e)
        }

        assertTrue(channelClosed)
        receivers.close()
    }

    @Test fun registrationAfterTeardownIsRejectedAndClosesItsChannel() {
        val receivers = BroadcastRegistrations()
        receivers.close()
        var channelClosed = false
        try {
            receivers.register(
                register = { fail("Must not register after teardown") },
                unregister = { fail("Must not unregister an unregistered receiver") },
                closeChannel = { channelClosed = true }
            )
            fail("Expected closed registry")
        } catch (_: IllegalStateException) {
            assertTrue(channelClosed)
        }
    }

    @Test fun teardownFailureReleasesOtherReceiversAndPreservesAllErrors() {
        val receivers = BroadcastRegistrations()
        val firstError = IllegalArgumentException("Unexpected missing first receiver")
        val secondError = IllegalArgumentException("Unexpected missing second receiver")
        var channelsClosed = 0
        var lastUnregistered = false
        listOf(firstError, secondError).forEach { error ->
            receivers.register({}, { throw error }, { channelsClosed++ })
        }
        receivers.register({}, { lastUnregistered = true }, { channelsClosed++ })

        try {
            receivers.close()
            fail("Unexpected lifecycle failures must not be swallowed")
        } catch (e: IllegalArgumentException) {
            assertSame(firstError, e)
            assertEquals(listOf(secondError), e.suppressed.toList())
        }

        assertEquals(3, channelsClosed)
        assertTrue(lastUnregistered)
        receivers.close()
        assertEquals(3, channelsClosed)
    }

    @Test fun reentrantCloseDoesNotUnregisterTwice() {
        val receivers = BroadcastRegistrations()
        var unregisters = 0
        receivers.register({}, { unregisters++; receivers.close() }, {})

        receivers.close()

        assertEquals(1, unregisters)
    }

    @Test fun cancellationReleasesReceiversWithoutReplacingCancellation() = runBlocking {
        val receivers = BroadcastRegistrations()
        val registered = CompletableDeferred<Unit>()
        var channelClosed = false
        var unregistered = false
        val module = launch {
            try {
                receivers.register({}, { unregistered = true }, { channelClosed = true })
                registered.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { receivers.close() }
            }
        }
        registered.await()
        assertFalse(unregistered)

        module.cancelAndJoin()

        assertTrue(module.isCancelled)
        assertTrue(unregistered)
        assertTrue(channelClosed)
    }
}
