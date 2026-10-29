# Contributing to Clear GPS

Clear GPS is experimental and hardware-dependent. Reproducible evidence is more useful than an unverified accuracy claim.

## Before changing code
- Read README.md, docs/ARCHITECTURE.md and the relevant docs/spec documents.
- Keep a change focused and include regression tests.
- Do not change licensing, add network uploads or weaken privacy defaults without explicit owner approval.
- Keep algorithms independent of Android APIs where practical.
- Treat imports, radio inputs, OBD data and server responses as untrusted.

## Code quality
Use explicit units and monotonic timestamps. Keep parsing, retries and storage bounded. Represent sensor availability and data quality separately. Do not silently swallow parse failures. Missing hardware is not an anomaly. Never claim universal device support based only on compilation.

## Validation
Run relevant unit tests and the Android debug build where the environment supports it. For device-specific changes, include Android version, device class, test steps and redacted results. Do not attach raw routes, exact coordinates, SSID/BSSID, cell IDs, Bluetooth addresses, secrets or unredacted logs to public issues.

Pull requests should describe the problem, implementation, test commands, known limitations and any change to permissions, network behaviour, retention or user-facing claims. Mark measurement-dependent thresholds as open.
