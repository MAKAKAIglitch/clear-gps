package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Obd2PidCircuitBreakerTest {
    @Test
    fun opensAfterThreeConsecutiveFailuresAndRemainsOpenForSession() {
        val breaker = Obd2PidCircuitBreaker()

        assertFalse(breaker.recordFailure(0x0D))
        assertFalse(breaker.isDisabled(0x0D))
        assertFalse(breaker.recordFailure(0x0D))
        assertEquals(2, breaker.consecutiveFailureCount(0x0D))
        assertTrue(breaker.recordFailure(0x0D))
        assertTrue(breaker.isDisabled(0x0D))
        assertTrue(breaker.recordFailure(0x0D))
    }

    @Test
    fun successfulReplyResetsOnlyTheConsecutiveFailureStreak() {
        val breaker = Obd2PidCircuitBreaker()

        breaker.recordFailure(0x0D)
        breaker.recordFailure(0x0D)
        breaker.recordSuccess(0x0D)
        assertEquals(0, breaker.consecutiveFailureCount(0x0D))
        assertFalse(breaker.isDisabled(0x0D))

        assertFalse(breaker.recordFailure(0x0D))
        assertEquals(1, breaker.consecutiveFailureCount(0x0D))
    }

    @Test
    fun pidFailuresAreIndependent() {
        val breaker = Obd2PidCircuitBreaker()

        breaker.recordFailure(0x0D)
        breaker.recordFailure(0x0D)
        assertFalse(breaker.isDisabled(0x0D))
        assertEquals(0, breaker.consecutiveFailureCount(0x0C))
        assertFalse(breaker.recordFailure(0x0C))
        assertFalse(breaker.isDisabled(0x0C))
    }

    @Test
    fun resetStartsAFreshConnectionSession() {
        val breaker = Obd2PidCircuitBreaker()

        repeat(3) { breaker.recordFailure(0x0D) }
        assertTrue(breaker.isDisabled(0x0D))

        breaker.reset()
        assertFalse(breaker.isDisabled(0x0D))
        assertEquals(0, breaker.consecutiveFailureCount(0x0D))
        assertFalse(breaker.recordFailure(0x0D))
    }

    @Test
    fun successfulReplyCannotReopenAlreadyDisabledPidBeforeReset() {
        val breaker = Obd2PidCircuitBreaker()
        repeat(3) { breaker.recordFailure(0x0D) }

        breaker.recordSuccess(0x0D)
        assertTrue(breaker.isDisabled(0x0D))
        assertTrue(breaker.recordFailure(0x0D))
    }
}
