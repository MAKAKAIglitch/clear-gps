# Architecture

This document describes the **current implementation** on `dev`. It is intentionally different from a promise that every item in the roadmap is finished. The target contracts and open decisions live in [`docs/spec/`](docs/spec/).

## Gradle modules

The repository currently builds three Gradle modules:

- **`:app`** — Android application, Compose Material 3 UI, MapLibre map, Android services and permissions, local radio database, radio-position estimation, OBD-II transports, mock-location publication, diagnostics, settings and export/import flows.
- **`:core-detect`** — plain Kotlin/JVM code for GNSS anomaly detection and related data models, baseline/environment diagnostics, risk feedback and replay parsing/encoding. It uses JUnit and has no Android dependency declared in its module build.
- **`:core-fusion`** — plain Kotlin/JVM foundation for a 2D local-frame constant-velocity Kalman filter that accepts timestamped GNSS/radio position measurements, rejects invalid/stale/outlier inputs, and stops returning estimates after its short prediction horizon. It has unit tests and no Android dependency.

`:core-fusion` is wired into `LocationMonitorService` as a conservative output smoother for physical GPS fixes only after `LocationFilterEngine` returns `NORMAL`, source `GPS`, and no active risk zone. It rejects invalid, reordered and large statistical-outlier inputs, and its output is used only inside the short configured prediction horizon. Local radio estimates now retain the oldest monotonic timestamp available from their matched input observations; the helper does not infer it from wall-clock time, rejects unknown/future evidence, and applies a conservative uncertainty floor. This is groundwork only: the live service still does **not** combine local-radio estimates with GNSS in one fusion stream, because out-of-sequence evidence must be handled and validated explicitly. A NETWORK-provider callback also cannot refresh a cached raw GPS candidate: it must reuse a fresh fusion estimate, otherwise the GPS candidate is withheld. Raw IMU/OBD integration, out-of-sequence radio updates and field calibration are still open. Fusion does not affect TrustEngine risk. Process noise remains a motion model, not a replacement for measured acceleration. Do not infer a completed EKF/IMU/OBD/DEM pipeline from supporting classes or specifications.

## Runtime responsibilities

### Android application (`:app`)

- `ClearGpsApplication.kt` installs the process-level uncaught-exception handler and enables log-only Android StrictMode policies only in debuggable builds; `CrashLoopGuard.kt` stores a rolling crash window and blocks the online BeaconDB source after three uncaught crashes in ten minutes.
- LeakCanary 2.14 is scoped to the app's `debugImplementation`; it watches retained Android components while debugging and is not part of the release runtime classpath.
- `ClearGpsApp.kt` wires together the UI and application actions.
- `DeviceCapabilitiesPanel.kt` reports declared Android/GPS/Wi-Fi RTT and sensor capabilities, plus only the fresh GNSS/AGC/frequency evidence already present in the in-memory diagnostics store. Opening it does not request location, run scans or start the service.
- `LocationMonitorService.kt` owns the location-monitoring foreground service.
- `AndroidImuMotionReader.kt` samples `TYPE_LINEAR_ACCELERATION` and `TYPE_ROTATION_VECTOR` at 10 Hz only while monitoring is active. It retains just the latest horizontal east/north acceleration sample in process memory for up to 500 ms; this first step is diagnostic-only and does not feed position fusion or TrustEngine.
- LocationMonitorBootReceiver restores previously enabled monitoring only after BOOT_COMPLETED and only with ACCESS_BACKGROUND_LOCATION; otherwise it clears stale mock output and saves a status explaining that manual restart is required.
- `ServiceRecoveryWatchdog.kt`, `ServiceRecoveryWorker.kt` and `ServiceRecoveryPolicy.kt` add a 30-second monotonic service heartbeat, a WorkManager watchdog with a 15-minute minimum interval, and a delayed task-removal/service-destruction check. Recovery requires previously enabled monitoring and location/background-location permissions; Android can still reject a background FGS start, in which case the status asks the user to open the app.
- `MockLocationOutput.kt` handles publication through Android mock providers. The user must select Clear GPS in Developer options; the app cannot silently grant itself mock-location authority.
- `RadioGeolocationRepository.kt`, `RadioFixReliability.kt` and `LocationQuality.kt` implement/support local radio estimates and the quality checks around them.
- The `Obd2*` classes implement the current read-only OBD-II foundation, transports, polling and vehicle metrics. The full planned make-specific CAN profiles and all sensor-fusion features are not implied to be complete.
- `DiagnosticLogStore.kt`, `GnssReplayRecorder.kt`, `RiskFeedbackStore.kt` and `SettingsZoneBackupCodec.kt` own their corresponding local diagnostic or export data paths.

### Detection core (`:core-detect`)

The JVM module contains GNSS signal/data models and anomaly logic, baseline/environment helpers, and replay codecs. Its tests cover deterministic logic without requiring an Android phone. Device-dependent availability (for example AGC or L5/E5) must be represented as unavailable/neutral rather than fabricated.

## Local radio database and provenance

`LocalRadioDatabase` currently extends Android `SQLiteOpenHelper`; the schema is version **9**, uses WAL and enables foreign-key enforcement. This is the implementation today, not Room yet. Core tables separate:

- `radio_sources` — local or imported source identity and trust settings;
- `radio_points` — a normalized radio identifier and its summary/selected position;
- `radio_observations` — timestamped RSSI/frequency observations with source provenance;
- `scan_history` — bounded scan history;
- `radio_source_segment_settings` — per-source/day/type enablement and trust weights;
- short-lived deletion-undo snapshot tables.

Imports are merged into the existing local database, not used to replace it. The implemented file formats include WiGLE CSV (plain/gzip), WiGLE-shaped SQLite (`network/location`) and a full Clear GPS SQLite backup. A WiGLE-shaped export cannot preserve every Clear GPS-only field: use the full backup to preserve application history and metadata. Settings/zones use a separate `.cgsettings` format.

SSID exclusion is applied on supported recording and matching paths. The radio-position estimate is not meant to become its own ground-truth training coordinate; provenance and quality remain relevant when positions conflict.

## Service recovery and background limits

The foreground monitor updates a monotonic heartbeat while monitoring is enabled. WorkManager checks it periodically (minimum interval 15 minutes), and a one-time check is queued after `onTaskRemoved` or a service-destruction callback. The one-time check waits 45 seconds and recovers only if the service did not write a newer heartbeat. The worker never starts monitoring when the persisted user-enabled flag is false; it requires foreground and background location permissions and clears stale position fields before requesting recovery. Android/OEM restrictions may still block a background start, so this is best-effort rather than a guarantee of uninterrupted location tracking. Settings now has an explicit, user-triggered request to exempt long-running location monitoring from battery optimization, with a fallback to system battery settings if the direct screen is missing. No permanent wake lock is acquired, and the exemption request can be declined. Physical-device/Doze testing remains required.

## Battery cadence profile

Settings expose Eco/Normal/Max for scheduled location updates and repeating radio searches. Eco doubles the movement-adjusted interval (up to 60 seconds), Normal preserves it, and Max halves it (minimum one second). The setting is included in settings-zone backup v2; v1 backups decode as Normal. The profile does not throttle raw GNSS callback processing, which remains available to the detector. Battery use in percent/hour and Doze behavior are not yet measured on reference hardware.

## Position, trust and publication

GNSS diagnostics/detection, radio matching and the Android publication layer are distinct responsibilities, although some orchestration currently remains inside the application module. The target contracts specify that the detector must consume raw GNSS callbacks rather than the application's own mock `Location`, and that an unavailable/failed source should not invalidate all other sources.

When no position meets the publication policy, the intended safe behavior is to stop publishing a stale fix rather than label it fresh. This contract and the trust-state semantics are described in [`position-contract.md`](docs/spec/position-contract.md) and [`trust-model.md`](docs/spec/trust-model.md); device-level publication still requires the manual validation checklist.

## Barometric altitude estimator

- `BarometricAltitudeEstimator.kt` converts pressure to an approximate altitude only after a trusted height and pressure are supplied together as a calibration. The caller must provide pascals, effective mean-column temperature in kelvin, timestamp and uncertainties.
- The estimate inherits the reference's explicit vertical datum and carries propagated sensor uncertainty plus a configured atmosphere-model allowance. Calibration expires after a caller-selected monotonic interval to avoid treating weather-driven pressure drift as permanent height.
- This is not yet connected to Android's pressure-sensor listener or TrustEngine. It does not calibrate itself from an untrusted GNSS fix, and it does not turn raw pressure directly into spoofing evidence.

## Altitude consistency policy

- `AltitudeConsistencyPolicy.kt` compares two fresh altitude observations only when both use the same explicitly supported vertical datum and both provide source-normalized uncertainty estimates. The tolerance is the larger of a caller-selected floor and a configured multiple of the combined uncertainty.
- `SustainedAltitudeDiscrepancyTracker` requires repeated disagreement without long gaps before reporting a sustained discrepancy; consistent, unavailable, stale, invalid or out-of-order evidence resets the window.
- This is a typed decision gate, not a calibrated spoofing signal. It does not add to TrustEngine risk, start sensors, calibrate raw pressure or connect the SRTM store to live location processing. Unspecified mean-sea-level heights are deliberately not treated as EGM96.

## Offline elevation data

- `SrtmHgtElevationStore.kt` reads locally supplied SRTM HGT tiles (1201 × 1201 or 3601 × 3601, signed big-endian samples) and bilinearly interpolates the requested coordinate. Data voids, missing tiles and malformed files return no estimate instead of a zero or guessed height.
- Each result explicitly identifies the EGM96 geoid vertical datum. It is not interchangeable with GNSS ellipsoid height; no offset is guessed.
- `SrtmHgtTilePackStore.kt` imports user-selected ZIP archives into the app-private `filesDir/dem/srtm` directory. Only validated tile basenames are written; each archive is staged first and is bounded by entry, tile and decompressed-size limits.
- After a normal, trusted physical GPS fix, the service may sample the local DEM no more than once every 15 seconds on an IO dispatcher. The diagnostics panel reports the sampled elevation/datum or an unavailable result. Missing tiles and voids remain unavailable.
- The diagnostic live store keeps only elevation, vertical datum and monotonic sample time in memory; it does not retain the query coordinate or write data to disk/logs. Data expires after two minutes and is cleared when monitoring stops or the position mode changes.
- This is **diagnostics only**. DEM is not directly compared with GNSS ellipsoid altitude or raw barometric pressure, and it does not yet feed the live TrustEngine/fusion. Copernicus GeoTIFF, automatic downloads, datum conversion, calibrated vertical uncertainty and real-device validation remain separate work.
## Network and privacy boundaries

- Radio observations and the local database stay in app-private storage by default.
- BeaconDB lookup is disabled by default. If the user enables it and starts a lookup, the current request is **cell-only** (supported cell identifiers and signal strength), not Wi-Fi/BSSID, BLE or GPS coordinates. The remote service also sees the device IP address. No contribution/upload endpoint is used, and the result is not automatically inserted into the local radio database.
- The default map style is remote; a map provider can observe the IP address and requested map area. Radio identifiers are not attached to map requests.
- Diagnostic logs redact recognized labelled identifiers and round explicitly recognized coordinate values. Arbitrary unlabelled free text cannot be guaranteed to be sanitized.
- A minimal `CRASH` entry is forced to the app-private diagnostics log even when verbose logging is off. It contains the exception class, up to eight class/method/line frames, recent crash count and safe-mode flag, but not the exception message. Three uncaught crashes within ten minutes disable BeaconDB lookup and mock output for ten minutes and reset its opt-in preference to off.
- Replay v2 stores relative time/track coordinates and opaque per-recording radio IDs. It can include available IMU samples and (only in Auto mode while recording) anonymized radio snapshots. These samples do **not** currently contribute to the live risk score. The replay remains a possible source of movement-context information, so it is kept private and exported only by user action.
- A full SQLite backup can contain exact radio IDs, coordinates and observation history. It is a deliberate user-selected export, not a sanitized diagnostic log.

See [`docs/spec/privacy.md`](docs/spec/privacy.md) and [`THREAT_MODEL.md`](THREAT_MODEL.md) for the privacy and threat boundaries.

## Build and test flow

The Android CI uses JDK 17 and the checked-in Gradle Wrapper:

```sh
./gradlew --no-daemon --stacktrace :core-detect:test testDebugUnitTest assembleDebug
```

On Windows, use `gradlew.bat` or the checked-in `windows-build/build-apk.ps1`. CI also validates PowerShell syntax, verifies the Gradle Wrapper JAR checksum, and smoke-tests Gradle argument forwarding on Windows. Automated tests are necessary but do not replace real-device checks listed in [`docs/testing/device-validation.md`](docs/testing/device-validation.md).


## Android barometer capture

- `AndroidBarometerSensorReader.kt` subscribes to `Sensor.TYPE_PRESSURE` only while the location monitor is active, using a one-second sampling-period hint. Samples use the sensor event's monotonic timestamp and are converted from hPa to Pa.
- The live store is in-memory only and returns data only while fresh (default five-second age). It does not persist raw pressure or infer an accuracy from sensor resolution.
- This acquisition path does not yet feed calibrated pressure into the altitude estimator automatically: selecting a trusted GNSS/DEM reference and an effective column temperature remains explicit work. Missing hardware or stale data is neutral.


## Local SRTM tile packs

- `SrtmHgtTilePackStore.kt` imports user-selected ZIP archives into the app-private `filesDir/dem/srtm` directory. It validates canonical one-degree tile names and supported 1201/3601 square grids, stages an archive before committing, and limits each import to 512 MiB of uncompressed data / 50 HGT tiles.
- Archive paths are never used as filesystem destinations; only a validated basename is written. Non-HGT metadata files are ignored, invalid HGT entries reject the staged import, and no network request is made.
- Settings displays tile count and total size and launches the Android document picker. Import prepares data only; tiles are not yet connected to live location fusion or TrustEngine.
