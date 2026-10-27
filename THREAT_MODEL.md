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
- BeaconDB is disabled by default and the user must start an explicit cell-only lookup. The service receives the supported cell identifiers/signal levels and the device IP. The map provider can see the IP and requested map area.
- WiGLE file import/export is local; the app does not call the WiGLE API or upload a selected database.
- Complete SQLite backups are a deliberate export and can contain exact radio IDs, coordinates and history.

## Known limitations and residual risk

Clear GPS cannot promise protection against a phase-coherent spoofer or synchronized record-and-replay. Multipath, weak signal, urban canyon, tunnel or indoor reception can cause poor measurements without an attack. Detector precision/recall and false alarms require a labelled replay set and device-specific measurements; CI alone cannot establish those real-world metrics.

Android provider behavior, sensor availability, battery behavior in Doze and the full publication path need manual checks on real devices. Follow [`docs/testing/device-validation.md`](docs/testing/device-validation.md); do not treat an unchecked item as completed.

## Testing boundary

Use synthetic data and recorded logs for detector tests. Do not create RF transmissions to simulate spoofing or jamming in normal environments. Mock location does not alter raw GNSS measurements and therefore cannot, by itself, validate GNSS-spoofing detection.
