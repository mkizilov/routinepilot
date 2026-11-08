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

Room database `routinepilot.db` (schema version 8). Every table uses UUID text primary keys, `createdAt`, `modifiedAt` (local modification time), and `deletedAt` (tombstone). Deletes are soft: reads filter `deletedAt IS NULL`. Hard purge is reserved for future sync cleanup.

| Table | Purpose | Relationships |
| --- | --- | --- |
| `routines` | Routines with `position` ordering | Parent of steps and occurrences |
| `routine_steps` | Ordered steps of a routine | Cascade from routine; restricted from hard delete while events reference it |
| `tasks` | Standalone tasks, optionally linked to a routine | Routine delete sets link to null |
| `scheduled_occurrences` | Planned or completed routine runs | Cascade from routine |
| `execution_sessions` | Runs of a routine | Cascade from routine; optional occurrence (set null) |
| `step_completion_events` | Step completions within a session | Cascade from session; restricted by step |
| `user_preferences` | Named preference values, unique by name | None |
| `calendar_links` | Device-local link from each exported schedule to its calendar event | None; rows are deleted outright, not tombstoned |

Device-only UI settings stay in DataStore, and so do calendar export settings, because calendar ids belong to one device. Preferences that must sync later belong in `user_preferences`.

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

## Recurring schedules and the daily agenda

Routines and tasks can repeat on a rule: daily, on selected weekdays, or every N days (1 to 365). Each rule has a local time and a start date. The agenda (the Today screen) groups the day's items into **Due now** (within the last hour), **Upcoming**, and **Earlier today** (done, skipped, and missed items). It refreshes every minute.

- **Generation** is pure Kotlin in `domain/schedule/ScheduleGenerator.kt`. It plans a window from yesterday to 14 days ahead. Each occurrence is identified by its original instant, and a unique index on `(scheduleId, originalScheduledAt)` enforces that a slot exists at most once. Generation is idempotent, so repeated runs and app restarts create nothing twice. A slot the user deleted stays deleted.
- **Edits** remove only pending items at or after now that the new rule no longer produces. Completed, skipped, and past items are kept as history. Editing a rule never creates items before the edit.
- **Time zones and daylight saving:** the local time is kept on the wall clock. A time inside a spring-forward gap is shifted forward by the gap, so 02:30 runs at 03:30. A time in the autumn overlap runs once, at the earlier instant. A device zone change re-anchors pending items through the same remove-and-insert path, and `DeviceClock` reads the zone on every call.
- **Occurrence actions:** Done, Skip, and +15 min. Postponing keeps the original slot, so the rule still cannot regenerate it. Only pending items can change.
- **Maintenance:** a deferrable WorkManager job runs every 12 hours (unique, KEEP) to roll the window forward and re-anchor after travel. The agenda also generates on open.

Known limits:
- A moved (rescheduled) pending item is identified by its original slot. If a later rule edit no longer produces that slot, the item is removed even though the user moved it.
- Occurrences migrated from schema version 3 get their local date computed in UTC, not the device zone. Their scheduled time is unchanged.
- The schema 3 to 4 migration is exercised only by the full-chain open in `MigrationTest`. A dedicated `MigrationTestHelper` test needs the exported schema JSON from a first build, and is not yet written.

## Progress and statistics

The Progress screen (opened from the agenda) shows today's completion, the last 7 days, weekly and monthly trends, a five-week calendar, routine consistency, and the current and longest streak. Every number comes from one set of definitions in `domain/statistics/StatisticsCalculator.kt`.

- **Completion %** for a day: completed scheduled items divided by completed plus missed. Skipped items and items not yet due are left out. Blank when nothing was due.
- **Completed routine session:** a session that ended with Finish. Cancelled sessions are counted separately and never as completed.
- **Completed steps:** step completions that day, whatever happened to their session.
- **Focused time:** running time, excluding pauses, of sessions that ended that day, including cancelled ones (their focus was real).
- **Streak day:** a day counts when a routine finished or a scheduled item was completed. A day with only skipped items is excused: it neither adds to nor breaks the streak. A day with a missed item and nothing done breaks it. A cancelled session alone does not count. Today stays in progress until something is missed.
- **Consistency:** per routine over the last 30 days, completed divided by completed plus missed.
- **Linking:** starting a run links the oldest pending item of that routine due within the next hour. Finishing completes that item. Cancelling leaves it pending.
- **Updates:** statistics come from Room flows, so finishing a run or completing an item updates the screen with no refresh. A one-minute tick rolls the day over.

Limits:
- Sessions recorded before schema version 5 are marked UNRECORDED. They are not counted as finished or cancelled, and their focused time is unknown. Their dates are computed in UTC.
- Runs that are left open and never finished or cancelled are never counted, and their focus is not recorded.
- Streaks and trends look back at most 365 days, so a longer streak is shown as 365 days.
- The 4 to 5 migration is covered only by the full-chain open in `MigrationTest`, like the 3 to 4 migration.

## Home screen widgets

Three widgets, built with Jetpack Glance 1.2.0. Each one declares the same three sizes, and the layout picks the bucket the launcher gives it.

- **Today:** the next routines and tasks, due-now first. A tap opens the app.
- **Routine progress:** the running routine's step, minutes left in the step and in total, and controls. Pause, Skip, and Finish act on the run directly. Resume opens the run screen.
- **Quick start:** up to four routines. A tap opens the run screen and starts the routine through the same permission path as the Start button.

What the widgets show comes from the same sources as the app: Room for routines and the agenda, and the run checkpoint in DataStore. The widgets hold no copy of their own. Their rules are in `domain/widget/`, which is pure Kotlin and tested on the JVM.

Updates and process death:
- While the app process is alive, widgets update when data changes, when a run changes step or status, once a minute while a routine is running, and every fifteen minutes for today's list.
- If the process is gone, a periodic WorkManager job refreshes every thirty minutes at most. Android may delay it.
- A run that was running when its process stopped shows "needs attention" rather than a countdown that is no longer true. Opening the app settles it.
- A run whose routine was deleted shows that it was deleted. The app ends such a run the next time it starts.
- A widget button for pause, skip, or finish goes through the app's coordinator, which restores a stored run first.

Security:
- The three widget providers are `exported="false"`. The system's AppWidget service sends their update broadcasts, and widget taps go through Glance's own non-exported receiver.
- A widget action carries only a command word (`pause`, `skip`, or `finish`). It never names a routine, so a widget cannot act on anything but the current run.
- Widget intents to the activity are explicit, and the routine id is parsed as a UUID. Anything malformed is ignored.
- Glance creates the PendingIntents for button taps. I have not checked the flags it uses in this environment. Check them on the first device build.

Supported Android versions:
- The app's minimum is Android 8.0 (API 26). Glance 1.2.0 supports API 23 and up.
- On Android 12 and later, responsive sizing uses the launcher's native multi-size support. On Android 8 to 11, Glance renders each declared size on its own.

Limits:
- **Countdowns are to the minute.** Launchers cannot tick a widget every second. The figure is current when it is drawn, then refreshed each minute while the app process is alive.
- **Quick start does not start a timer in the background.** Android restricts background starts, so the tap opens the app.
- **Launchers vary.** Some ignore resizing or fix widget sizes. Some do not show a preview in the picker. This app does not provide preview images.
- **Colours are fixed.** Widgets do not follow Material You in this version.
- **Not verified here.** No widget was rendered on a launcher. The Glance layouts, the receivers, and the PendingIntents have not been compiled or run in this environment. The state logic is covered by JVM tests only.

## Calendar export

Optional and off until the user turns it on in **Schedules, then Calendar**. Each active schedule becomes one recurring event in a calendar the user picks. The rules are pure Kotlin in `domain/calendar/` and tested on the JVM. `data/calendar/CalendarRepositoryImpl.kt` keeps the settings in DataStore and the links in Room. `infrastructure/calendar/ProviderCalendarGateway.kt` is the only code that touches the Android Calendar Provider.

**Permissions.** Both are requested when the user turns export on, and the screen states why:
- `READ_CALENDAR` lists the calendars the user can write to, and finds this app's own events by their UID.
- `WRITE_CALENDAR` creates, updates, and deletes those events.

The app never reads other events. Refusing leaves export off, and nothing else in the app depends on it.

**What is exported.**
- A routine or task becomes one event titled with its name. A routine's description lists its steps, with each duration rounded up to whole minutes.
- A timed event starts at the schedule's time and uses the schedule's zone, so it keeps its wall-clock time across daylight-saving changes. Its length is the sum of the step durations, with 30 minutes for a routine that has none, and 15 minutes for a task. The length is kept between 5 minutes and 12 hours.
- The all-day option writes UTC-midnight events that last one day.
- A series starts on the day its rule was last saved, and keeps that start while the rule is unchanged. A re-save of an unchanged schedule on a later day does not move past events out of the calendar. Changing the time, the days, the start date, or the zone moves the start to the day of the last save. A zone change made when the device travels does not save the schedule, so the start stays on the last save day.

**Sync.**
- Sync runs when export is switched, when the destination or all-day setting changes, when a schedule, routine, or task changes (`CalendarSyncObserver`, with a 500 ms debounce), and when the calendar screen resumes. Syncs run one at a time.
- Each event has a stable UID, `routinepilot-schedule-<id>@routinepilot.local`, written to `UID_2445`. The link row records the event id. If a link is lost, the next sync adopts the event with the same UID instead of creating a second one.
- Stopping a schedule ends its series at the stop time, so past events stay. Stopping before the first occurrence removes the event.
- An event the user deletes in the calendar app is marked **Deleted in the calendar app** and is not recreated. **Export again** in the list creates a new one.
- Changing the destination deletes the events in the old calendar, after a confirmation.
- **Remove exported events and turn off** deletes every linked event and turns export off, after a confirmation. Turning export off alone keeps the events in the calendar.
- The **Open** button opens the event in the calendar app. If none can open it, the app says so.

**Permission revoked.** When access is missing, or is revoked during a sync, the sync changes no links and the screen reports that access is off. Events already written keep their UID, so the next sync adopts them rather than duplicating them. Removal does nothing without access, so the links stay for a later retry.

**Limits.**
- Edits made to an exported event in the calendar app are not read back. They are overwritten the next time RoutinePilot changes that event.
- The sync runs only while the app process is alive. Changes made while the app is closed are exported when it next starts.
- Access revoked outside the app is noticed at the next sync or when the screen resumes.
- The UID relies on the provider storing `UID_2445`. A provider that drops it can create a duplicate only after a lost link.
- The first build exports the Room schema for version 8 to `app/schemas/`. Those files are not in this checkout yet. Commit them after the first build.
- **Not verified here.** The environment had no JDK, Android SDK, Gradle, or network, so nothing in this section has been compiled or run. The mapping and sync tests are written but have not run. The provider calls, the screen, the migration, and the permission prompts have not run on a device or emulator. Check them on a device before relying on them.

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

CI (`.github/workflows/android.yml`) starts with `scripts/static-checks.py`, then runs format, detekt, unit tests, and debug and release builds on every push and pull request, and runs the Compose UI tests on an API 34 emulator. It currently calls `gradle` directly through `gradle/actions/setup-gradle`; switch to `./gradlew` once the wrapper is committed. The release build is unsigned in CI, because no signing secret is set there. `docs/release.md` describes signing.

## Layout

- `app/src/main/java/com/routinepilot/` application code, grouped by layer
- `app/src/test/` JVM unit tests
- `app/src/androidTest/` Compose UI tests
- `config/detekt/detekt.yml` detekt rule overrides
- `.github/workflows/android.yml` CI
- `backend/` the Kotlin sync backend (Ktor, PostgreSQL). It has its own Gradle build and is deployed separately. See `backend/README.md`. The Android app does not use it yet.
- `.github/workflows/backend.yml` backend CI, including a container image build
- `.github/workflows/release.yml` release bundle, from a `v*` tag or by hand
- `docs/release.md` build types, signing, and the release checklist
- `docs/privacy.md` what is stored where, what is sent, and how to delete data
- `docs/testing.md` the test suites, what was run, and what was not
- `docs/release-readiness.md` the final summary and what remains before release

## License

RoutinePilot is released under the MIT License. See [LICENSE](LICENSE). Third-party components keep their own licenses, which are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Verification status

Read this before relying on any feature.

- **Not compiled or run since the calendar export (commit `14b9fb8`).** The commits from that point to the current head were written where there was no JDK, Android SDK, Gradle, emulator, Docker daemon, or device. Their Kotlin code, unit tests, instrumented tests, backend tests, Docker image, and CI workflows have not been executed. Earlier commits were not re-verified in this series.
- **Checked without a compiler:** `python3 scripts/static-checks.py` runs twelve structural checks. It needs only Python 3.9 or later, and CI runs it first.
- **Gradle wrapper:** not committed yet. Run `gradle wrapper --gradle-version 8.9` once, then use `./gradlew`.
- **Remaining work:** [ROADMAP.md](ROADMAP.md) lists it in priority order, with status and estimates.

## Documentation

- [ROADMAP.md](ROADMAP.md): status, prioritized remaining work, and estimates
- [docs/release.md](docs/release.md): build types, signing, and the release checklist
- [docs/privacy.md](docs/privacy.md): what is stored, sent, and deleted
- [docs/testing.md](docs/testing.md): test suites, what was run, and what was not
- [docs/release-readiness.md](docs/release-readiness.md): summary of the release pass
- [docs/offline-sync.md](docs/offline-sync.md), [docs/smart-scheduling.md](docs/smart-scheduling.md), [docs/ui-accessibility.md](docs/ui-accessibility.md): design and behavior notes
- [backend/README.md](backend/README.md): sync backend API, configuration, and deployment

## Status

Routine management: the home screen lists routines and offers duplicate and delete (with undo). The editor creates and renames routines, adds, removes (with undo), reorders, and describes steps, sets step durations in minutes, shows the total estimated duration, and validates input before saving. Deleting a routine from the editor asks for confirmation.

Running a routine: the run screen is full screen and walks through the steps with a countdown for each timed step and a total time left. Start, pause, resume, skip, previous, finish, and cancel are supported. Timed steps advance automatically when their countdown reaches zero. Steps without a duration have no countdown and wait for Skip or Finish. The timer is a pure state machine in `domain/execution/RoutineTimer.kt`. It measures time with a monotonic clock and never counts ticks. Each step, session, and completion is written to Room, and the in-progress run is saved in DataStore. The run keeps going when the app is backgrounded, and it is restored correctly afterwards (see below).

Decisions: step reordering uses up and down buttons rather than drag and drop. Step removal and routine deletion undo only from the snackbar that follows them; edits stay in the draft until Save. Duration is stored in seconds.
