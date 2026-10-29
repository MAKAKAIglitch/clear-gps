# Radio database format

Status: draft schema contract.

## Provenance model

A Source represents where a record came from: local scan, WiGLE import, BeaconDB lookup or custom file. Each RadioObservation stores the measured radio identity and observation details. When records merge, preserve links to every source instead of overwriting provenance with one source ID.

Suggested entities:
- Source: stable ID, kind, label, import time, file digest, trust weight, terms/license reference and enabled state.
- RadioObservation: radio kind, normalized ID, RSSI, frequency/channel, optional coordinates and horizontal uncertainty, measurement time, last-seen time and validation flags.
- ObservationProvenance: observation/source relation with acquisition and source-specific metadata.
- ScanSession: time span, device/build capability metadata and accepted/rejected counts.

## Identity and validation

Normalize Wi-Fi BSSID before comparison. A BLE address may be randomized and cannot automatically be treated as a permanent beacon ID. A cellular key includes radio technology and all available MCC/MNC/TAC/LAC/CI/PCI fields; missing fields remain null. Do not invent coordinates for records without coordinates.

Bound file size, row count, field length and transaction time. Reject invalid coordinates, non-finite RSSI and malformed IDs. Never execute imported SQL or arbitrary query strings.

## Merge and migrations

Deduplicate only within documented tolerance for identity, type, time and measurement. Keep all distinct source relationships. Update last-seen from the newest valid observation without silently replacing a better-documented coordinate with an unverified estimate. Keep unknown import fields in bounded versioned metadata where safe.

Imports are transactional and idempotent. Every schema change has a versioned migration and tests against prior schemas. Index source, type/identity and observation time where query plans justify them.
