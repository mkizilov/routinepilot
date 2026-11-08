# Testing and verification

This records what was run, what was not, and why. Counts are the number of `@Test` functions in each source set.

## Suites

| Suite | Location | Count | Runs on | Needs |
| --- | --- | --- | --- | --- |
| JVM unit tests | `app/src/test` | 303 | Any JVM | JDK 17 and Gradle |
| Compose UI and database tests | `app/src/androidTest` | 129 | Emulator or device, API 34 in CI | Android SDK, emulator |
| Backend unit, database, and API tests | `backend/src/test` | 63 | JVM | JDK 17 and Gradle. Database and API tests also need Docker for Testcontainers |

Commands:

```sh
./gradlew spotlessCheck detekt testDebugUnitTest assembleDebug assembleRelease   # Android static checks and unit tests
./gradlew connectedDebugAndroidTest                                              # needs an emulator or device
cd backend && gradle test installDist                                            # needs Docker for database and API tests
```

## Coverage by requested area

- **Routine timing and recurrence**: `RecurrenceTest`, `ScheduleGeneratorTest`, `RoutineTimerTest`, `AgendaAndTransitionsTest`.
- **Time zones and daylight saving**: `DaylightSavingTest` (10 tests). The Berlin expectations were checked against the IANA time-zone database. Three cases were added for New York and Lord Howe Island, whose expected instants were also computed from that database rather than from the app's code.
- **Offline operation**: `OfflineMergeTest`, `OfflineSyncSimulationTest`, and `SyncEngineTest` run devices with no connection, and the planning and routine features do not call the network.
- **Process restart and offline edits**: `SyncRestartTest` (4 tests). Each test ends a device's process, starts a new one over the same storage, and checks that queued changes survive and reach the server exactly once. These cover a kill before sync, a lost response followed by a kill, an expired session that is signed in again, and two devices restarting before reconnecting in either order.
- **Account isolation**: `AccountIsolationTest` covers the sign-in decision for each owner and queue state.
- **Synchronization conflicts**: `OfflineMergeTest` and `OfflineSyncSimulationTest` cover edits to different fields, the same field, deletes against edits, edits to deleted records, reordering, duplicates, and several devices.
- **Database migrations**: `MigrationTest` opens a version 1 file and runs the whole chain to version 8. It also checks that the outbox and planning tables accept rows afterwards. Room checks the final schema against the entities when the database opens.
- **Persistence**: `RoomOutboxStorePersistenceTest` closes and reopens a file database and checks that queued changes and the server base survive.
- **Scheduling**: `SyncSchedulerTest` checks that a sync pass waits for a connection, that a periodic pass is not duplicated, and that sign-out cancels both passes.
- **Notifications**: `RunNotificationPresenterTest` (instrumented).
- **Onboarding**: `OnboardingTest` (domain rules) and `OnboardingScreenTest` (paging, back, skip, finish).
- **Help**: `HelpScreenTest`.
- **Account deletion**: `AccountScreenTest` checks that deletion needs the password and a confirmation, and that the confirmed password is passed on.
- **Planning race**: `PlanningViewModelTest`.

## What was run in this environment

The environment had no working JDK, Android SDK, Gradle, emulator, or Docker daemon, and no network from the shell. So no Gradle task and no test was executed. The following checks were run instead, and each one's result is recorded here.

1. **Daylight-saving expectations.** The three existing Berlin expectations (gap, before the gap, and overlap) were recomputed with Python's `zoneinfo`. All three matched. The three new New York and Lord Howe expectations were computed the same way. A check also confirmed that each of those cases is a real gap or overlap, rather than an ordinary time.
2. **Client and backend contract.** A script compared the two sides' source:
   - Every route the app calls exists in the backend's routes. Before this turn one did not: account deletion was a DELETE with a body, which the app's HTTP client cannot send. It is now a POST, and the backend test and documentation were updated to match.
   - Every entity type the app sends exists in the backend. The backend also accepts `preference`, which the app never sends.
   - The statuses (`ACCEPTED`, `DUPLICATE`, `CONFLICT`, `REJECTED`) and operations (`UPSERT`, `DELETE`) match on both sides.
   - Every field the backend requires is sent by the app, for all four synced types.
   - The backend's title limit (200) accepts every title the app allows (80).
   - The error codes the app maps (`invalid_credentials`, `email_taken`, `validation_failed`, `invalid_password`, `unauthorized`) are the ones the backend emits.
3. **Workflow files.** `android.yml`, `backend.yml`, and `release.yml` parse as YAML with Ruby's YAML library.
4. **Static checks on changed code.** No duplicate string names, no references to missing string resources, no raw apostrophes in new strings, and no line over 120 characters. Six reported unused imports were checked by hand: they are the `getValue` and `setValue` operators that `by` delegates need.

## What was not run, and why

- **Gradle, Kotlin compilation, detekt, Spotless, and all Kotlin tests.** No JDK or Gradle is installed. A compile error anywhere is possible and would be found first by the first build.
- **Migration tests against exported schemas.** Room writes version schema files on the first build. `MigrationTestHelper` needs them, so `MigrationTest` uses the in-database chain instead.
- **Emulator and device behaviour.** Compose rendering, TalkBack output, WorkManager constraints (the scheduling test relies on WorkManager's test setup, not a real network), Keystore encryption on real hardware, notification delivery, boot restore, and calendar provider behaviour.
- **Backend tests and the container.** The database and API tests need Docker. The image is built in CI but is not started there, and the compose stack and the direct-TLS path have not been run.
- **Performance.** Nothing has been measured, on a device or on the server.

## Limits of the checks that were run

- The Python checks read source text. They do not compile or execute it, so they cannot find an error that only shows at run time.
- The DST expectations were checked against the same tz rules that the Java code uses, so they confirm the expected values, not the platform's rule data for future years.
- The contract script checks names, fields, and codes. It does not check value formats beyond what the source states, such as timestamp syntax, and it does not check behaviour under failure.
