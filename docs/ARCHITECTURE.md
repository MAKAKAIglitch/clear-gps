# Clear GPS architecture

Status: living document for the current app and planned module boundaries.

## Current repository

The app is Kotlin/Android with Jetpack Compose, Material 3, MapLibre and local SQLite. Platform-facing code remains in the app module. Existing GNSS detection, location filtering, radio matching, OBD-II and mock publication code remains the runtime implementation.

The new core-detect module is a pure Kotlin/JVM foundation for normalized evidence, trust-state mechanics and a coarse context hint. It has no Android dependency and can be tested on a standard JVM. It is intentionally not wired into runtime yet: replacing the production path requires a separate review of score semantics, source adaptation and publication policy.

## Target boundaries

- core-detect: normalized GNSS/cross-check evidence, risk index, state transitions and reason codes.
- core-fusion: planned position and measurement fusion in a local ENU frame.
- source-gnss, source-radio, source-obd, source-imu and source-baro: independently failing source adapters.
- data: Room migrations, provenance, imports, retention and user-controlled export.
- mock and service: one publication boundary, foreground-service lifecycle and stale-output watchdog.
- ui: screens, explanations, source toggles and localized strings.
- car and vehicle-profiles: optional Android Auto experiment and read-only vehicle-specific profiles.

The planned module names do not imply that every module already exists.

## Data flow

1. A source adapter validates input and records observation time from a monotonic clock.
2. Feature extraction normalizes values and reports availability separately from measurement.
3. TrustEngine evaluates independent evidence. Raw GNSS detection must never consume the app's mock output.
4. Fusion consumes accepted measurements with uncertainty; radio estimates are not silently promoted to ground truth.
5. One publisher applies the selected policy and only publishes fresh, accepted fixes.
6. Diagnostics use stable reason codes and avoid exact coordinates and raw radio IDs by default.

## Invariants

- Missing hardware capability is neutral, not evidence of attack.
- A source failure disables only that source.
- Use elapsed-realtime time for sample ordering and freshness.
- A detector never ingests its own mock output.
- No trusted current fix means publication stops instead of inventing a coordinate.
- The score is an uncalibrated risk index until replay evaluation supports probability claims.
- Network lookup is opt-in and disabled by default.
