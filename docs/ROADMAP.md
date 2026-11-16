# Roadmap — dev

This roadmap describes planned work on the `dev` branch. An item is complete only after its automated tests and the relevant real-device scenario pass. Existing code is listed separately from work that is still required; a partial implementation does not make a stage complete.

## Current foundation in `dev`

The following building blocks already exist, but they do not yet provide the full navigation pipeline described below:

- Local SQLite radio data for Wi-Fi, cellular and BLE, with source provenance, timestamps, trust weights and retention controls.
- Local radio positioning and separate, consent-gated BeaconDB / OpenCellID lookups.
- A timestamped 2D constant-velocity Kalman foundation for absolute GNSS and RADIO measurements, including outlier rejection and bounded timeline replay.
- An IMU reader for `TYPE_LINEAR_ACCELERATION` and `TYPE_ROTATION_VECTOR`, sampled at about 10 Hz while monitoring is active. Its current correction is short-lived (up to 2 seconds, with a 15 m correction cap); it is not continuous inertial navigation.
- Motion-aware radio scheduling and battery profiles, while full radio snapshots are currently more conservative than the desired high-cadence collector.

The full sensor-driven dead-reckoning engine, continuous PDR, high-cadence independent radio collectors and optional road map-matching are **not implemented yet**.

## R1 — Faster local radio geolocation

- [ ] Profile the complete local-position path: scan-result ingestion, identifier matching, SQLite reads, fingerprint scoring, fusion and publication. Record baseline latency on small, medium and large databases.
- [ ] Inspect existing SQLite indexes and query plans with `EXPLAIN QUERY PLAN`. Add or adjust indexes only where benchmarks show a real bottleneck; cover radio type + normalized identifier, observation recency and the actual candidate lookup predicates.
- [ ] Replace per-radio / per-observation query loops with bounded batched queries and prepared statements. Avoid N+1 reads and avoid loading the full observation history for each position update.
- [ ] Build a small candidate set from the identifiers actually heard in the latest scan. Score candidates in memory using signal level, frequency where useful, observation age, source trust, coordinate uncertainty and independent-radio support.
- [ ] Run independent Wi-Fi, BLE and cellular matching work concurrently on background dispatchers; merge partial results as they arrive instead of waiting for the slowest collector.
- [ ] Add a bounded cache for recently used candidate summaries and repeated scan fingerprints. Invalidate it on relevant database edits, source/trust changes, imports and deletions.
- [ ] Keep local measured coordinates, imported coordinates and external geolocation estimates separate. Never let an external estimate silently become a measured radio point.
- [ ] Preserve SSID exclusions, risk-zone checks, provenance, monotonic timestamps, outlier rejection and uncertainty growth during every optimization.
- [ ] Add reproducible benchmarks and diagnostics for lookup latency, database query time, candidate count, cache hit rate and time from last radio callback to a new position.

**Exit criteria:** the same input database and scan produce equivalent or better position quality, with a measured reduction in end-to-end lookup latency. No stale result may be presented as a fresh fix.

## R2 — Maximum practical radio scan cadence

Goal: collect every useful radio observation as soon as the platform and hardware make it available, without making one radio technology wait for another.

- [ ] Split Wi-Fi, BLE and cellular collection into independent asynchronous collectors with their own timestamps, error states, cadence policies and cancellation.
- [ ] Wi-Fi: subscribe to `SCAN_RESULTS_AVAILABLE_ACTION` and consume fresh results from scans performed by Android or other apps. On Android 10+ this broadcast can report full scans performed by the platform or other apps.
- [ ] Wi-Fi: request an active scan only when the platform policy says another request is appropriate; check the return value, process success/failure callbacks and distinguish a throttled/rejected request from an empty scan.
- [ ] Remove the global radio-snapshot bottleneck where possible. A new BLE or cell observation must be usable immediately without waiting for the next Wi-Fi scan, and vice versa.
- [ ] BLE: keep a movement-aware foreground scan session where supported, use the appropriate low-latency mode while actively surveying, and batch/persist observations without repeatedly tearing down and restarting the scanner.
- [ ] Cellular: use asynchronous cell-info callbacks and change/availability signals where supported; refresh only when the modem/platform permits it and keep per-cell measurement timestamps.
- [ ] Add a user-selectable **Max scan** profile. It should use the fastest supported cadence for each source independently, while exposing the actual achieved cadence and platform throttling. It must not claim a scan occurred when only a cached result was read.
- [ ] Adapt scanning to movement, signal novelty, location uncertainty, screen/service state, battery and thermal state. Reduce duplicate scans when stationary; increase collection priority while moving or when the position estimate is degrading.
- [ ] Use bounded per-source event queues with coalescing/backpressure so a fast sensor or BLE callback cannot create unbounded memory growth or block the UI/SQLite writer.
- [ ] Display last-result age, real scan/callback rate, rejected/throttled requests and per-source status in diagnostics.
- [ ] Test foreground, screen-off, Doze, Battery Saver and several OEM implementations. Document which limits are Android-wide and which are device-specific.

**Platform constraint:** Android 9 and Android 10+ normally throttle `WifiManager.startScan()` to four scans per foreground app in two minutes; background apps share a one-scan-per-30-minute limit. Android 10+ also broadcasts full scan completions from the platform and other apps, which Clear GPS can observe passively. A developer option can disable Wi-Fi scan throttling for local testing, but an ordinary app cannot silently override the system policy. See the [Android Wi-Fi scanning guide](https://developer.android.com/develop/connectivity/wifi/wifi-scan).

Use [NeoStumbler](https://github.com/mjaakko/NeoStumbler) as a behavior/reference project for movement-aware scan cadence and explicit throttle-aware settings, not as a runtime dependency. Its [user guide](https://github.com/mjaakko/NeoStumbler/blob/master/docs/user_guide.md) describes advisory scan-frequency settings and the requirement to change Android's developer setting before ignoring Wi-Fi throttling. Do not copy its intervals as universal guarantees.

## R3 — Full phone-sensor inventory and asynchronous sensor pipeline

- [ ] At runtime enumerate all available sensors and record capability metadata: sensor type, vendor/name, wake-up status, reporting mode, minimum delay, maximum range, resolution and current availability.
- [ ] Cover available physical and virtual sensors where useful: accelerometer, gyroscope and uncalibrated gyroscope, magnetometer and uncalibrated magnetometer, gravity, linear acceleration, rotation vector, game rotation vector, geomagnetic rotation vector, pressure/barometer, step detector, step counter and significant-motion detector.
- [ ] Treat GNSS speed/course/altitude, Wi-Fi RTT when supported and optional OBD-II speed/pressure as additional motion or anchor inputs, not as interchangeable Android motion sensors.
- [ ] Distinguish a missing sensor from a sensor that is temporarily unavailable, permission-blocked, rate-limited or returning stale/low-quality values.
- [ ] Move sensor callbacks and preprocessing off the UI thread. Use monotonic event timestamps, independent streams and a common fusion clock; never substitute callback-arrival time for measurement time.
- [ ] Maintain bounded, chronological in-memory buffers with explicit gap, age, accuracy and saturation checks. Preserve only the minimum diagnostics needed; raw motion history remains local and is not uploaded.
- [ ] Select sampling rates by motion state and accuracy need. Measure the delivered rate from sensor timestamps: Android sampling delays are hints, not guaranteed rates.
- [ ] Request `HIGH_SAMPLING_RATE_SENSORS` only if profiling proves that a rate above Android's default limit is needed. Handle the permission being absent and system privacy/rate limits without crashing.
- [ ] Avoid double-counting derived sensors that are calculated from the same physical inputs. Prefer the best available calibrated/virtual output while retaining raw streams for bias and quality checks where useful.
- [ ] Stop unneeded sensor subscriptions when monitoring is stopped; clear transient buffers on service shutdown/restart and handle device rotation/orientation changes.

**Exit criteria:** capability discovery works on devices with different sensor sets; missing sensors degrade gracefully; sensor events stay ordered and bounded; no callback or database operation blocks the UI.

## R4 — Continuous inertial navigation / dead reckoning

- [ ] Establish the initial absolute anchor from a recent trusted GNSS or radio fix. Without a valid anchor, inertial data may describe motion but must not invent a global starting coordinate.
- [ ] Implement a documented attitude solution in quaternion form. Fuse gyroscope integration with rotation-vector / gravity / magnetometer evidence and track magnetic disturbance and calibration quality.
- [ ] Transform device-frame measurements into a local ENU frame using accurate orientation and timestamps. Remove gravity consistently; reject gaps, saturation, jumps and implausible acceleration.
- [ ] Implement pedestrian dead reckoning (PDR): step detection, step timing, heading, adaptive stride-length estimation and stationary detection. Calibrate stride only from trusted position/speed segments; do not assume a fixed step length for every user or device.
- [ ] Implement a separate vehicle-motion model. Prefer trusted GNSS speed/course or fresh read-only OBD speed as longitudinal constraints; use IMU acceleration/gyro for short-term dynamics and turns. Do not rely on unbounded double integration of accelerometer data for long drives.
- [ ] Add zero-velocity / stationary updates when multiple independent signals support that the device is stopped. Reject false stationary decisions caused by smooth vehicle movement or a phone resting in a moving vehicle.
- [ ] Use barometer data for relative vertical change only after calibration against a trusted altitude anchor; pressure alone must not create a horizontal position.
- [ ] Upgrade the current short IMU correction to a continuously propagated state with velocity, attitude, sensor biases and covariance (error-state EKF or another estimator justified by tests). Keep absolute GNSS/RADIO measurements as corrections to the motion state, not as a stream of fabricated IMU fixes.
- [ ] Extend the position contract and fusion metadata to represent a DEAD_RECKONING-derived estimate and its age/uncertainty. Keep the distinction between an absolute anchor and a predicted position explicit.
- [ ] Grow uncertainty continuously while moving without an absolute correction. Enforce a configurable maximum prediction age/uncertainty; stop publishing when the estimate is no longer useful instead of pretending it is fresh.
- [ ] Recover cleanly after a new trusted radio/GNSS anchor, including correction of accumulated drift without a visible coordinate jump where a smooth correction is possible.
- [ ] Add deterministic sensor-log replay tests for walking, stationary, vehicle turns, acceleration/braking, missing samples, magnetic disturbances and long anchor outages.

## R5 — Radio-assisted drift correction and unified fusion

- [ ] Feed each accepted local Wi-Fi/BLE/cell estimate into the fusion timeline as a timestamped absolute observation with source-specific uncertainty and trust.
- [ ] Use new radio observations to correct accumulated inertial drift while moving. Weight by the number and geometry of matching transmitters, signal quality, observation age, source provenance and disagreement between independent radio groups.
- [ ] Allow incremental correction when one source updates; do not wait for every radio technology to return a result.
- [ ] Preserve hard GNSS trust gates, risk-zone policy, source enablement switches and the rule that no valid anchor means no falsely precise global coordinate.
- [ ] Add optional Wi-Fi RTT ranging when the device and access points support it; keep ranging evidence distinct from ordinary RSSI positioning and verify actual device support.
- [ ] Keep BeaconDB/OpenCellID as separate, consent-gated, bounded external estimates. They may help re-anchor the current estimate, but must not be silently persisted as locally measured fingerprints.
- [ ] Test recovery from drift after 10, 30, 60 and 120 seconds without an absolute fix, with and without new radio observations.

## R6 — Optional road / path map matching

- [ ] Add a user-controlled **Road matching** setting with an immediate on/off switch. The raw fused position must remain available separately from the map-matched output.
- [ ] Use a local/offline OpenStreetMap-derived road and path graph. Keep graph data separate from map tiles; review ODbL attribution and data-update requirements before bundling or distributing extracts.
- [ ] Support distinct travel profiles (walking, cycling and vehicle) so the matcher considers suitable roads, paths and access restrictions.
- [ ] Match a sequence of positions, not a single point, using distance to candidate segments, reported uncertainty, heading, speed, road class, connectivity and temporal continuity.
- [ ] Apply map matching as a soft correction only when confidence is high and the candidate lies within the fix's plausible uncertainty corridor. Do not snap across blocks, rivers, barriers, grade-separated roads or disconnected intersections.
- [ ] If there are no plausible candidates, weak heading evidence, poor map coverage or conflicting radio evidence, retain the unmodified fused estimate and report that map matching was skipped.
- [ ] Let fresh radio fixes and inertial motion select/correct the route candidate. A road candidate must never override a much stronger independent measurement merely because it is geometrically close to a road.
- [ ] When the user turns the option off, immediately return to the unsnapped fused position. Do not rewrite radio database coordinates, recorded observations or the raw trajectory.
- [ ] Test parallel streets, motorway/service roads, bridges/tunnels, roundabouts, footpaths, off-road movement, GPS drift and radio-only positioning.

## R7 — Accuracy, performance, battery and device validation

- [ ] Record reference routes with independent ground truth and compare: radio-only, current fusion, IMU-only after a trusted anchor, IMU + radio correction, and each of those with road matching enabled.
- [ ] Measure horizontal error, drift per minute without anchors, time to recover after a new anchor, position-update latency, real scan rate, dropped/throttled events, CPU, memory, battery drain and thermal state.
- [ ] Add replay/unit tests for filtering, fusion, map matching, timestamp ordering, sensor loss, database invalidation and queue backpressure; add Android instrumentation tests for actual callbacks and permissions.
- [ ] Validate on at least three device families, including a device without Google Play services and devices with incomplete sensor sets. Record model/Android build only in local test reports unless explicitly approved for publication.
- [ ] Verify that Max scan mode, continuous sensors and road matching do not create unbounded storage, excessive wakeups or thermal throttling.
- [ ] Set numeric accuracy and battery targets only after collecting baseline measurements. Do not mark a stage complete based only on a successful build or synthetic tests.

## Cross-cutting requirements

- No silent radio uploads, sensor uploads or route uploads. Existing external providers remain individually consent-gated.
- All raw radio observations, sensor history, anchors and unsnapped trajectories remain local by default.
- Every input carries a monotonic measurement timestamp, source identity, quality and uncertainty where applicable.
- Every queue, cache, history and map-matching search has an explicit size/time bound.
- UI remains responsive while scanning, querying SQLite, fusing sensor events or matching roads.
- No dependency is added solely to imitate another scanner. Prefer Android platform APIs and small, audited components; review dependency activity, license, permissions and transitive dependencies first.
