# Development guide

## Requirements

- JDK 17 for the current Android/Gradle configuration.
- Android SDK platform 36 and the build tools resolved by the Windows bootstrap.
- Gradle 8.14.6 as pinned in the wrapper properties, or the version provided by CI.
- Network access for first-time dependency resolution.

The preparation environment does not have Gradle or the Android SDK available, so an APK build is not claimed as locally verified. CI is configured to run the app tests/build and pure JVM core-detect tests.

## Build and test

On Windows, run build-apk.bat from the repository root. The existing bootstrap validates Java/SDK configuration and stores a detailed log under the user's local ClearGPS log directory.

On Linux CI, the existing workflow runs the core-detect test task, app unit tests and debug APK assembly. A successful compilation does not prove live GNSS, radio scan, battery, or mock-provider behaviour.

## Contribution workflow

1. Start with a focused logical change and add a regression test.
2. Keep algorithms independent of Android framework APIs when practical.
3. Treat imported files, radio inputs, OBD bytes and server responses as untrusted.
4. Use bounded retries, parsing and storage.
5. Report sensor capability and data quality explicitly.
6. Document changes to permissions, network behaviour, retention or user-facing claims.
7. Mark thresholds requiring field measurement as TBD instead of guessing.
8. Never attach personal route logs, raw radio identifiers or exact locations to public issues.

## Dependency hygiene

Pin explicit versions, review direct and transitive licenses, and check maintenance status before introducing a dependency. Avoid adding a dependency to core-detect when standard Kotlin/JDK code can satisfy the requirement.
