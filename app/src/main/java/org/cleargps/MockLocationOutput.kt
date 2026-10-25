package org.cleargps

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock

/** Optional adapter; requires explicit user selection as the Android mock-location app. */
class MockLocationOutput(context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var registered = false

    fun publish(source: Location): Boolean = try {
        if (!registered) {
            manager.addTestProvider(LocationManager.GPS_PROVIDER,
                false, false, false, false, true, true, true,
                Criteria.POWER_HIGH, Criteria.ACCURACY_FINE)
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            registered = true
        }
        val out = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = source.latitude
            longitude = source.longitude
            accuracy = if (source.hasAccuracy()) source.accuracy.coerceAtLeast(1f) else 100f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (source.hasAltitude()) altitude = source.altitude
            if (source.hasSpeed()) speed = source.speed
            if (source.hasBearing()) bearing = source.bearing
        }
        manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, out)
        true
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    fun disable() {
        try {
            if (registered) manager.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        } catch (_: RuntimeException) {
        } finally {
            registered = false
        }
    }
}
