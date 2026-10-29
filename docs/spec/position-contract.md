# Position contract

Status: draft.

## Coordinates and units

Horizontal coordinates use WGS84 latitude/longitude in decimal degrees. Fusion is intended to use a local East-North-Up frame with a documented origin. Distance is metres, speed is metres per second, course is degrees clockwise from true north, and observation ordering uses elapsed-realtime nanoseconds. Optional values are null when unavailable, never zero placeholders.

Accuracy values describe source uncertainty, not a guaranteed bound unless the source semantics justify that claim. Reject non-finite values, latitude outside [-90, 90], longitude outside [-180, 180], negative accuracy, impossible speed and stale/future monotonic timestamps.

## Height

Every height carries a reference: WGS84 ellipsoidal, mean-sea-level/orthometric, pressure-relative or unavailable. DEM comparison requires orthometric height. Android MSL altitude can be used where supported; older-device fallback must identify the geoid model and coverage. Pressure alone is relative and drifts with weather.

## Semantic fix

The proposed PositionFix includes location, horizontal accuracy, optional height and datum, vertical accuracy, speed and speed accuracy, course and course accuracy, wall-clock time, monotonic time, source/fusion method, trust state, risk index and provenance IDs. Names can evolve, but meaning and units cannot.

## Publication

- NORMAL: publish a fresh accepted fix with usable uncertainty.
- SUSPECT: follow configured policy and expose the reason/source downgrade.
- SPOOFED: use independently trusted radio/dead-reckoning position with growing uncertainty; stop if none exists.
- JAMMING: describe signal degradation separately and use only valid independent inputs.
- DEGRADED: show unavailable/stale data; do not infer attack from absent hardware.
- Manual reference is an explicit coarse fallback, not a measured position.

Mock output must not feed the detector. LocationManager, GPS/network providers and Google fused location are separate delivery paths requiring device-level tests. Devices without Play services must retain supported Android-only behaviour.
