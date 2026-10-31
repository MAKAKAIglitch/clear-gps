# Architecture

This document describes the **current implementation** on `dev`. It is intentionally different from a promise that every item in the roadmap is finished. The target contracts and open decisions live in [`docs/spec/`](docs/spec/).

## Gradle modules

The repository currently builds three Gradle modules:

- **`:app`** — Android application, Compose Material 3 UI, MapLibre map, Android services and permissions, local radio database, radio-position estimation, OBD-II transports, mock-location publication, diagnostics, settings and export/import flows.
- **`:core-detect`** — plain Kotlin/JVM code for GNSS anomaly detection and related data models, baseline/environment diagnostics, risk feedback and replay parsing/encoding. It uses JUnit and has no Android dependency declared in its module build.
- **`:core-fusion`** — plain Kotlin/JVM foundation for a 2D local-frame constant-velocity Kalman filter over timestamped GNSS/radio position measurements, with input validation, outlier rejection and a short prediction horizon. The separate `PositionFusionMotionModel` can apply a tightly bounded post-filter correction from fresh east/north IMU samples; it does not change the absolute-measurement timeline or TrustEngine and does not constitute a full inertial EKF.

`:core-fusion` is wired into `LocationMonitorService` through a bounded `PositionFusionTimeline` (45-second window, at most 128 retained measurements). It accepts a physical GPS fix only after `LocationFilterEngine` returns `NORMAL`, source `GPS`, and no active risk zone. A late absolute measurement can be inserted by its original monotonic timestamp and the retained timeline replayed chronologically; duplicate timestamps, entries outside the window, invalid input and statistical outliers are rejected. Long measurement gaps still reinitialize the underlying filter.

In Auto mode, a low-cadence local radio scan can contribute an absolute RADIO measurement to position fusion only while the current physical GPS decision remains NORMAL, the latest accepted physical fix is within the two-second fusion publication horizon, and the fix lies outside enabled risk zones. A separate bounded radio/GNSS consistency signal can be evaluated while GNSS trust is being re-evaluated, provided physical GPS callbacks remain fresh and out-of-zone coordinates remain available, even while the current trust state is being re-evaluated. The source must carry valid monotonic timestamps for every exact type-qualified radio key matched by the current estimate; a missing, invalid or future timestamp rejects that radio estimate. Timestamps are never reconstructed from wall-clock time or restored preferences. If an enabled zone exists, an uncertainty above 500 m is too coarse to rule out intersection and the radio sample is skipped. The service publishes the resulting estimate without replacing the physical GPS provider. The local-radio disagreement component is capped at 45/100 by itself and requires distinct timestamped scans; one observation cannot confirm spoofing. It contributes only as supporting evidence alongside existing motion, network-position or GNSS-signal indicators. Radio fusion is not enabled in explicit GPS-only or Radio-only modes. A post-filter IMU motion correction is now available only within the same two-second prediction horizon: it requires a bounded 10 Hz sample history with no gaps over 250 ms, acceleration no greater than 12 m/s², and a total position correction no greater than 15 m. The reported uncertainty grows with prediction age; invalid, stale or incomplete IMU history falls back to the original constant-velocity prediction. This does not add an absolute measurement, extend the prediction horizon or change TrustEngine. OBD dead-reckoning, calibration of radio/GNSS thresholds, field-accuracy measurements and real-device validation remain open. Do not infer a completed EKF/IMU/OBD/DEM pipeline from supporting classes or specifications.

## Runtime responsibilities

### Android application (`:app`)

- `ClearGpsApplication.kt` installs the process-level uncaught-exception handler and enables log-only Android StrictMode policies only in debuggable builds; `CrashLoopGuard.kt` stores a rolling crash window and blocks the online BeaconDB source after three uncaught crashes in ten minutes.
- LeakCanary 2.14 is scoped to the app's `debugImplementation`; it watches retained Android components while debugging and is not part of the release runtime classpath.
- `ClearGpsApp.kt` wires together the UI and application actions.
- `DeviceCapabilitiesPanel.kt` reports declared Android/GPS/Wi-Fi RTT and sensor capabilities, plus only the fresh GNSS/AGC/frequency evidence already present in the in-memory diagnostics store. Opening it does not request location, run scans or start the service.
- `LocationMonitorService.kt` owns the location-monitoring foreground service.
- `AndroidImuMotionReader.kt` samples `TYPE_LINEAR_ACCELERATION` and `TYPE_ROTATION_VECTOR` at 10 Hz only while monitoring is active. It keeps at most 128 monotonic east/north samples for up to five seconds in process memory only. Live prediction uses a validated slice no older than the two-second fusion horizon; the separate 500 ms snapshot remains available for diagnostics. Samples are never persisted or used as absolute positions.
- LocationMonitorBootReceiver restores previously enabled monitoring only after BOOT_COMPLETED and only with ACCESS_BACKGROUND_LOCATION; otherwise it clears stale mock output and saves a status explaining that manual restart is required.
- `ServiceRecoveryWatchdog.kt`, `ServiceRecoveryWorker.kt` and `ServiceRecoveryPolicy.kt` add a 30-second monotonic service heartbeat, a WorkManager watchdog with a 15-minute minimum interval, and a delayed task-removal/service-destruction check. Recovery requires previously enabled monitoring and location/background-location permissions; Android can still reject a background FGS start, in which case the status asks the user to open the app.
- `MockLocationOutput.kt` handles publication through Android mock providers. The user must select Clear GPS in Developer options; the app cannot silently grant itself mock-location authority.
- `RadioGeolocationRepository.kt`, `RadioFixReliability.kt` and `LocationQuality.kt` implement/support local radio estimates and the quality checks around them.
- `BleBeaconAdvertisementParser.kt` decodes protocol-level BLE beacon identities and auxiliary Eddystone frames before any observation enters the radio database.
- The `Obd2*` classes implement the current read-only OBD-II foundation, transports, polling, vehicle metrics and a short GPS-anchored speed fallback. That fallback requires the original monotonic PID 0D midpoints, query durations no longer than 1.5 seconds, a fresh sample history after the GPS anchor, recent course and conservative limits on sample gaps, age, distance and initial accuracy. Distance is trapezoidally integrated between actual speed samples; missing or stale history fails closed. Wall-clock time is not used to integrate OBD speed into coordinates. This is still a short emergency projection, not continuous OBD dead reckoning; make-specific CAN profiles and field calibration remain open.
- `DiagnosticLogStore.kt`, `GnssReplayRecorder.kt`, `RiskFeedbackStore.kt` and `SettingsZoneBackupCodec.kt` own their corresponding local diagnostic or export data paths.

### Detection core (`:core-detect`)

The JVM module contains GNSS signal/data models and anomaly logic, baseline/environment helpers, and replay codecs. Its tests cover deterministic logic without requiring an Android phone. Device-dependent availability (for example AGC or L5/E5) must be represented as unavailable/neutral rather than fabricated.

## BLE beacon formats and identity

BleBeaconAdvertisementParser reads Android ScanRecord manufacturer data and service data without introducing another runtime scanner library. Current protocol handling is:

- iBeacon: Apple manufacturer ID 0x004C, UUID + unsigned Major/Minor.
- Eddystone-UID: 10-byte Namespace + 6-byte Instance. Standard 20-byte frames require zero RFU bytes; the legacy 18-byte form is accepted.
- Eddystone-URL: compressed URI is decoded and classified; it is not yet exposed in a dedicated beacon-details screen.
- Eddystone-TLM v0: battery mV, signed 8.8 temperature, advertising count and uptime are decoded.
- Eddystone-EID: detected as an ephemeral frame, but not promoted to a persistent anchor unless a future resolver has the required provisioning material.
- RuuviTag RAWv2: manufacturer ID 0x0499; the 24-byte production frame is decoded into an embedded MAC identity plus temperature, humidity, pressure, acceleration, battery/TX power, movement counter and measurement sequence.
- AltBeacon: company ID + 20-byte Beacon ID. SemBeacon is identified when an AltBeacon identity and an Eddystone-URL-compatible scan response are present in the same Android scan record. The persisted key remains the AltBeacon ID across scans even when the URL response is intermittently missing.

Only stable protocol identities are emitted as BLE positioning keys. RuuviTag uses its embedded payload MAC (not the advertiser address) and also exposes parsed sensor values in the scan summary. Eddystone URL/TLM values are shown as scan details; EID is reported as rotating. URL, telemetry and EID metadata are not fabricated into map points. A protocol identity is independent of the advertiser MAC, so a changing address does not split the beacon into multiple local points.

For a generic BLE advertiser with no stable protocol identity, a MAC fallback is allowed only on Android API 35+ when BLUETOOTH_CONNECT is already granted and BluetoothDevice.getAddressType() explicitly reports ADDRESS_TYPE_PUBLIC and the address is unicast/global. The scanner does not request BLUETOOTH_CONNECT just for this fallback. RANDOM, ANONYMOUS, UNKNOWN and unavailable address types are rejected. On older Android releases the address type is not exposed publicly, so generic MAC-only advertisements are not promoted to persistent radio points; recognized protocol identities remain supported.

Radio keys are stored in the existing (radio_type, identifier) columns. This change does not require a SQLite schema migration and does not delete or rewrite previously imported BLE records. BeaconDB's MAC-based payload builder continues to reject protocol IDs; protocol identifiers remain local. Parser and address-policy tests cover valid frames, malformed/truncated payloads, duplicate identities across address changes, SemBeacon composition and randomized/unknown address handling.

Physical-device validation is still required for scan-record delivery, Android/OEM address-type behavior, RSSI stability and scan throttling. Unit tests validate byte parsing and policy, not RF performance or indoor positioning accuracy.
## Home and Map screens

The Compose navigation has separate `HOME` and `MAP` destinations. Home hosts the position/monitoring dashboard and GNSS diagnostics without constructing a MapLibre `MapView`. The Map destination owns the MapView; its existing attach/detach callbacks and AndroidView release path remain responsible for the native view lifecycle.

The Map layer menu independently controls Wi‑Fi, cell, BLE, saved spoofing zones, GPS/radio position centres and accuracy areas, the manual anchor, the latest BeaconDB estimate already returned in the current session, and successful cached OpenCellID cell coordinates that pass the existing `shouldUseForPosition` freshness/status checks. OpenCellID display is capped at 2,000 coordinates and uses a clustered GeoJSON source. The menu flags are stored in UI preferences and affect only MapLibre rendering. They do not trigger recording, change position fusion or source trust, create radio observations, or initiate a provider lookup. The existing Settings switch for showing all radio points remains a master visibility switch above the per-radio-type menu toggles.

BeaconDB contributes only the coordinate in the current in-memory lookup result; it is not persisted as a local radio fingerprint. OpenCellID markers are derived from the separate local cell catalogue and are not the device position. Opening the Map tab or changing either overlay switch does not issue a request. The online/offline basemap remains OpenFreeMap HTTPS tiles or the private blank offline canvas. The Light/Dark/System choice still affects the app and map together; an independently themed map is not implemented.

## Local radio database and provenance

`LocalRadioDatabase` currently extends Android `SQLiteOpenHelper`; the schema is version **11**, uses WAL and enables foreign-key enforcement. This is the implementation today, not Room yet.

Core tables and catalogues:

- `radio_sources` — local or imported source identity and trust settings;
- `radio_points` — normalized radio identifiers and their summary/selected coordinates;
- `radio_observations` — timestamped RSSI/frequency observations with source provenance;
- `scan_history` — bounded scan history;
- `radio_source_segment_settings` — per-source/day/radio-type enablement and trust weights;
- `opencellid_cells` — separate read-only catalogue of estimated cell positions; it is not mixed into local training observations;
- `radio_deletion_undo` and its `*_keys`, `*_points`, `*_observations`, `*_history` and `*_segments` tables — short-lived snapshots for undoing scoped deletions.

Important indexes include point last-seen/source indexes, observation time/source-time/radio-key-time indexes, the import-deduplication index on `(source_id, radio_type, identifier, observed_at, rssi, frequency)`, and the OpenCellID checked-time index. Migrations are additive: version 10 adds the isolated OpenCellID catalogue; version 11 adds the composite import-deduplication index. Existing points, observations and scan history are preserved by these upgrades.

Storage limits are independent:

- Detailed `radio_observations` retention defaults to **20,000** rows; the selectable limits are 5,000, 10,000, 20,000 and 50,000. The maximum is **50,000**. Reducing this limit prunes the oldest detailed observation rows; it does not delete `radio_points` summaries or their selected coordinates.
- `scan_history` retains at most **500** entries.
- The persisted map marker limit is separate from observation retention. It defaults to **50,000** geolocated `radio_points`; choices are 5,000, 10,000, 20,000, 50,000, 100,000, 250,000 and 500,000, with a maximum of **500,000**. Only rows with valid coordinates can become map markers; there is no additional low-RAM/device-class cap. Wi-Fi, cell and BLE point layers use MapLibre clustering.

Imports are merged into the existing local database, not used to replace it. The implemented file formats include WiGLE CSV (plain/gzip), WiGLE-shaped SQLite (`network/location`) and a full Clear GPS SQLite backup. A WiGLE-shaped export cannot preserve every Clear GPS-only field: use the full backup to preserve application history and metadata. Settings/zones use a separate `.cgsettings` format.

SSID exclusion is applied on supported recording and matching paths. The radio-position estimate is not meant to become its own ground-truth training coordinate; provenance and quality remain relevant when positions conflict.

## Service recovery and background limits

The foreground monitor updates a monotonic heartbeat while monitoring is enabled. WorkManager checks it periodically (minimum interval 15 minutes), and a one-time check is queued after `onTaskRemoved` or a service-destruction callback. The one-time check waits 45 seconds and recovers only if the service did not write a newer heartbeat. The worker never starts monitoring when the persisted user-enabled flag is false; it requires foreground and background location permissions and clears stale position fields before requesting recovery. Android/OEM restrictions may still block a background start, so this is best-effort rather than a guarantee of uninterrupted location tracking. Settings now has an explicit, user-triggered request to exempt long-running location monitoring from battery optimization, with a fallback to system battery settings if the direct screen is missing. No permanent wake lock is acquired, and the exemption request can be declined. Physical-device/Doze testing remains required.

## OBD-II telemetry routing and reconnect

- The app does not currently use a separate `SensorHub` class. `Obd2Repository` polls only the allowlisted SAE J1979 Mode 01 PIDs advertised by the ECU; `Obd2SharedState` is the short-lived preference hand-off from the Compose connection owner to `LocationMonitorService`.
- PID `0D` (vehicle speed) is the only OBD value used by location logic: `LocationMonitorService` feeds its fresh monotonic sample into the GNSS trust detector; `Obd2LocationFusion` can make a short GPS-anchored prediction; `Obd2SpeedCalibration` compares it with trusted physical GNSS; `BatteryProfilePolicy` adjusts radio scan cadence. `Obd2DriveMetricsTracker` also estimates session distance/acceleration for the OBD screen only; it is not a trip odometer and is not fed into spoof risk.
- PIDs `0C`, `04` and `11` feed `Obd2GearRatioLearner`: RPM/speed pairs are time-aligned and must pass the trusted GNSS↔OBD speed comparison; engine load and throttle reject unstable/high-load samples. Learned ratios stay in memory for the current connection and are not real gear numbers.
- PIDs `0B` (manifold pressure), `05` (coolant temperature) and `33` (barometric pressure) are currently UI diagnostics and optional explicit external broadcast fields only. They do not yet feed location or spoof-risk calculations; the barometric path belongs to P3.6. The OBD screen includes a `PID → consumers` trace with value, issue, sample age and actual consumers. PID `0C/0D` age uses monotonic request midpoints; the other PIDs have only a poll-cycle timestamp.
- After a successful user-selected connection, the selected endpoint and auto-reconnect preference are stored locally. Startup/resume attempts the same endpoint when Bluetooth permission is already granted. A transient loss retries with 1/2/4/8/15/30/60-second backoff, capped at one minute and continuing until auto-reconnect is disabled or the user disconnects. Permission/configuration errors do not spin. BLE discovery remains manual and is never started by auto-connect; Wi-Fi TCP still requires the user to connect Android to the adapter's private network, and Clear GPS never switches the active network.
- Missing, stale, future or slow PID `0D` samples are neutral/cleared rather than carried forward. OBD values are read-only; VIN/address are not uploaded, and telemetry is not written to radio fingerprints or training data.

## Automatic GPS ↔ radio fallback

Auto mode keeps physical GNSS as its preferred position source. If no physical GPS fix has arrived within the acquisition grace period, or an already-seen GPS source remains untrusted / inside a risk zone for 10 continuous seconds, the service switches its position path to radio. The first-fix grace is at least 30 seconds and expands to twice the configured location-update interval when that is longer. This state machine is active only in Auto mode; explicit GPS-only and Radio-only modes retain their separate behavior.

While Radio is active, the radio positioning loop continues uninterrupted. Every 120 seconds the service starts a GPS-only probe for at most 30 seconds. A fresh physical GPS fix must be accepted as NORMAL and outside enabled risk zones before Auto returns to GPS. Probe callbacks update GNSS trust and diagnostics but cannot replace or disable the active radio output. When a probe expires or GPS is unavailable / permission is absent, radio remains active and the next check stays on the two-minute cadence.

Automatic RADIO estimates inside the fusion uncertainty limit use the original monotonic measurement timestamp, the existing risk-zone uncertainty gate, a continuity speed bound and a dedicated position-fusion timeline with a 45-second measurement-gap limit. The timeline uses the bounded constant-velocity Kalman model; fresh world-frame IMU data can correct only its existing two-second prediction horizon. A jump that exceeds the time-based movement bound is rejected instead of falling back to the raw coordinate. When the user explicitly enables low-accuracy publication for estimates above the fusion limit, the estimate keeps its measured uncertainty and still passes the time, zone and continuity gates, but does not enter the Kalman core. These safeguards reduce abrupt changes but are not a field-accuracy guarantee.

OBD PID 0D remains a speed-only input. It has no independent course, so the old GNSS bearing is not reused after a GNSS trust failure and OBD is not presented as radio-mode dead reckoning. A real vehicle/road test remains required; see docs/testing/device-validation.md.

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
- The foreground service now assesses readiness after each successful local SRTM lookup made from a trusted physical GPS fix. It keeps only an in-memory readiness snapshot and emits a diagnostic-journal entry when readiness changes; coordinates, height values and raw pressure are not added to this snapshot or log. The readiness gate rejects unknown MSL realization, ellipsoid/EGM96 mismatch, stale or invalid monotonic timestamps, and missing uncertainty. It does not compute a discrepancy or affect TrustEngine risk.
- The existing SRTM HGT reader does not attach source uncertainty metadata, so even a same-datum observation remains unavailable for comparison until the imported product's uncertainty is described. Copernicus GeoTIFF, datum conversion, calibration from logs and real-device validation remain separate work.
## Network and privacy boundaries

- `NetworkGate.kt` is the only approved Internet egress for app-owned HTTP and MapLibre resource requests. It validates HTTPS, exact hosts/paths, request methods and each provider's separate opt-in; diagnostics record only the operation, never URL query strings or request bodies. Android's `network_security_config.xml` also disables cleartext.
- BeaconDB lookup is disabled by default. With the explicit lookup switch enabled, `POST https://api.beacondb.net/v1/geolocate` may carry only current supported Wi-Fi BSSID/RSSI, eligible public BLE MAC/RSSI and supported GSM/WCDMA/LTE cell ID/RSSI. SSID, Bluetooth names/payloads, GPS coordinates, scan history and stored observations are excluded. This POST is required by the read-only Ichnaea-compatible geolocate API; it is not a contribution endpoint. `/v2/geosubmit` is not allowlisted.
- OpenCellID uses only `GET https://opencellid.org/cell/get` for current supported cell identifiers plus the user API key. It does not send RSSI, Wi-Fi/BSSID, BLE, GPS coordinates or observation history. The separate OpenCellID switch/key, per-batch cooldown and local daily budget remain required; its write/contribution API is not allowlisted.
- MapLibre resources are routed through its `HttpRequestUtil` OkHttp Call.Factory and checked on every network exchange/redirect. Only HTTPS GET resources from `tiles.openfreemap.org` are accepted while the saved map source is `online`; offline mode blocks map egress. The map provider sees the IP address and tile area, not radio identities or app positioning fixes.
- No app-owned radio observations, local database, settings backup, replay, diagnostic log or WiGLE export is uploaded. Wi-Fi TCP to a user-selected private OBD adapter is a local peripheral connection and is not an Internet lookup.
- BeaconDB and OpenCellID lookup results are used as described in the source-fusion section above; they are not written into local training fingerprints as ground-truth observations.
## Build and test flow

The Android CI uses JDK 17 and the checked-in Gradle Wrapper:

```sh
./gradlew --no-daemon --stacktrace :core-detect:test testDebugUnitTest assembleDebug
```

On Windows, use `gradlew.bat` or the checked-in `windows-build/build-apk.ps1`. CI also validates PowerShell syntax, verifies the Gradle Wrapper JAR checksum, and smoke-tests Gradle argument forwarding on Windows. Automated tests are necessary but do not replace real-device checks listed in [`docs/testing/device-validation.md`](docs/testing/device-validation.md).


## Android barometer capture

- `AndroidBarometerSensorReader.kt` subscribes to `Sensor.TYPE_PRESSURE` only while the location monitor is active, using a one-second sampling-period hint. Samples use the sensor event's monotonic timestamp and are converted from hPa to Pa.
- The live store is in-memory only and returns data only while fresh (default five-second age). It does not persist raw pressure or infer an accuracy from sensor resolution.
- The altitude readiness diagnostic checks whether a fresh pressure sample exists at the time of the DEM assessment, but labels it raw-only. It does not convert pressure into height, because reference calibration, pressure uncertainty and effective mean-column temperature are not yet supplied. Missing hardware or stale data is neutral.


## Local SRTM tile packs

- `SrtmHgtTilePackStore.kt` imports user-selected ZIP archives into the app-private `filesDir/dem/srtm` directory. It validates canonical one-degree tile names and supported 1201/3601 square grids, stages an archive before committing, and limits each import to 512 MiB of uncompressed data / 50 HGT tiles.
- Archive paths are never used as filesystem destinations; only a validated basename is written. Non-HGT metadata files are ignored, invalid HGT entries reject the staged import, and no network request is made.
- Settings displays tile count and total size and launches the Android document picker. Import prepares data only; tiles are not yet connected to live location fusion or TrustEngine.
