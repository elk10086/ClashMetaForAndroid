package com.github.kr328.clash.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudMembershipRefreshPolicyTest {
    private val now = 1_800_000_000_000L
    private val day = CloudMembershipRefreshPolicy.INTERVAL
    private val retry = CloudMembershipRefreshPolicy.RETRY

    @Test fun missingSnapshotIsDueImmediately() {
        assertTrue(CloudMembershipRefreshPolicy.isDue(now, 0, 0))
    }

    @Test fun regularPageCacheExpiryDoesNotTriggerDailyRequest() {
        assertFalse(CloudMembershipRefreshPolicy.isDue(now, now - 60_001, 0))
    }

    @Test fun waitsForFullTwentyFourHours() {
        assertFalse(CloudMembershipRefreshPolicy.isDue(now, now - day + 1, 0))
        assertTrue(CloudMembershipRefreshPolicy.isDue(now, now - day, 0))
    }

    @Test fun returningAfterSeveralDaysCatchesUpImmediately() {
        assertTrue(CloudMembershipRefreshPolicy.isDue(now, now - 3 * day, 0))
    }

    @Test fun failureWaitsFifteenMinutesInsteadOfEveryPoll() {
        for (elapsed in 0 until retry step 30_000L) {
            assertFalse(CloudMembershipRefreshPolicy.isDue(now + elapsed, now - day, now + retry))
        }
        assertTrue(CloudMembershipRefreshPolicy.isDue(now + retry, now - day, now + retry))
    }

    @Test fun failedInitialLoadAlsoHonorsRetryDeadline() {
        assertFalse(CloudMembershipRefreshPolicy.isDue(now, 0, now + retry))
    }

    @Test fun anySuccessfulPageRefreshRestartsDailyDeadline() {
        assertFalse(CloudMembershipRefreshPolicy.isDue(now + retry, now, 0))
        assertFalse(CloudMembershipRefreshPolicy.isDue(now + day - 1, now, 0))
        assertTrue(CloudMembershipRefreshPolicy.isDue(now + day, now, 0))
    }

    @Test fun clockRollbackDoesNotBlockRefreshIndefinitely() {
        assertTrue(CloudMembershipRefreshPolicy.isDue(now, now + day, now + day + retry))
        assertFalse(CloudMembershipRefreshPolicy.isDue(now, now + day, now + retry))
    }
}
