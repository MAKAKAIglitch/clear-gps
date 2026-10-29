# Roadmap

A stage is complete only when its automated tests and the relevant real-device scenario pass.

## Foundation

- [x] Stage 0 technical specification draft.
- [x] Pure Kotlin trust-state foundation with JVM unit tests.
- [x] Conservative GNSS environment-context hint.
- [x] Antimeridian-aware local risk-zone geometry and regression tests.
- [x] CI invokes core-detect JVM tests.
- [ ] Review score semantics and close blocking decisions.
- [ ] Integrate TrustEngine with Android feature extraction after review.
- [ ] Add replay adapter and first labelled baseline dataset.

## Detector v1

- [ ] Raw GNSS capability discovery and feature normalization.
- [ ] Per-constellation and dual-frequency-aware metrics.
- [ ] Device baseline updated only during accepted NORMAL periods outside risk zones.
- [ ] Context hints never suppress independent attack evidence.
- [ ] Replay metrics and thresholds approved before probability wording.

## Position, sources and storage

- [ ] Unified PositionFix contract and timestamp policy.
- [ ] Room schema, migration tests and source provenance.
- [ ] Import preview, transaction rollback, source delete and retention controls.
- [ ] One guarded mock publisher and instrumented test app.
- [ ] Freshness, accuracy growth and stop-publication behaviour verified on device.

## Radio and vehicle

- [ ] Local Wi-Fi weighted k-NN and conservative no-match policy.
- [ ] BLE stable-beacon policy and randomized-address filtering.
- [ ] Cellular data treated as coarse anchor unless coordinates are verified.
- [ ] OBD PID discovery, timeouts, circuit breaker and fuzz tests.
- [ ] Vehicle profiles remain read-only experiments.

## Release hardening

- [ ] Battery/startup baseline on a reference device.
- [ ] At least three device families including no-GMS coverage.
- [ ] Privacy export review, dependency license inventory and release checklist.
- [ ] Signed release and distribution metadata validation.

Deferred from version 1.0: shared cloud radio database, automatic uploads, general-purpose navigation, unreviewed vehicle CAN profiles and Android Auto.
