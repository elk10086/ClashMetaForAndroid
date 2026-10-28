package com.github.kr328.clash.design.util

import org.junit.Assert.*
import org.junit.Test

class PowerRequestGateTest {
    @Test fun rapidClicksOnlyEnqueueOneRequest() {
        val gate = PowerRequestGate()
        assertEquals(1, (1..100).count { gate.acquire() })
        assertTrue(gate.pending)
    }

    @Test fun completionOrFailureAllowsTheNextToggle() {
        val gate = PowerRequestGate()
        assertTrue(gate.acquire())
        gate.release()
        assertTrue(gate.acquire())
        assertFalse(gate.acquire())
    }

    @Test fun repeatedResetDoesNotReserveARequest() {
        val gate = PowerRequestGate()
        gate.release()
        gate.release()
        assertFalse(gate.pending)
        assertTrue(gate.acquire())
    }
}
