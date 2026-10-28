package com.github.kr328.clash.design.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyDelayTest {
    @Test fun normalDelayIsDividedByThreeAndRounded() {
        assertEquals("30 ms", formatProxyDelay(90))
        assertEquals("100 ms", formatProxyDelay(300))
        assertEquals("200 ms", formatProxyDelay(599))
    }

    @Test fun exactlySixHundredStillUsesThree() {
        assertEquals("200 ms", formatProxyDelay(600))
    }

    @Test fun delayAboveSixHundredIsDividedByFiveAndRounded() {
        assertEquals("120 ms", formatProxyDelay(601))
        assertEquals("121 ms", formatProxyDelay(603))
        assertEquals("200 ms", formatProxyDelay(1_000))
    }

    @Test fun untestedDelayStaysBlank() {
        assertEquals("", formatProxyDelay(0))
    }

    @Test fun failedAndOutOfRangeDelaysStayTimeout() {
        assertEquals("Timeout", formatProxyDelay(-1))
        assertEquals("Timeout", formatProxyDelay(Int.MIN_VALUE))
        assertEquals("Timeout", formatProxyDelay(Short.MAX_VALUE.toInt() + 1))
        assertEquals("Timeout", formatProxyDelay(Int.MAX_VALUE))
    }

    @Test fun highestPreviouslyValidDelayIsStillNumeric() {
        assertEquals("6553 ms", formatProxyDelay(Short.MAX_VALUE.toInt()))
    }
}
