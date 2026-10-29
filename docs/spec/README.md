# Clear GPS — Stage 0 specification

Status: draft for review. Product basis: Clear GPS project plan dated 25 October 2026. Open owner decisions and values requiring measurement are not silently filled in.

## Documents

- position-contract.md — coordinate reference, timestamps, fix fields and publication policy.
- radio-db-format.md — observation schema, provenance and duplicate handling.
- trust-model.md — score semantics, evidence groups, state transitions and calibration.
- sources-matrix.md — source capabilities, limitations and failure isolation.
- quality-criteria.md — detector, position, battery and stability evaluation.
- threat-model.md — assets, threats, non-goals and known limits.
- privacy.md — local storage, network consent, diagnostics and export.
- licenses.md — current license, dependency review and external datasets.
- battery-budget.md — Eco/Normal/Max profiles and measurement protocol.
- decision-register.md — decisions reserved for the owner or field measurements.
- context-classifier.md — conservative interpretation of poor GNSS environments.
- replay-format.md — deterministic timestamped replay records.
- storage-retention.md — bounded storage, cleanup and migrations.

## Normative principles

1. The detector uses raw GNSS-derived features and independent checks, never its own mock output.
2. An unavailable sensor is neither attack evidence nor evidence of safety.
3. Every observation has a monotonic timestamp and source provenance.
4. One source failure must not stop other sources.
5. Online lookup is opt-in, disabled by default and explained at consent.
6. If no trusted current position exists, mock publication stops.
7. Until calibration on labelled replay data, use “risk index/level”, not “probability”.
8. TBD targets require measured evidence before becoming acceptance criteria.
