# OBD-II telemetry broadcast integration (API v1)

Clear GPS can optionally send current OBD-II values to other Android apps through an app-defined broadcast. It is **off by default**: enable **OBD-II → Передача OBD-II в другие приложения** to opt in.

- Action: `org.cleargps.action.OBD2_TELEMETRY`
- API version: `api_version = 1`
- Connection/status: `connected`, `status`
- Sample freshness: `updated_at_epoch_millis`, `speed_sample_elapsed_realtime_nanos`, `rpm_sample_elapsed_realtime_nanos`
- Values when fresh: `vehicle_speed_kmh`, `engine_rpm`, `engine_load_percent`, `throttle_percent`, `manifold_pressure_kpa`, `coolant_temperature_celsius`, `barometric_pressure_kpa`
- Read quality: `speed_issue`, `rpm_issue`. Values may be `NONE`, `NO_VALID_RESPONSE`, `TRANSIENT_ZERO_SUPPRESSED`, `PID_UNSUPPORTED`, or `PID_DISABLED_FOR_SESSION`.

A missing numeric extra means **no current valid value**; do not convert it to zero or reuse an old value. A real zero is sent only after the PID filter accepts it. The broadcast never contains VIN, adapter address, coordinates, or radio identifiers.

## Receiver example

Receiving apps should register a dynamic, exported receiver while they need the telemetry (Android 13+ requires the exported flag for a custom broadcast from another app):

```kotlin
private val obdReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "org.cleargps.action.OBD2_TELEMETRY") return
        val connected = intent.getBooleanExtra("connected", false)
        val status = intent.getStringExtra("status").orEmpty()
        val speedKmh = if (intent.hasExtra("vehicle_speed_kmh")) {
            intent.getIntExtra("vehicle_speed_kmh", 0)
        } else null
        val rpm = if (intent.hasExtra("engine_rpm")) {
            intent.getIntExtra("engine_rpm", 0)
        } else null
        val speedIssue = intent.getStringExtra("speed_issue") ?: "NO_VALID_RESPONSE"
        val rpmIssue = intent.getStringExtra("rpm_issue") ?: "NO_VALID_RESPONSE"
        // Render only present, fresh fields. Clear old values on disconnect/missing data.
    }
}

fun registerObdReceiver(context: Context) {
    ContextCompat.registerReceiver(
        context,
        obdReceiver,
        IntentFilter("org.cleargps.action.OBD2_TELEMETRY"),
        ContextCompat.RECEIVER_EXPORTED
    )
}

fun unregisterObdReceiver(context: Context) {
    context.unregisterReceiver(obdReceiver)
}
```

Unregister the receiver with the same context when it is no longer needed. This is an app-to-app telemetry contract, not a universal API that automatically makes arbitrary apps understand OBD data. Android location consumers use a separate mock-location output path, which requires its own explicit opt-in and Developer options setup.
