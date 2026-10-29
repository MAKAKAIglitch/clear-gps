# Sources matrix

Status: qualitative; numerical error characteristics require measurement.

| Source | Main use | Limitation | Failure behaviour |
|---|---|---|---|
| Raw GNSS | Primary fix and integrity features | Chipset/API variance; multipath and receiver faults | Disable unavailable features neutrally |
| Android network location | Coarse independent cross-check | Vendor and network-service dependence | Preserve age and accuracy |
| Local Wi-Fi database | Local fingerprint matching | Sparse/stale data, RSSI bias, mobile APs | Return no fix when match is weak |
| BLE | Stable beacon evidence | Randomized addresses and changing beacon config | Require stable validated signals |
| Cellular | Coarse anchor | Sparse coverage and uncertain tower coordinates | Keep provenance; avoid false precision |
| Phone IMU | Short-term movement and orientation changes | Drift and unknown in-vehicle orientation | Use uncertainty and calibration |
| OBD-II speed | Motion cross-check/dead reckoning | Clone adapters and delayed samples | Timeout, validate and circuit-break |
| Barometer | Relative vertical movement | Weather drift and offset | Never claim absolute height alone |
| DEM | Terrain elevation comparison | Grid and datum error | Carry datum and uncertainty |
| BeaconDB | Experimental cell-only lookup | Remote disclosure and incomplete coverage | Opt-in, off by default, separate TTL/provenance |
| Map service | Background map rendering | Server sees IP and tile requests | Explain network use; custom/offline option if implemented |

Availability is not ground truth. Every estimate needs provenance, time, uncertainty and capability. Source conflict should be explicit; do not silently promote stale radio data or average incompatible measurements without tested covariance.
