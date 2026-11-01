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
- BeaconDB automatic refinement of existing local Wi-Fi points is a separate optional lookup path. While monitoring is active it sends eligible existing BSSID identifiers, RSSI, sample age and frequency to the read-only `/v1/geolocate` endpoint only after the BeaconDB network-consent switch and the separate auto-refinement switch are enabled; it also requires validated Wi-Fi, at least 60% battery and battery temperature no higher than 37 °C. SSID is read locally only to reject unknown/hidden and `_nomap` networks and is never serialized. The server sees the device IP. No GPS coordinates, local observation history or database records are uploaded. The result is stored as a low-trust, separately deletable `auto_refined` batch, outside `radio_observations` and detector training; manual edits are excluded from refinement.
- OpenCellID read-only `/cell/get` receives supported cell identifiers (MCC/MNC/LAC or TAC/full Cell ID/RAT), the user API key and the device IP. RSSI, Wi-Fi/BSSID, BLE, GPS coordinates and observation history are not included. Current-cell lookups require the separate OpenCellID position-source switch, network switch, stored API key and precise-location permission; the current-cell path retains its 3-cell/15-minute/100-attempts-per-UTC-day safeguards. A separate local-database refinement switch is off by default and requires active monitoring plus the enabled OpenCellID source/network consent and stored key; it is capped at 10 attempts per UTC day and at least 2 h 30 min between attempts. Returned coordinates are stored in independently deletable low-trust `auto_refined` batches, not as observations/training data; manually edited coordinates are not overwritten. The contribution/upload endpoint is not used.
- The dedicated manual-refinement tab queries only records already held in the local database. BeaconDB receives eligible BSSID/RSSI/age/frequency from one coherent recent scan group (SSID is local-only); it can return an estimated scan-area location that is associated with the group's BSSIDs, not exact per-router placement. The manual path requires separate BeaconDB consent and validated Wi-Fi. It applies a local minimum one-minute interval and a maximum of 10 requests per run; these are app safeguards, not a claim about a published BeaconDB quota. OpenCellID receives one existing local cell identity and the API key per read-only `/cell/get` request; the manual path has user-selected spacing (1–15 minutes), but manual and background local-database refinement share the 10-attempt UTC-day cap. Both flows validate zones, keep results in removable low-trust `radio_auto_refinements`, exclude manually edited coordinates and keep results outside measured observations/training. A bounded local event log stores provider/status summaries only, without BSSID/cell identifiers or API keys. Device IP is visible to the selected provider for either lookup.
- Optional passive radio capture listens to Android `PASSIVE_PROVIDER` only after the user enables it. It receives fresh, non-mock GPS fixes initiated by other applications (GPS provider, at most 10 m accuracy, outside spoofing/airport exclusion zones); Clear GPS does not request its own GPS fix for this capture path. The resulting radio observations and coordinates stay in the local database and are managed as separate daily batches; this path makes no network request.
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
