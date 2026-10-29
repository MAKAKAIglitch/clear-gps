# Privacy specification

Status: draft.

## Defaults
- Keep radio identifiers and observations in app-private storage.
- Do not upload Wi-Fi, BLE, route or GPS data by default.
- BeaconDB is experimental, opt-in and disabled by default; consent names the data sent and explains that the service sees the request IP.
- Do not call contribution/submission endpoints without a separate product decision.
- Keep Android backup disabled unless a reviewed export/restore design is implemented.

## Logs and exports
Default diagnostics omit raw coordinates, SSID, BSSID, BLE addresses and full cell IDs. Prefer reason codes and aggregates; bound size and retention. Logs stay local unless the user explicitly copies or shares them.

Default exports should omit raw radio identifiers and coarsen precise positions when those fields are not required. A full-fidelity export needs a separate confirmation explaining the privacy impact and a preview of its scope. A per-export random salt may reduce easy cross-export linkage but hashed identifiers are still linkable within one export.

## Network inventory and tests
List each host, payload field, purpose, trigger, consent, retention and licence/terms. Include map styles/tiles because public map servers see ordinary IP and tile requests even if no radio identifier is attached.

Test that BeaconDB stays disabled unless explicitly enabled, only approved cell fields are sent, Wi-Fi/BLE/GPS fields never enter that request, logs omit identifiers and exports respect the selected privacy level.
