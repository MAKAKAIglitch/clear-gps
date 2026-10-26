package org.cleargps

import org.cleargps.core.SavedRiskZone
import org.cleargps.core.ZonePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsZoneBackupCodecTest {
    private fun snapshot() = SettingsZoneBackupSnapshot(
        positionMode = "auto",
        mapTheme = "dark",
        mapSource = "offline",
        scanIntervalSeconds = 15,
        adaptiveScan = true,
        useWifi = true,
        useCells = false,
        useBle = true,
        zones = listOf(
            SavedRiskZone(
                id = "test-zone-1",
                name = "Проверочная зона | 1",
                points = listOf(
                    ZonePoint(55.75, 37.61),
                    ZonePoint(55.76, 37.62),
                    ZonePoint(55.74, 37.63)
                ),
                enabled = false,
                createdAt = 1_700_000_000_000L
            )
        )
    )

    @Test fun settingsAndZonesRoundTrip() {
        val original = snapshot()
        assertEquals(original, SettingsZoneBackupCodec.decode(SettingsZoneBackupCodec.encode(original)))
    }

    @Test fun rejectsUnsupportedVersionAndMissingFooter() {
        val encoded = SettingsZoneBackupCodec.encode(snapshot())
        assertNotNull(runCatching { SettingsZoneBackupCodec.decode(encoded.replaceFirst(SettingsZoneBackupCodec.HEADER, "CLEAR_GPS_SETTINGS_ZONES|99")) }.exceptionOrNull())
        assertNotNull(runCatching { SettingsZoneBackupCodec.decode(encoded.substringBefore("END|")) }.exceptionOrNull())
    }

    @Test fun rejectsInvalidCoordinatesAndOversizedFiles() {
        val encoded = SettingsZoneBackupCodec.encode(snapshot())
        val damaged = encoded.replace("55.75|37.61", "95.75|37.61")
        assertNotNull(runCatching { SettingsZoneBackupCodec.decode(damaged) }.exceptionOrNull())
        assertNotNull(runCatching { SettingsZoneBackupCodec.decode("x".repeat(SettingsZoneBackupCodec.MAX_BYTES + 1)) }.exceptionOrNull())
        assertTrue(SettingsZoneBackupCodec.MAX_ZONES == 100)
    }
}
