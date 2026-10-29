# Quality and acceptance criteria

Status: draft. Numerical targets remain TBD until reference devices and a labelled corpus are selected.

## Detector
Report precision, recall, false alerts per clean-travel hour, time to first alert, recovery delay and calibration error if any probability is shown. Include sample counts and confidence intervals. Break results down by AGC, dual-frequency GNSS and constellation support.

## Position
Use surveyed control points or independent reference tracks. Report median and p95 horizontal error, fix availability, time to first usable fix, stale-output duration and uncertainty coverage separately for GPS, radio and fusion. A plausible map track is not an accuracy measurement.

## Stability and battery
Record crash-free duration, service restart count, source timeout rate, ANR/StrictMode findings, storage growth and battery percentage points per hour. Test Doze, permission denial, adapter disconnect, malformed input, process death and reboot.

## Corpus and release gate
Include clean travel, multipath/urban canyon, tunnel/parking, low sky view, ordinary receiver faults, satellite changes, synthetic anomaly cases and data gaps. Keep final holdout data separate from calibration.

CI unit tests must pass; missing hardware must not be scored as a detector failure; each advertised mock mode needs instrumented testing; every numeric target must be tied to a repeatable procedure and documented device. TBD is an open decision, not permission to guess.
