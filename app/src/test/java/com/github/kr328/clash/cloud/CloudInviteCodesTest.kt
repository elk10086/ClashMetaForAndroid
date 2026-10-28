package com.github.kr328.clash.cloud

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudInviteCodesTest {
    private fun empty() = JSONObject("""{"data":{"codes":[],"stat":[1,2,3]}}""")
    private fun existing() = JSONObject("""{"data":{"codes":[{"code":"INVITE01","created_at":1}]}}""")

    @Test fun existingCodeNeverTriggersCreation() = runBlocking {
        var reads = 0
        val result = CloudInviteCodes.ensure(
            fetch = { reads++; existing() },
            create = { fail("Existing code must be reused") },
            isCurrentSession = { true },
        )
        assertEquals(1, reads)
        assertEquals("INVITE01", result.code)
        assertNull(result.generationError)
    }

    @Test fun firstVisitCreatesExactlyOneCodeAndReadsItBack() = runBlocking {
        var reads = 0
        var creates = 0
        val result = CloudInviteCodes.ensure(
            fetch = { reads++; if (creates == 0) empty() else existing() },
            create = { creates++ },
            isCurrentSession = { true },
        )
        assertEquals(2, reads)
        assertEquals(1, creates)
        assertEquals("INVITE01", result.code)
        assertNull(result.generationError)
    }

    @Test fun concurrentVisitsReuseTheCodeCreatedByFirstVisit() = runBlocking {
        var creates = 0
        val results = List(5) {
            async {
                CloudInviteCodes.ensure(
                    fetch = { if (creates == 0) empty() else existing() },
                    create = { yield(); creates++ },
                    isCurrentSession = { true },
                )
            }
        }.awaitAll()
        assertEquals(1, creates)
        assertTrue(results.all { it.code == "INVITE01" && it.generationError == null })
    }

    @Test fun creationTimeoutReadsBackInsteadOfBlindlyCreatingAgain() = runBlocking {
        var creates = 0
        val result = CloudInviteCodes.ensure(
            fetch = { if (creates == 0) empty() else existing() },
            create = { creates++; throw IOException("timeout") },
            isCurrentSession = { true },
        )
        assertEquals(1, creates)
        assertEquals("INVITE01", result.code)
        assertNull(result.generationError)
    }

    @Test fun failedCreationKeepsSummaryAndOffersRetry() = runBlocking {
        val original = empty()
        var creates = 0
        val result = CloudInviteCodes.ensure(
            fetch = { original },
            create = { creates++; throw IOException("offline") },
            isCurrentSession = { true },
        )
        assertSame(original, result.json)
        assertEquals("", result.code)
        assertEquals("offline", result.generationError)
        assertEquals(1, creates)
    }

    @Test fun failedReadbackDoesNotRepeatCreation() = runBlocking {
        var reads = 0
        var creates = 0
        val original = empty()
        val result = CloudInviteCodes.ensure(
            fetch = { if (++reads == 1) original else throw IOException("read failed") },
            create = { creates++ },
            isCurrentSession = { true },
        )
        assertSame(original, result.json)
        assertEquals("read failed", result.generationError)
        assertEquals(1, creates)
    }

    @Test fun retryChecksServerBeforeCreatingAnotherCode() = runBlocking {
        var creates = 0
        var visibleOnServer = false
        suspend fun load() = CloudInviteCodes.ensure(
            fetch = { if (visibleOnServer) existing() else empty() },
            create = { creates++ },
            isCurrentSession = { true },
        )
        assertNotNull(load().generationError)
        visibleOnServer = true
        assertEquals("INVITE01", load().code)
        assertEquals(1, creates)
    }

    @Test fun initialReadFailureNeverCreatesCode() = runBlocking {
        val failure = runCatching {
            CloudInviteCodes.ensure(
                fetch = { throw IOException("offline") },
                create = { fail("Cannot create without reading history") },
                isCurrentSession = { true },
            )
        }.exceptionOrNull()
        assertTrue(failure is IOException)
    }

    @Test fun malformedSummaryNeverTriggersCreation() = runBlocking {
        for (raw in listOf("{}", """{"data":null}""", """{"data":{"stat":[]}}""")) {
            val result = CloudInviteCodes.ensure(
                fetch = { JSONObject(raw) },
                create = { fail("Missing history is not an empty history") },
                isCurrentSession = { true },
            )
            assertEquals("", result.code)
            assertNotNull(result.generationError)
        }
    }

    @Test fun accountChangeDuringReadStopsCreation() = runBlocking {
        var sameSession = true
        val failure = runCatching {
            CloudInviteCodes.ensure(
                fetch = { sameSession = false; empty() },
                create = { fail("Session changed") },
                isCurrentSession = { sameSession },
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test fun cancellationPropagatesWithoutReadbackOrRetry() = runBlocking {
        var reads = 0
        val failure = runCatching {
            CloudInviteCodes.ensure(
                fetch = { reads++; empty() },
                create = { throw CancellationException("page closed") },
                isCurrentSession = { true },
            )
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(1, reads)
    }

    @Test fun parsesDirectAndNewestCodesWithoutTreatingJsonNullAsCode() {
        assertEquals("DIRECT", CloudInviteCodes.findCode(JSONObject(
            """{"data":{"invite_code":" DIRECT ","codes":[{"code":"OLD"}]}}""")))
        assertEquals("NEW", CloudInviteCodes.findCode(JSONObject(
            """{"data":{"invite_code":null,"codes":[{"code":"OLD","created_at":1},{"code":"NEW","updated_at":3},{"code":null,"updated_at":4},{"code":" ","updated_at":5}]}}""")))
        assertEquals("", CloudInviteCodes.findCode(JSONObject("""{"data":{"invite_code":null}}""")))
    }

    @Test fun legacyCodeArrayIsUsedEvenWhenCodesArrayIsEmpty() {
        assertEquals("LEGACY", CloudInviteCodes.findCode(JSONObject(
            """{"data":{"codes":[],"code":[{"code":"LEGACY"}]}}""")))
    }
}
