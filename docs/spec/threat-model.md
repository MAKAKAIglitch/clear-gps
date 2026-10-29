# Threat model

Status: review draft.

## Assets
Integrity and freshness of published positions; detector states and explanations; local radio observations and zones; route privacy and radio identifiers; import integrity; foreground-service stability.

## Threats and response

| Threat | Effect | Response and limit |
|---|---|---|
| Simple GNSS spoofing | False position/time | Independent checks and raw GNSS features; not universal detection |
| Meaconing/replay | Plausible replayed signal | Detect some inconsistency; synchronized attacks may evade heuristics |
| GNSS jamming | Loss or degradation | Separate JAMMING status and trusted independent fallback only |
| Multipath/tunnel/urban canyon | False alarm | Context hint, hysteresis and labelled difficult clean environments |
| Stale/poisoned radio data | Wrong fallback | Provenance, age penalties, uncertainty and conflict checks |
| Malformed OBD/import/network input | Crash or false value | Bounds, validation, timeouts, circuit breaker, transactional writes |
| Foreign mock app | Replaced system position | Detect mock flag when own mock is disabled |
| Export leakage | Route/network exposure | Privacy-default exports and explicit full-export consent |
| All sources unavailable | No trusted position | Stop publishing; never manufacture coordinates or accuracy |

Android framework, GNSS chipset, vendor firmware, map provider, external radio DBs, OBD adapter and imported files are outside the trusted computing base. BeaconDB is a separate disclosure boundary because the request may reveal selected cell IDs and IP address.

## Non-goals and testing

The app does not guarantee detection of all spoofers, phase-coherent attacks or synchronized replay; guarantee accuracy without independent measurements; upload observations automatically; or write to vehicle ECUs. Use synthetic and recorded replay for normal development. Live RF interference tests require lawful authorization and suitable shielding.

UI explanations must show supporting evidence and hardware limitations. JAMMING is not proof of spoofing. If no current trusted position remains, publication stops.
