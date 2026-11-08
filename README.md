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

## Local data model

Room database `routinepilot.db` (schema version 2). Every table uses UUID text primary keys, `createdAt`, `modifiedAt` (local modification time), and `deletedAt` (tombstone). Deletes are soft: reads filter `deletedAt IS NULL`. Hard purge is reserved for future sync cleanup.

| Table | Purpose | Relationships |
| --- | --- | --- |
| `routines` | Routines with `position` ordering | Parent of steps and occurrences |
| `routine_steps` | Ordered steps of a routine | Cascade from routine; restricted from hard delete while events reference it |
| `tasks` | Standalone tasks, optionally linked to a routine | Routine delete sets link to null |
| `scheduled_occurrences` | Planned or completed routine runs | Cascade from routine |
| `execution_sessions` | Runs of a routine | Cascade from routine; optional occurrence (set null) |
| `step_completion_events` | Step completions within a session | Cascade from session; restricted by step |
| `user_preferences` | Named preference values, unique by name | None |

Device-only UI settings stay in DataStore. Preferences that must sync later belong in `user_preferences`.

Schema changes need a `Migration` in `infrastructure/database/Migrations.kt`. Room exports each version to `app/schemas/`; commit those files.

## Background timing, notifications, and recovery

The run is owned by `RoutineRunCoordinator`, a process-wide singleton, and not by the screen. Timing uses the monotonic clock (`SystemClock.elapsedRealtime`), so a late tick still lands on the right step.

- **Backgrounded:** while a run is RUNNING, `RoutineRunService` runs as a foreground service with a chronometer notification showing the current step and countdown, with Pause and Skip actions. The service is started only from a foreground user action, and it stops as soon as the run pauses or ends. Its type is `specialUse`, because a user-started guided timer matches no standard foreground-service type. The `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` in the manifest explains it.
- **Step alerts:** each completed step, and a finished routine, posts a notification. Alerts are skipped when the user has not allowed notifications. The run itself does not depend on the permission.
- **Notification permission:** `POST_NOTIFICATIONS` is requested the first time a run starts on Android 13 and later. If it is refused, the run starts anyway and the run screen says that alerts are off.
- **Returning to the app:** leaving or re-entering the run screen never changes the timer. Entering the foreground settles any step that ran out while the screen was away. Leaving the foreground writes a checkpoint.
- **Process death:** the run is checkpointed about once a second while running, so at most about one second is lost. The next time the app opens, the run comes back paused at that checkpoint, and Home shows a Resume card. The user decides when time counts again.
- **Device reboot:** `RunBootReceiver` pauses an interrupted run at its last checkpoint, schedules a reminder, and posts a notification. A reboot resets the monotonic clock, so timing can't be continued across it.
- **Paused runs:** a WorkManager job posts a "still paused" reminder after 15 minutes. It is deferrable on purpose, and it survives process death and reboot. No exact alarm is used, so no exact-alarm permission is requested.
- **Notification actions:** Pause, Skip, and Finish act on the run directly. Resume opens the run and resumes it, because Android allows a foreground start only from the foreground.
- **Refused background start:** if the platform refuses the foreground start, the run continues in-process and the run screen says that background timing is unavailable.

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

Routine management: the home screen lists routines and offers duplicate and delete (with undo). The editor creates and renames routines, adds, removes (with undo), reorders, and describes steps, sets step durations in minutes, shows the total estimated duration, and validates input before saving. Deleting a routine from the editor asks for confirmation.

Running a routine: the run screen is full screen and walks through the steps with a countdown for each timed step and a total time left. Start, pause, resume, skip, previous, finish, and cancel are supported. Timed steps advance automatically when their countdown reaches zero. Steps without a duration have no countdown and wait for Skip or Finish. The timer is a pure state machine in `domain/execution/RoutineTimer.kt`. It measures time with a monotonic clock and never counts ticks. Each step, session, and completion is written to Room, and the in-progress run is saved in DataStore. The run keeps going when the app is backgrounded, and it is restored correctly afterwards (see below).

Decisions: step reordering uses up and down buttons rather than drag and drop. Step removal and routine deletion undo only from the snackbar that follows them; edits stay in the draft until Save. Duration is stored in seconds.
