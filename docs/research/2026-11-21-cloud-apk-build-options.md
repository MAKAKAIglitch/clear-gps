# Cloud APK builds: Superpowers and Railway

- **Date:** 2026-11-21
- **Scope:** whether the installed Superpowers and Railway ChatGPT integrations can produce a downloadable APK for ClearGPS without a personal computer or GitHub Actions.
- **Repository:** [MAKAKAIglitch/clear-gps](https://github.com/MAKAKAIglitch/clear-gps)
- **Build branch:** `feature/navigation-ui`
- **Repository build configuration reviewed:** `app/build.gradle.kts`, `build.gradle.kts`, `settings.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`, `gradle.properties`.
- **Research type:** tool-capability comparison, official provider documentation review and public-template repository inspection.

## 1. Conclusion

**Railway can supply remote Linux compute for a native Android Gradle build. Superpowers does not provide that compute by itself.**

The best Railway route is an ephemeral **Railway Sandbox**, not a normal always-running web service. A sandbox can clone the feature branch, install/use the required Android SDK and run the Gradle wrapper, then make the resulting APK retrievable before the sandbox is destroyed.

Superpowers is still useful as the engineering method around the task (plan, tests, systematic debugging and review), but a build runner and an artifact-transfer mechanism are separate requirements.

## 2. Superpowers capability

The Superpowers plugin listing describes an agentic software-development methodology: brainstorming, implementation planning, TDD, systematic debugging, parallel work, code review and finishing a branch. It does not advertise a hosted Linux runner, Android SDK image, remote shell, APK artifact store or binary download endpoint.

Sources:

- [Superpowers plugin listing](https://chatgpt.com/plugins/plugins~Plugin_60aea7460bd4819199fd97a9553a5e12)
- [OpenAI plugins repository — Superpowers listing](https://github.com/openai/plugins/blob/main/plugins/superpowers/README.md)

**Assessment:** use it to improve the development/build workflow, not as the actual machine that compiles the APK.

## 3. Railway capability

Railway's official Sandboxes documentation describes isolated on-demand Linux VMs for coding agents, dependency installation, tests, builds and remote command execution. The docs describe three access paths: TypeScript SDK, CLI and Railway dashboard. They state that Sandboxes are available on every Railway plan.

Sources:

- [Railway Sandboxes documentation](https://docs.railway.com/sandboxes)
- [Railway sandbox quick-start and CLI reference](https://docs.railway.com/cli/sandbox)
- [Railway sandbox overview](https://railway.com/sandboxes)

A sandbox is preferable to a normal Railway deployment for a one-off APK build because the task is finite and does not need a permanently running web service. The APK still needs an explicit retrieval path before teardown; a temporary authenticated download endpoint or an object-storage upload are possible designs. The exact transfer method should be chosen before starting the build.

### Important connector limitation

At the time of this review, the Railway actions exposed to this ChatGPT session include project/service/deployment management and `fork_exec` for an already-created environment fork. They do **not** expose a direct Sandbox create/exec/destroy action. That means the official Railway platform supports the required workflow, but this particular ChatGPT connector cannot currently provision a fresh sandbox directly. The Railway dashboard is the available no-PC route for the user to create/manage it.

This is a limitation of the current connected tool surface, not a statement that Railway itself lacks sandbox support.

## 4. ClearGPS build requirements

The current branch is a native Kotlin/Android Gradle project.

Verified settings:

- Gradle Wrapper distribution: `8.14.6`, with a pinned SHA-256 in `gradle/wrapper/gradle-wrapper.properties`.
- Android Gradle Plugin: `8.11.1`.
- Kotlin Android and Compose plugins: `2.2.21`.
- Java source/target and Kotlin JVM target: 17.
- Android `compileSdk = 36`, `minSdk = 26`, `targetSdk = 35`.
- The APK task is `:app:assembleDebug`.

Expected Gradle invocation:

```sh
./gradlew --no-daemon --stacktrace :app:assembleDebug
```

The build machine needs network access to retrieve the pinned Gradle distribution and Maven/Google Maven dependencies, plus Android platform/build-tools for API 36. It does not need the user's personal computer.

The debug variant has an `.debug` application ID suffix. It is suitable for testing as a separate installation; it is not the release-signed production APK.

## 5. A third-party Railway Android terminal template was inspected

Railway's template catalogue currently lists [Android SDK Terminal](https://railway.com/deploy/android-sdk-terminal), based on [Amritasha/android-railway](https://github.com/Amritasha/android-railway).

GitHub repository metadata at review time:

- Stars: **0**
- Forks: **1**
- Last push: **2026-11-06**
- Default branch: `main`

The reviewed Dockerfile installs Ubuntu 24.04, Java 17, Android SDK platform/build-tools 34.0.0 and a browser terminal (`ttyd`). The README says the web terminal is protected by username/password environment variables and persistent storage is mounted at `/root`.

Concerns for ClearGPS:

- It provisions Android API 34, whereas this project currently compiles against API 36, so extra SDK packages must be installed.
- The terminal service is an internet-facing shell; it must be protected with a strong password and handled as a sensitive administrative endpoint.
- The Dockerfile downloads the `ttyd` binary from a GitHub release URL without a recorded checksum in the file reviewed here.
- The repository has very low adoption and no licence field was reported by GitHub at review time.

**Assessment:** do not use this template as the default trusted build route. An official Railway Sandbox with a minimal, transparent setup is preferable. If a persistent browser terminal is ever needed, review and harden its image separately.

## 6. Recommended workflow

1. Create a temporary Railway Sandbox from the Railway dashboard, in a project/environment owned by the user.
2. Clone only `MAKAKAIglitch/clear-gps`, branch `feature/navigation-ui`.
3. Use JDK 17 and install Android SDK platform/build-tools 36 required by the repository.
4. Run the Gradle wrapper task `:app:assembleDebug`.
5. Inspect the Gradle exit code and verify that the APK file exists and has nonzero size.
6. Transfer the APK through a short-lived authenticated download URL or private object storage. Do not expose a general shell endpoint just to retrieve one file.
7. Download the APK on the phone, then destroy the temporary Sandbox and remove any temporary public download endpoint.

No GitHub Actions workflow is required. No PR or merge is required.

## 7. Verification limits

- No Railway Sandbox was created during this research.
- No Gradle build was executed.
- No APK has been produced or downloaded.
- Railway resource usage and any account-specific quotas must be checked in the user's Railway workspace before creating compute.
- The research confirms platform capability and repository requirements, not successful compilation or runtime behaviour.

