# Testing strategy

## Pure JVM tests

Test score aggregation, evidence age, monotonic timestamp handling, state hysteresis, context hints and geometry without Android APIs. Tests must be deterministic and independent of wall-clock time.

## Android unit tests

Cover app policies and parsers such as radio fix reliability, mock publication policy, BeaconDB payload validation, OBD protocol parsing and local zone geometry. Invalid and partial input are expected test cases.

## Instrumented/device tests

JVM tests cannot prove:
- that Android exposes each selected mock provider on all supported OS versions;
- what position a downstream app reads from GPS, network or fused APIs;
- vendor-specific Wi-Fi/BLE/cell scan behaviour;
- permission and foreground-service behaviour, Doze recovery and reboot paths;
- battery consumption or surveyed radio-position error.

Maintain a device matrix recording phone model, Android version, Play services availability and raw GNSS/AGC capability. Capability absence must not be counted as a detector failure.

## Detector evaluation

Create labelled replay sets for clean travel, urban canyon, indoor/parking, tunnel/shadow, poor sky view, ordinary receiver faults, satellite visibility changes, synthetic simple-spoof indicators and data gaps. Keep calibration and final holdout sets separate.

Report precision, recall, false alerts per hour, time to detection, recovery delay and calibration metrics when applicable. Break out metrics by AGC and dual-frequency support. Numerical acceptance targets remain open until the reference devices and corpus are approved.

Mock location does not alter raw GNSS measurements and is not a valid raw-measurement spoofing test.

## Repeatability

Use synthetic values and recorded replay for ordinary development. RF transmission intended to imitate or interfere with GNSS is outside this test plan; specialized RF testing requires legal authorization and shielded facilities.
