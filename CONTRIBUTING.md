# Contributing to Clear GPS

Clear GPS is an experimental Android project. Please keep changes aligned with the roadmap and the contracts in `docs/spec/`; a specification describes intended behavior and does not by itself prove that the behavior is implemented.

## Before changing code

1. Read the relevant document under `docs/spec/` and check the corresponding implementation and tests.
2. Prefer a small change with a clear failure mode. An unsupported sensor or unavailable transport should degrade gracefully rather than disable unrelated sources.
3. For database/schema or import/export changes, update/add migration or round-trip tests. Preserve source provenance, keep unknown coordinates unknown, and do not treat a calculated radio estimate as ground truth.
4. Update `CHANGELOG.md` in the same commit as every code, specification, build, CI or repository-structure change. Include the reason, paths, behavior, checks and known limitations. Report tests as passed only after an actual result.
5. Keep the implementation and both README language sections consistent with behavior that is present in code.

## Build and tests

Use JDK 17 and the checked-in Gradle Wrapper. From the repository root:

Linux/macOS:

```sh
./gradlew --no-daemon --stacktrace :core-detect:test testDebugUnitTest assembleDebug
```

Windows PowerShell:

```powershell
.\gradlew.bat --no-daemon --stacktrace :core-detect:test testDebugUnitTest assembleDebug
```

For the Windows bootstrap/build path, see `windows-build/build-apk.ps1` and `build-apk.bat`. Do not replace the checked-in wrapper with a globally installed Gradle in CI. The workflow verifies the wrapper JAR checksum and tests its Windows argument forwarding.

Use pure JVM unit tests for deterministic policies/codecs where possible, and Robolectric tests for Android/SQLite behavior. Keep fixture inputs synthetic and bounded. Include a test for the failure case as well as the normal case—for example malformed imports, unknown coordinates, repeated imports, or a legacy schema migration.

## Privacy and safety requirements

- Do not commit real SSIDs, BSSIDs, cell identifiers, BLE addresses, precise locations, private logs, replay files, API tokens, credentials or user backups.
- Use anonymized/fake identifiers and coordinates in fixtures. Never paste raw radio data or precise user location into test output.
- Keep BeaconDB behavior cell-only and explicitly opt-in unless its privacy contract is deliberately reviewed and updated. Do not add contribution uploads or background lookups as a side effect.
- Treat imported files as untrusted input: maintain size bounds, validate schema/field ranges, fail before mutating the target database when validation fails, and keep existing deletion-undo state usable after a rejected import.
- GNSS attack tests should use synthetic scenarios or recorded replay files. Do not create RF transmissions to imitate spoofing or jamming in ordinary environments.

## Review checklist

Before marking a change complete, check:

- [ ] relevant unit/integration tests pass and the current CI run is linked in the changelog;
- [ ] no unrelated branch or generated file was changed;
- [ ] user-visible claims match implementation and the English/Russian README sections agree;
- [ ] network requests and data exports are accurately documented;
- [ ] unresolved real-device checks remain marked as unverified rather than inferred from CI.

There is no requirement in this guide to create a pull request; use the repository workflow agreed by its maintainers.
