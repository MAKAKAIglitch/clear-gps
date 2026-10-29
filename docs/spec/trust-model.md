# Trust model — TrustEngine

Status: draft. Weights and thresholds require labelled replay calibration.

## Output and evidence boundary

The engine emits NORMAL, SUSPECT, SPOOFED, JAMMING or DEGRADED, an explainable 0–100 risk index and stable reason codes. The new JVM module marks the index uncalibrated. Do not call it probability until a calibration report supports that interpretation.

The detector consumes normalized GNSS-derived features and independent checks; Android system Location must not become raw-detector input. Feature families include RF/AGC/CN0, satellite/constellation behaviour, clock consistency, GNSS versus network/radio, IMU versus GNSS, OBD speed, compass/course and DEM/barometer height.

Missing AGC, L5/E5, barometer, IMU, OBD or other device capability is neutral. Quality and availability are separate from value. Correlated features must not be counted as independent votes without justification.

## Scoring and state

Initial planning bands are risk below 30 for NORMAL, 30–69 for SUSPECT and 70–100 for candidate SPOOFED. A high score requires repeat evidence; recovery requires clean-sample hysteresis and minimum state hold. JAMMING is separate and needs its own RF-degradation assessment. DEGRADED indicates inadequate or stale inputs.

The new engine caps contributions per broad domain, applies time-based smoothing and ignores malformed/stale observations. Its coefficients are provisional test defaults, not calibrated statistics. Current Android runtime integration remains deferred.

## Risk zones and personal baseline

A configured risk zone may raise prior risk or trigger a stronger app policy. Zone logic must account for accuracy circles, polygon boundaries, antimeridian and hysteresis. Personal CN0/AGC/satellite baselines may update only during accepted NORMAL periods outside risk zones; never learn from suspect, stale or externally referenced positions.

## Calibration and limits

Use labelled replay data; report precision, recall, false alerts per hour, detection delay, recovery delay and calibration error. Break results down by AGC availability, dual-frequency support and constellation capability. Keep calibration and holdout datasets separate. Multipath and phase-coherent or synchronized replay attacks can evade heuristics, so universal detection must not be promised.
