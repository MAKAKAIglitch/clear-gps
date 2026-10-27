package org.cleargps

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
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
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RadioGeolocationRepositoryBeaconDbConsentTest {
    private lateinit var baseContext: Context

    @Before
    fun setUp() {
        baseContext = RuntimeEnvironment.getApplication()
        baseContext.deleteDatabase(DB_NAME)
        baseContext.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ClearGpsPreferences.KEY_BEACONDB_LOOKUP_ENABLED, false)
            .commit()
    }

    @After
    fun tearDown() {
        baseContext.deleteDatabase(DB_NAME)
        baseContext.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(ClearGpsPreferences.KEY_BEACONDB_LOOKUP_ENABLED)
            .commit()
    }

    @Test
    fun disabledBeaconDbReturnsBeforeCheckingAndroidLocationPermission() = runBlocking {
        val guardedContext = PermissionGuardContext(
            base = baseContext,
            permissionResult = PackageManager.PERMISSION_DENIED,
            failIfChecked = true
        )

        val result = RadioGeolocationRepository(guardedContext).lookupBeaconDbCellsOnly()

        assertFalse(result.success)
        assertTrue(result.cells.isEmpty())
        assertEquals(0, guardedContext.permissionChecks)
        assertTrue(result.message.contains("выключены", ignoreCase = true))
        assertTrue(result.message.contains("сетевого обращения не было", ignoreCase = true))
    }

    @Test
    fun enabledBeaconDbWithoutFineLocationPermissionReturnsBeforeLookup() = runBlocking {
        baseContext.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ClearGpsPreferences.KEY_BEACONDB_LOOKUP_ENABLED, true)
            .commit()
        val guardedContext = PermissionGuardContext(
            base = baseContext,
            permissionResult = PackageManager.PERMISSION_DENIED
        )

        val result = RadioGeolocationRepository(guardedContext).lookupBeaconDbCellsOnly()

        assertFalse(result.success)
        assertTrue(result.cells.isEmpty())
        assertEquals(1, guardedContext.permissionChecks)
        assertTrue(result.message.contains("разрешение", ignoreCase = true))
        assertTrue(result.message.contains("не отправлялся", ignoreCase = true))
    }

    private class PermissionGuardContext(
        base: Context,
        private val permissionResult: Int,
        private val failIfChecked: Boolean = false
    ) : ContextWrapper(base) {
        var permissionChecks: Int = 0
            private set

        override fun getApplicationContext(): Context = this

        override fun checkSelfPermission(permission: String): Int {
            permissionChecks += 1
            if (failIfChecked) {
                throw AssertionError("BeaconDB opt-out must return before checking Android permissions.")
            }
            return permissionResult
        }
    }

    companion object {
        private const val DB_NAME = "clear_gps_radio.sqlite"
    }
}
