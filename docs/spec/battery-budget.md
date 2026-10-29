# Battery budget

Status: draft. Numeric targets require measurements on a reference device.

## Profiles
Eco, Normal and Max may vary the user fix interval, raw GNSS analysis, radio scanning, OBD polling and IMU sampling. A configured 1–30 second fix interval does not promise that Android permits Wi-Fi/BLE scans at that cadence.

Eco reduces optional work; Normal balances responsiveness and energy; Max samples more frequently within platform limits. At standstill, optional scans/polling should slow down. Every source requires timeout and backoff. Partial wake locks are limited to critical time-bounded sections.

## Measurement
Record phone model, Android version, screen/battery state, app build, vehicle adapter and temperature if available. Measure idle and each active source profile over comparable routes, repeat runs and record charge delta, duration, missing fixes, position error and detection delay. Report battery percentage points per hour and observed variance. Test GNSS, raw analysis, radio, OBD, fusion and map activity separately.

Reference device and Eco/Normal/Max battery targets remain TBD. Do not invent targets. Re-run the baseline when sensor cadence, service lifecycle, map rendering or fusion changes.
