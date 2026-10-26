package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class Obd2ReconnectPolicyTest {
    @Test
    fun retriesUseBoundedExponentialBackoffWithAMaximumDelay() {
        assertEquals(1_000L, Obd2ReconnectPolicy.delayBeforeAttempt(0))
        assertEquals(2_000L, Obd2ReconnectPolicy.delayBeforeAttempt(1))
        assertEquals(4_000L, Obd2ReconnectPolicy.delayBeforeAttempt(2))
        assertEquals(8_000L, Obd2ReconnectPolicy.delayBeforeAttempt(3))
        assertEquals(15_000L, Obd2ReconnectPolicy.delayBeforeAttempt(4))
        assertEquals(5, Obd2ReconnectPolicy.MAX_ATTEMPTS)
        assertEquals(null, Obd2ReconnectPolicy.delayBeforeAttempt(-1))
        assertEquals(null, Obd2ReconnectPolicy.delayBeforeAttempt(5))
    }

    @Test
    fun transientTransportFailuresCanBeRetried() {
        assertTrue(Obd2ReconnectPolicy.shouldRetry(IOException("link lost")))
        assertTrue(Obd2ReconnectPolicy.shouldRetry(SocketTimeoutException("timeout")))
    }

    @Test
    fun permissionAndConfigurationFailuresAreNotRetried() {
        assertFalse(Obd2ReconnectPolicy.shouldRetry(SecurityException("permission denied")))
        assertFalse(Obd2ReconnectPolicy.shouldRetry(IllegalStateException("Bluetooth is off")))
        assertFalse(Obd2ReconnectPolicy.shouldRetry(IllegalArgumentException("bad address")))
    }
}
