# RoutinePilot: project boundaries and working agreement

- Work only in `/Users/mk-rune/test_projects/06-routinepilot`. This is one of fourteen independent repositories.
- Do not read, modify, clean up, or run commands in sibling repositories or the user's configuration.
- Work autonomously: choose sensible defaults and document assumptions; do not ask questions or delegate.
- Never install anything globally or use sudo, Homebrew, launchd, host credentials, or host Docker sockets.
- Dependencies, toolchains, caches, browser binaries, SDKs, temporary files and test data stay in this repository. Preserve the supplied project-local environment. Python packages go in `.venv`.
- `.agent-local/` is ignored, project-local build/runtime state. `.opencode/` is managed orchestration configuration; do not change or remove it or bypass the sandbox.
- Use existing system tools read-only, or install pinned tools under this repository. Do not change shell profiles, user settings, Keychain, or global Git settings.
- Bind test servers to loopback; use ports 21500–21599. Limit build/test workers to two unless a required benchmark explicitly needs more.
- Docker-dependent integration tests must remain reproducible but be reported as unverified here if they require access outside this repository. Do not bypass this boundary.
- Do not touch attached hardware, enroll services, flash firmware, install agents, or alter OS settings. Supply manual checklists for such steps.
- Implement only the current numbered prompt, preserving prior behavior. Run appropriate checks, fix failures, and report genuine limitations without fabricating test or benchmark results.
- Before finishing every numbered turn: stop its servers/watchers, remove scratch outputs, update relevant documentation, and make a local conventional-commit Git commit. Do not push.
- End each turn with a short summary of changes, checks actually run, remaining limitations, and commit hash. The orchestrator will provide the next prompt only after this turn ends.

Extend this file with the project's language-specific architecture and verification commands while retaining these boundaries.

## Android architecture

- Native Android app in Kotlin with Jetpack Compose, Material 3, Room, DataStore, Hilt, and Kotlin Coroutines/Flow. Single `app` module; packages `presentation`, `domain`, `data`, `infrastructure` under `com.routinepilot`.
- Dependency direction: `presentation` → `domain` ← `data` → `infrastructure`. `domain` has no Android imports. Hilt bindings live in `infrastructure/di`.
- Versions are pinned in `gradle/libs.versions.toml`. Do not add dependencies for a few lines of code.

## Backend architecture (`backend/`)

A separately deployable Kotlin service: Ktor 3.0.3 on Kotlin 2.0.21, PostgreSQL 16 through JDBC and HikariCP. It has its own Gradle build (`backend/settings.gradle.kts`) and version catalog, so the Android build does not depend on it. Packages under `com.routinepilot.backend`: `api` (routes, DTOs, plugins), `service` (rules that use transactions), `persistence` (SQL), `domain` (pure rules, validation, and sync decisions), `security` (hashing, tokens, rate limits), `db` (JDBC helpers and migrations), `config`. Dependencies point inward, and `domain` imports no Ktor or JDBC types. The sync contract lives in `backend/README.md`.

Backend verification commands, run from `backend/`:

- `gradle test` — unit, database, and API tests. The database and API tests need a Docker daemon (Testcontainers).
- `gradle installDist` — distribution build used by the Dockerfile.
- `docker compose up --build` — local PostgreSQL and API on loopback.

The backend is not compiled in every environment. Record it as unverified when Gradle, a JDK, or Docker is unavailable.

## Engineering rules for this project

- Make small, focused changes. Each change touches only what it must and includes tests for new behavior.
- Keep ViewModels lifecycle-safe: expose `StateFlow` built with `stateIn`/`WhileSubscribed`, collect in Compose with `collectAsStateWithLifecycle`, and never hold Activity or View references.
- Offline-first: Room is the source of truth. UI reads from Flow; no feature may require network to render local data.
- Do not commit generated output, caches, `local.properties`, keystores, or scratch files. Delete temporary build output you create.

## Verification commands

Run from the repository root with a JDK 17 and an Android SDK available (see README). Until the Gradle wrapper is committed, use a local Gradle 8.9 (`gradle wrapper --gradle-version 8.9` generates it).

- `./gradlew spotlessCheck detekt` — formatting and static analysis
- `./gradlew testDebugUnitTest` — JVM unit tests
- `./gradlew assembleDebug` — debug build
- `./gradlew connectedDebugAndroidTest` — Compose UI tests on a device or emulator
- Limit Gradle to two workers (`org.gradle.workers.max=2` in `gradle.properties`).
