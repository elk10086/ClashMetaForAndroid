package com.github.kr328.clash.cloud

import org.junit.Assert.assertEquals
import com.github.kr328.clash.cloud.CloudMembershipWarning.EXPIRING
import com.github.kr328.clash.cloud.CloudMembershipWarning.LOW_TRAFFIC
import com.github.kr328.clash.cloud.CloudMembershipWarning.TRAFFIC_EXHAUSTED
import org.junit.Test

class CloudMembershipWarningTest {
    private val now = 1_800_000_000_000L
    private val threeDays = 3 * 24 * 60 * 60L
    private val gb = 1_024 * 1_024 * 1_024L

    private fun plan(expiresAt: Long = 0, total: Long = 10 * gb, used: Long = 0) = CloudSubscription(
        email = "test@example.com", planId = 1L, plan = "Test", subscribeUrl = "",
        used = used, total = total, expiresAt = expiresAt, balance = 0,
    )

    private fun assertWarnings(subscription: CloudSubscription, vararg expected: CloudMembershipWarning) {
        assertEquals(expected.toList(), subscription.dashboardWarnings(now))
    }

    @Test fun timedPlanWarnsStrictlyBelowThreeDays() {
        val expiry = now / 1_000 + threeDays
        assertWarnings(plan(expiresAt = expiry))
        assertEquals(listOf(EXPIRING), plan(expiresAt = expiry).dashboardWarnings(now + 1))
    }

    @Test fun timedPlanWithMoreThanThreeDaysHasNoWarning() {
        assertWarnings(plan(expiresAt = now / 1_000 + threeDays + 1))
    }

    @Test fun expiredPlanKeepsExistingExpiredCard() {
        assertWarnings(plan(expiresAt = now / 1_000))
        assertWarnings(plan(expiresAt = now / 1_000 - 1, used = 10 * gb))
        assertWarnings(plan(expiresAt = now / 1_000 + 1), EXPIRING)
    }

    @Test fun timedPlanAlsoWarnsStrictlyBelowThreeGigabytes() {
        val expiry = now / 1_000 + 30 * 24 * 60 * 60
        assertWarnings(plan(expiresAt = expiry, used = 7 * gb))
        assertWarnings(plan(expiresAt = expiry, used = 7 * gb + 1), LOW_TRAFFIC)
        assertWarnings(plan(expiresAt = expiry, used = 10 * gb), TRAFFIC_EXHAUSTED)
    }

    @Test fun bothWarningsAreShownWhenBothConditionsMatch() {
        assertWarnings(plan(expiresAt = now / 1_000 + 60, used = 9 * gb), EXPIRING, LOW_TRAFFIC)
        assertWarnings(plan(expiresAt = now / 1_000 + 60, used = 10 * gb), EXPIRING, TRAFFIC_EXHAUSTED)
    }

    @Test fun eitherConditionAloneStillWarns() {
        val expiry = now / 1_000 + threeDays
        assertWarnings(plan(expiresAt = expiry - 1, used = 7 * gb), EXPIRING)
        assertWarnings(plan(expiresAt = expiry, used = 7 * gb + 1), LOW_TRAFFIC)
        assertWarnings(plan(expiresAt = expiry, used = 7 * gb))
    }

    @Test fun untimedPlanWarnsStrictlyBelowThreeGigabytes() {
        assertWarnings(plan(used = 7 * gb))
        assertWarnings(plan(used = 7 * gb + 1), LOW_TRAFFIC)
    }

    @Test fun warningUsesRemainingInsteadOfTotalOrUsedTraffic() {
        assertWarnings(plan(used = gb))
        assertWarnings(plan(total = 2 * gb), LOW_TRAFFIC)
        assertWarnings(plan(total = 100 * gb, used = 98 * gb), LOW_TRAFFIC)
    }

    @Test fun zeroAndOverdrawnTrafficHaveExhaustedMessage() {
        assertWarnings(plan(used = 10 * gb), TRAFFIC_EXHAUSTED)
        assertWarnings(plan(used = 11 * gb), TRAFFIC_EXHAUSTED)
        assertWarnings(plan(total = 0), TRAFFIC_EXHAUSTED)
    }

    @Test fun missingPlanAndInvalidQuotaDoNotProduceMisleadingWarnings() {
        assertWarnings(plan(total = 0).copy(planId = null))
        assertWarnings(plan(expiresAt = now / 1_000 + 60).copy(planId = null))
        assertWarnings(plan(total = -1))
        assertWarnings(plan(total = 2 * gb, used = -1), LOW_TRAFFIC)
    }

    @Test fun invalidQuotaDoesNotSuppressExpiryWarning() {
        assertWarnings(plan(expiresAt = now / 1_000 + 60, total = -1), EXPIRING)
    }

    @Test fun nonPositiveExpiryMatchesExistingUntimedSemantics() {
        assertWarnings(plan(expiresAt = -1, used = 9 * gb), LOW_TRAFFIC)
    }

    @Test fun renewalOrQuotaIncreaseClearsWarning() {
        val expiring = plan(expiresAt = now / 1_000 + 60)
        assertWarnings(expiring, EXPIRING)
        assertWarnings(expiring.copy(expiresAt = now / 1_000 + 30 * 24 * 60 * 60))
        val lowTraffic = plan(used = 9 * gb)
        assertWarnings(lowTraffic, LOW_TRAFFIC)
        assertWarnings(lowTraffic.copy(total = 20 * gb))
    }

    @Test fun resolvingOneConditionPreservesTheOtherWarning() {
        val both = plan(expiresAt = now / 1_000 + 60, used = 9 * gb)
        assertWarnings(both, EXPIRING, LOW_TRAFFIC)
        assertWarnings(both.copy(expiresAt = now / 1_000 + 30 * 24 * 60 * 60), LOW_TRAFFIC)
        assertWarnings(both.copy(total = 20 * gb), EXPIRING)
    }
}
