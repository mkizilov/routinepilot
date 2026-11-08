# RoutinePilot

Native Android app (Kotlin, Jetpack Compose, Material 3) for building and tracking routines. Offline-first: local storage is the source of truth.

## Stack

- Modern Android Gradle Plugin 8.7, Kotlin 2.0, Kotlin Coroutines and Flow
- Jetpack Compose with Material 3 and Compose Navigation
- Room (local database) and DataStore preferences
- Hilt for dependency injection
- JUnit for unit tests, Compose UI testing for instrumented tests
- detekt (static analysis) and Spotless with ktlint (formatting)

## Architecture

Packages under `app/src/main/java/com/routinepilot`:

| Layer | Package | Contents |
| --- | --- | --- |
| Presentation | `presentation` | Compose screens, navigation, theme, ViewModels |
| Domain | `domain` | Models and repository interfaces; no Android dependencies |
| Data | `data` | Repository implementations mapping local data to domain models |
| Infrastructure | `infrastructure` | Room database, DAOs, DataStore, Hilt modules |

Dependencies point inward: presentation depends on domain; data and infrastructure implement domain interfaces. Hilt bindings live in `infrastructure/di`.

## Requirements

- JDK 17
- Android SDK with platform 35 and build-tools (Android Studio installs these, or use `sdkmanager`)
- Gradle 8.9 (only needed once, to generate the Gradle wrapper; see below)

## Build and verify

The Gradle wrapper jar is not checked in yet. Generate it once with a local Gradle 8.9 installation:

```sh
gradle wrapper --gradle-version 8.9
```

Then use `./gradlew` for all commands:

```sh
./gradlew assembleDebug            # build the debug APK
./gradlew testDebugUnitTest        # JVM unit tests
./gradlew connectedDebugAndroidTest  # Compose UI tests (emulator or device)
./gradlew detekt                   # static analysis
./gradlew spotlessCheck            # formatting check
./gradlew spotlessApply            # apply formatting
./gradlew check                    # all of the above checks
```

CI (`.github/workflows/android.yml`) runs format, detekt, unit tests, and a debug build on every push and pull request, and runs the Compose UI tests on an API 34 emulator. It currently calls `gradle` directly through `gradle/actions/setup-gradle`; switch to `./gradlew` once the wrapper is committed.

## Layout

- `app/src/main/java/com/routinepilot/` application code, grouped by layer
- `app/src/test/` JVM unit tests
- `app/src/androidTest/` Compose UI tests
- `config/detekt/detekt.yml` detekt rule overrides
- `.github/workflows/android.yml` CI

## Status

Initial scaffold: a single home screen that lists routines from Room (empty state for now), navigation through Compose NavHost, and DataStore wired through Hilt for future preferences.
