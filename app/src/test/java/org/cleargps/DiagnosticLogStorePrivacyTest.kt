package org.cleargps

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticLogStorePrivacyTest {
    private lateinit var context: Context
    private lateinit var logFile: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(DiagnosticLogStore.KEY_ENABLED)
            .commit()
        logFile = File(File(context.filesDir, "diagnostics"), "clear-gps.log")
        logFile.parentFile?.mkdirs()
        logFile.delete()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(DiagnosticLogStore.KEY_ENABLED)
            .commit()
        logFile.delete()
    }

    @Test
    fun readingLegacyLogSanitizesAndRewritesSensitiveContent() {
        val raw = "Legacy SSID=Home WiFi, BSSID=aa:bb:cc:dd:ee:ff; " +
            "cell=lte:250:1:123:567890; position=52.123, 40.654\n"
        logFile.writeText(raw, Charsets.UTF_8)

        val visible = DiagnosticLogStore.read(context)
        val persisted = logFile.readText(Charsets.UTF_8)

        assertFalse(visible.contains("Home WiFi"))
        assertFalse(visible.contains("WiFi"))
        assertFalse(visible.contains("aa:bb:cc:dd:ee:ff"))
        assertFalse(visible.contains("lte:250:1:123:567890"))
        assertFalse(visible.contains("52.123"))
        assertFalse(visible.contains("40.654"))
        assertTrue(visible.contains("52.12, 40.65"))
        assertEquals(visible, persisted)
    }

    @Test
    fun loggingNewMessagesSanitizesBeforePersisting() {
        context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(DiagnosticLogStore.KEY_ENABLED, true).commit()
        val message = "SSID=Home WiFi, BSSID=aa:bb:cc:dd:ee:ff; " +
            "cell=lte:250:1:123:567890; at 52.123, 40.654"

        DiagnosticLogStore.log(context, "PRIVACY_TEST", message)

        val persisted = logFile.readText(Charsets.UTF_8)
        assertFalse(persisted.contains("Home WiFi"))
        assertFalse(persisted.contains("WiFi"))
        assertFalse(persisted.contains("aa:bb:cc:dd:ee:ff"))
        assertFalse(persisted.contains("lte:250:1:123:567890"))
        assertFalse(persisted.contains("52.123"))
        assertFalse(persisted.contains("40.654"))
        assertTrue(persisted.contains("52.12, 40.65"))
    }
}
