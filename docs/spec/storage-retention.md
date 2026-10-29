# Storage, retention and migrations

Status: proposed policy; numeric limits need owner review and measurement.

Every stored data category needs a maximum count or age, cleanup trigger and user-visible clear operation. Do not store unbounded sensor streams or raw payloads after extracting the necessary features.

Increment the schema version for every database change and provide explicit migrations tested against realistic prior schemas. Preserve source provenance. Imports are bounded, validated, transactional and idempotent. Deleting a source must have documented semantics for shared observations. Add indexes based on query patterns and migration evidence. SQLCipher remains an optional future decision; do not imply current SQLite is encrypted at rest.

Provide storage diagnostics for the database, scan history, imports, DEM/map packs and local logs. Explain which zones/settings/observations are removed before clear-data operations. Android backup remains disabled unless reviewed. Restore must validate schema version and resource limits, and apply changes through a temporary or transactional target to avoid partial corruption.
