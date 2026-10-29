# Sensor replay format

Status: proposed deterministic local-test envelope.

A replay file should contain a versioned header and ordered records. Each record contains relative elapsed nanoseconds, record type, schema version and payload. The manifest records source dataset, transformations, synthetic/recorded status, label provenance and a digest.

Initial record types may include normalized GNSS fix, aggregate raw-GNSS RF metrics, satellite snapshot, radio estimate plus uncertainty, IMU aggregate, barometer delta, OBD speed and labelled event intervals. Exact personal coordinates and raw radio identifiers are excluded from public fixtures unless synthetic or reviewed/redacted.

Parsers must bound file bytes, record count, field lengths and nesting. Reject unknown mandatory schema versions, invalid geographic ranges, NaN/infinite values and non-monotonic records. Keep source time and arrival time separately for delayed adapters. A malformed record becomes a typed error and must not crash the app.

Keep calibration and holdout sets separate. Public fixtures should be small and reviewable; private route recordings belong outside the repository unless consent and privacy/licence review are documented.
