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

    @Test fun v2BackupRoundTripsEcoProfile() {
        val original = snapshot().copy(batteryProfile = BatteryProfile.ECO.key)
        assertEquals(original, SettingsZoneBackupCodec.decode(SettingsZoneBackupCodec.encode(original)))
    }

    @Test fun v1BackupRemainsReadableAndDefaultsToNormalProfile() {
        val original = snapshot()
        val v1 = SettingsZoneBackupCodec.encode(original)
            .lineSequence()
            .filterNot { it.startsWith("S|batteryProfile|") }
            .joinToString("\n", postfix = "\n")
            .replaceFirst(SettingsZoneBackupCodec.HEADER, SettingsZoneBackupCodec.LEGACY_HEADER)

        assertEquals(original.copy(batteryProfile = BatteryProfile.NORMAL.key), SettingsZoneBackupCodec.decode(v1))
    }

    @Test fun settingsBackupRejectsUnknownBatteryProfile() {
        val invalid = snapshot().copy(batteryProfile = "turbo")
        assertNotNull(runCatching { SettingsZoneBackupCodec.encode(invalid) }.exceptionOrNull())
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
