# Clear GPS Threat Model

**Status:** concise repository-level summary. The detailed specification is [`docs/spec/threat-model.md`](docs/spec/threat-model.md); privacy-specific behavior is documented in [`docs/spec/privacy.md`](docs/spec/privacy.md). This is a research application, not a guarantee that every spoofing/jamming attack can be detected.

## Assets and safety goals

- Position fixes, raw GNSS data, trust/risk state and mock-location output.
- Local radio identifiers (Wi-Fi/BSSID/SSID, available cell identifiers and BLE addresses), coordinates, timestamped observations and imported-source provenance.
- User-defined risk zones, diagnostic/replay files, settings and complete database backups.
- Correct handling of unavailable sensors, OBD failures and untrusted imported files.

The application should avoid labelling an untrusted or stale position as fresh. If no position satisfies the publication policy, stopping publication is safer than inventing a coordinate. A radio estimate is not automatically promoted to training truth.

## Threats considered

- GNSS spoofing, jamming, meaconing and some replay patterns detectable from the fields available on the device.
- Conflicting position evidence from GNSS, local radio, physical movement, time and available vehicle measurements.
- Moved or misleading radio points and imported records with uncertain provenance.
- A third-party mock-location provider, stale fixes or an unavailable data source.
- Malformed, corrupt, unsupported or deliberately oversized CSV/SQLite/replay/settings files.
- Accidental disclosure through logs, exports or online lookups.

Loss of GNSS in a tunnel, garage, indoor space or urban canyon is not by itself proof of an attack. Missing AGC, L5/E5 or another unsupported measurement is neutral, not a positive attack signal.

## Current controls

- Detection is designed around raw GNSS callbacks; the application's own mock position must not be treated as independent raw GNSS evidence.
- Sources have separate error boundaries and provenance. Import merges are validated, bounded and tested; database migrations and deletion operations have regression tests.
- SSID filters and radio matching operate locally. Calculated estimates are not fed back as ground-truth measurements.
- Diagnostic logs redact recognized labelled radio/device identifiers and round recognized coordinate values. Arbitrary unlabelled free text cannot be reliably classified.
- Replay v2 uses relative time/track coordinates and per-recording opaque radio IDs; it can contain IMU and anonymized radio snapshots, but they do not currently affect the live risk score.
- BeaconDB read-only geolocation is disabled by default and requires its separate lookup switch. When enabled, the `/v1/geolocate` request may contain current supported Wi-Fi BSSID/RSSI, eligible public BLE MAC/RSSI and supported cellular identifiers/signal levels; the service also sees the device IP. SSIDs, Bluetooth names/payloads, GPS coordinates, scan history and local observations are not sent. The contribution endpoint `/v2/geosubmit` is not allowlisted and is never called. The geolocate POST is a lookup request, not an upload of survey records.
- OpenCellID read-only `/cell/get` receives only current supported cell identifiers (MCC/MNC/LAC or TAC/full Cell ID/RAT), the user API key and the device IP. RSSI, Wi-Fi/BSSID, BLE, GPS coordinates and observation history are not included. Calls require the separate OpenCellID switch and are bounded by the local request budget; its contribution/upload endpoint is not used.
- MapLibre requests HTTPS GET styles/tiles only from `tiles.openfreemap.org`; that provider sees the device IP and requested map tiles/area, but radio identifiers and positioning fixes are not attached.
- The bundled airport-exclusion catalogue contains 29,308 airport/landing-strip coordinates and is read locally. Within the configurable default 2 km buffer, radio matches and writes are excluded. Airport proximity contributes only a weak 10-point heuristic and never asserts `ZONE_RISK` by itself. If the offline catalogue is missing or more than 30 days old, the app can refresh it with a single-purpose HTTPS GET from `davidmegginson.github.io` only after the dedicated consent switch is enabled. The request fetches a public static CSV and contains no device coordinates, radio identifiers or observations; the server sees the device IP. Individual ICAO/IATA codes can be excluded by the user.
- `NetworkGate` allowlists these read-only endpoints, the exact airport-catalogue GET and MapLibre resources, rejects cleartext, arbitrary hosts and all other methods/paths. Android network security configuration also disables cleartext. Wi-Fi TCP to a user-selected private OBD adapter is a local peripheral connection, not an Internet service.
- WiGLE file import/export is local; the app does not call the WiGLE API or upload a selected database. Explicit WiGLE CSV/SQLite exports contain real BSSID/MAC, SSID, available coordinates and observation times; the export UI warns before the user selects a destination.
- Complete SQLite backups are a separate deliberate export and can contain the full local radio database, exact coordinates, source provenance and detailed history. A third-party destination may synchronize the saved file even though Clear GPS does not upload it.

## Known limitations and residual risk

Clear GPS cannot promise protection against a phase-coherent spoofer or synchronized record-and-replay. Multipath, weak signal, urban canyon, tunnel or indoor reception can cause poor measurements without an attack. Detector precision/recall and false alarms require a labelled replay set and device-specific measurements; CI alone cannot establish those real-world metrics.

Android provider behavior, sensor availability, battery behavior in Doze and the full publication path need manual checks on real devices. Follow [`docs/testing/device-validation.md`](docs/testing/device-validation.md); do not treat an unchecked item as completed.

## Testing boundary

Use synthetic data and recorded logs for detector tests. Do not create RF transmissions to simulate spoofing or jamming in normal environments. Mock location does not alter raw GNSS measurements and therefore cannot, by itself, validate GNSS-spoofing detection.
