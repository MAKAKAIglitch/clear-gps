package org.cleargps

import android.location.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocationAltitudeContractTest {
    @Test
    fun copiesEllipsoidAndMeanSeaLevelAltitudeAsSeparateMeasurements() {
        val source = Location("gps").apply {
            altitude = 123.5
            verticalAccuracyMeters = 7.0f
            mslAltitudeMeters = 117.25
            mslAltitudeAccuracyMeters = 4.5f
        }
        val target = Location("gps")

        copyAltitudeMetadata(source, target)

        assertTrue(target.hasAltitude())
        assertEquals(123.5, target.altitude, 0.0)
        assertTrue(target.hasVerticalAccuracy())
        assertEquals(7.0f, target.verticalAccuracyMeters, 0.0f)
        assertTrue(target.hasMslAltitude())
        assertEquals(117.25, target.mslAltitudeMeters, 0.0)
        assertTrue(target.hasMslAltitudeAccuracy())
        assertEquals(4.5f, target.mslAltitudeAccuracyMeters, 0.0f)
    }

    @Test
    fun doesNotInventMslAltitudeFromEllipsoidAltitude() {
        val source = Location("gps").apply { altitude = 120.0 }
        val target = Location("gps")

        copyAltitudeMetadata(source, target)

        assertTrue(target.hasAltitude())
        assertEquals(120.0, target.altitude, 0.0)
        assertFalse(target.hasMslAltitude())
        assertFalse(target.hasMslAltitudeAccuracy())
    }

    @Test
    fun absentAltitudeMetadataRemainsAbsent() {
        val source = Location("gps")
        val target = Location("gps")

        copyAltitudeMetadata(source, target)

        assertFalse(target.hasAltitude())
        assertFalse(target.hasVerticalAccuracy())
        assertFalse(target.hasMslAltitude())
        assertFalse(target.hasMslAltitudeAccuracy())
    }
}
