# Decision register

Status: open items. Defaults in code do not settle product decisions.

| ID | Decision | Closure evidence |
|---|---|---|
| D-01 | Keep MIT or approve GPL-3.0 | Owner approval and rights review |
| D-02 | Final score units, prior and calibration | Formula plus labelled replay/calibration report |
| D-03 | Precision/recall/false-alert/delay targets | Approved holdout report |
| D-04 | Priority/policy for SPOOFED, JAMMING, DEGRADED in each mode | State-by-mode table and tests |
| D-05 | Maximum age per source and mode | Measured limits |
| D-06 | Radio median/p95 location error | Surveyed control-point report |
| D-07 | Reference device matrix and minSdk | Tested device/API matrix including no-GMS |
| D-08 | Eco/Normal/Max battery targets | Repeated battery baseline |
| D-09 | Export hashing and coordinate coarsening | Field inventory and privacy tests |
| D-10 | Database/log retention and BeaconDB TTL | Defaults, cleanup UI and expiry tests |
| D-11 | Provenance after observation deduplication | Schema and idempotent merge tests |
| D-12 | DEM/geoid implementation and licence | Datum, coverage, terms and fallback decision |
| D-13 | Manual fallback wording and scope | Product/UI approval |
| D-14 | Android Auto scope | Keep experimental unless separately approved |

The current core-detect weights and thresholds are provisional test defaults. They do not close D-02 or D-03. Questions requiring a device or dataset close only with evidence, not guessed numbers.
