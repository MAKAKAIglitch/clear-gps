package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class Obd2ReconnectPolicyTest {
    @Test
    fun retriesUseExponentialBackoffAndKeepRetryingAtTheCappedDelay() {
        assertEquals(1_000L, Obd2ReconnectPolicy.delayBeforeAttempt(0))
        assertEquals(2_000L, Obd2ReconnectPolicy.delayBeforeAttempt(1))
        assertEquals(4_000L, Obd2ReconnectPolicy.delayBeforeAttempt(2))
        assertEquals(8_000L, Obd2ReconnectPolicy.delayBeforeAttempt(3))
        assertEquals(15_000L, Obd2ReconnectPolicy.delayBeforeAttempt(4))
        assertEquals(30_000L, Obd2ReconnectPolicy.delayBeforeAttempt(5))
        assertEquals(60_000L, Obd2ReconnectPolicy.delayBeforeAttempt(6))
        assertEquals(60_000L, Obd2ReconnectPolicy.delayBeforeAttempt(7))
        assertEquals(60_000L, Obd2ReconnectPolicy.delayBeforeAttempt(100))
        assertEquals(0, Obd2ReconnectPolicy.nextBackoffStage(-1))
        assertEquals(6, Obd2ReconnectPolicy.nextBackoffStage(5))
        assertEquals(6, Obd2ReconnectPolicy.nextBackoffStage(6))
        assertEquals(null, Obd2ReconnectPolicy.delayBeforeAttempt(-1))
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
