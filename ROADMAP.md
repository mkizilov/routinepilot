# RoutinePilot roadmap

This file records what the repository contains, what has actually been executed, and what to do next. It covers the commits from the calendar export (`14b9fb8`) to the commit that adds this file. Earlier commits are present in the tree, but they were not re-verified in this series.

## Status labels

- **Implemented:** the code or document exists in the repository.
- **Verified:** it was executed in an environment that can run it, and it passed. Only the repository checks in `scripts/static-checks.py` meet this bar for the work in this series.
- **Partially verified:** some check ran, such as an independent reference model, a source comparison, or a syntax check. The behavior itself was not run.
- **Unverified:** written, but never executed.

Nothing marked *Implemented* is verified unless it says so in the evidence column. The Android app compiles and its JVM tests pass as of the latest checkpoint. The backend compiles, and its 63 tests pass against a local PostgreSQL 16.15. Instrumented tests, device behavior, and Docker-based checks have not been run.

## Where things stand

| Area | Commit | Status | Evidence |
| --- | --- | --- | --- |
| Routines, timer, run recovery, schedules, agenda, statistics, widgets | before `14b9fb8` | Implemented; not re-verified in this series | Present in the tree; not reviewed here |
| Calendar export | `14b9fb8` | Implemented; JVM tests verified; device unverified | 36 JVM test functions pass (17 mapper, 19 synchronizer). Provider behavior on a device is unknown, including UID support |
| Sync backend (Ktor, PostgreSQL) | `5e7c0e4` | Implemented; verified against local PostgreSQL 16.15 | 63 of 63 tests pass. The service answers `/health` and serves register, upload, download, replay, and conflict over HTTP. Docker image and the Testcontainers path not run |
| Offline outbox and merge rules | `263473b` | Implemented; JVM tests verified | Merge and simulation tests pass as part of the 303 JVM tests |
| Android sync client, sign-in, account screen | `9537464` | Implemented; unverified | Client and backend compared in an earlier pass with an ad hoc script (routes, names, statuses, required fields, error codes). That script is not kept in the repository |
| Smart daily planning | `5e2de37` | Implemented; partially verified | 15 scenarios checked against an independent Python model, which is not kept in the repository. 22 JVM test functions written, not run |
| UI refinement, dynamic colour, accessibility semantics | `9540e24` | Implemented; partially verified | Semantics assertions written, not run. No TalkBack or emulator run |
| Release configuration, account deletion, onboarding, help, CI, privacy and deployment documents | `cee492e` | Implemented; partially verified | Workflows parse. Daylight-saving expectations checked against the IANA database. Contract compared in the same earlier pass. No build |
| Repository checks, `scripts/static-checks.py` | `7984f2c` | Implemented; verified | Twelve checks pass on the final tree. Nine injected defects were all reported |
| Android build, format, static analysis | `d20a979`, `11d3ca2` | Verified | Compile, 303 JVM tests, Spotless, detekt with a 42-entry baseline, debug and release assemblies |
| MIT license and third-party notices | `f194230` | Verified | `LICENSE` and `THIRD_PARTY_NOTICES.md` added. Items needing review are listed there |

Corrections to earlier reports:

- The planning turn reported 35 Kotlin unit tests. The planner's test file has 22 test functions.
- The README gave the schema version as 6 in two places. The current schema is version 8.

## Original requirements against the evidence

**Calendar export.** Optional export, destination selection, creating and updating events, removal, opening events, minimal permissions, duplicate prevention, time zones, all-day and recurring events, revocation, and mocked-provider tests are in the code and tests. None has been run, and device behavior is unknown. *Unverified.*

**Sync backend.** Registration, login, logout, profile, password change, account deletion, device registration and revocation, uploads, downloads since a cursor, paged records, rate limits, TLS settings, and validation are implemented. *Unverified.* The PostgreSQL JDBC calls have not been checked against any source, and Testcontainers has not run.

**Offline outbox and merge rules.** Different-field edits, same-field edits, delete against edit, reordering, duplicates, and multiple devices are implemented. *Unverified.*

**Multi-device sync.** Sign-in and sign-out, encrypted tokens, initial and incremental sync, uploads and downloads, backoff, connectivity-aware jobs, status, account isolation, and expired sessions without losing changes are implemented. Two-device convergence is covered by tests that have not run. *Unverified.*

**Smart daily scheduling.** The planner handles duration, deadlines, priority, prerequisites, fixed items, breaks, working hours, pins, and stable rescheduling. It explains why a task cannot be scheduled. Its rules were checked against an independent model, not the Kotlin code. *Partially verified.*

**UI refinement.**
- Responsive: a centred column of at most 640 dp on four screens only. *Partially implemented.*
- Dark mode and dynamic color: implemented, following the system setting. *Unverified on a device.*
- Accessible content descriptions and headings: asserted in semantics tests that have not run. *Partially implemented.*
- Large text: tested with a density override only. *Partially verified.*
- Keyboard navigation: Next and Done actions are set. Hardware keyboard traversal is not tested. *Partially implemented.*
- Loading and failure states: loading states added to the editor, planning, and account screens. Failure states are not uniform across screens. *Partially implemented.*
- Destructive confirmations: added to the two account conflict choices and account deletion. *Implemented.*
- Animations: `animateItem` on editor steps only. *Partially implemented.*
- Lifecycle and recomposition audit: done by reading code. No Compose compiler metrics were produced. *Partially verified.*
- Query and startup optimization: a query audit by reading DAO text, and one widget ticker fix. No measurement was made, so no performance gain is claimed. *Partially implemented.*

**Release and end-to-end testing.**
- Release configuration: implemented. `assembleRelease` runs and produces an unsigned APK. *Verified unsigned; signing and minification not configured.*
- Signing instructions: implemented. No keystore has been created and no build has been signed. *Unverified.*
- Backend deployment documentation: implemented. The distribution runs against PostgreSQL. The compose stack and the Docker image have not been run. *Partially verified.*
- Database migration tests: partially implemented. A test runs the chain to version 8 and a persistence test is written. There is no `MigrationTestHelper` test, and no exported schema exists yet. *Partially implemented.*
- End-to-end synchronization scenarios: partially implemented. Simulated devices with a fake server are written. Nothing runs against the real backend. *Partially implemented.*
- Offline and process-restart tests: written, not run. *Partially implemented.*
- Notification and scheduling tests: a WorkManager scheduling test and a notification presenter test are written. Not run. *Partially implemented.*
- CI workflows: implemented. The Gradle commands they run were run locally; the hosted runner has not run them. *Partially verified.*
- Privacy and data deletion documentation: implemented. No legal review. *Implemented.*
- Onboarding and help screens: implemented. *Unverified.*
- Verifying routine timing, recurrence, time zones, offline operation, account isolation, and sync conflicts: the daylight-saving expectations were checked against the IANA database. The rest is written as tests, not run. *Partially verified.*
- Running all available checks: the Android and backend Gradle checks were run with a local toolchain, and the repository checks passed. Instrumented tests, device checks, and Docker were not possible here. *Partially verified.*

## Prioritized roadmap

Priorities: **P0** blocks trust in everything else. **P1** correctness, security, or release blocker. **P2** important, not blocking. **P3** product improvement.

## 1. Confirm the build on a clean clone and in CI

- **Priority:** P0. **Status:** the local build is done: the wrappers are committed, the app compiles, and the Spotless, detekt, unit-test, and assembly gates pass. What remains is a clean-clone run and the CI run on a hosted runner.
- **Scope:** Clone the repository into an empty directory and run `./gradlew assembleDebug assembleRelease spotlessCheck detekt testDebugUnitTest` with a JDK 17 and an Android SDK. Switch the workflows to `./gradlew` (they still call `gradle`). Confirm the Room schema for version 8 in `app/schemas/` is the one the build writes.
- **Rationale:** The local run used a machine with an existing project-local toolchain. A clean clone checks that nothing depends on files that are ignored or uncommitted.
- **Dependencies:** Access to a hosted runner or a fresh machine.
- **Acceptance criteria:** The clean-clone run passes with the same results as the local run. The `android.yml` and `backend.yml` jobs pass on the hosted runner.
- **Effort:** 3 to 6 hours.

## 2. Run the JVM unit tests and fix failures

- **Priority:** P0. **Status:** 303 of 303 pass locally. CI has not run them.
- **Scope:** Run `./gradlew testDebugUnitTest`. Fix the code when a test shows a real defect. Change a test expectation only when the expectation is wrong, and say why in the commit.
- **Rationale:** The merge, planning, recurrence, and time-zone rules are encoded in these tests, and they have never run.
- **Dependencies:** Step 1.
- **Acceptance criteria:** All 303 tests pass locally and in CI. Each changed expectation has a stated reason. The counts in `docs/testing.md` match the run.
- **Effort:** 8 to 14 hours.

## 3. Compile and test the backend, including the Docker-backed tests

- **Priority:** P0. **Status:** 63 of 63 pass locally against PostgreSQL 16.15 (a project-local server). The Testcontainers path, the Docker image, and the compose stack have not run.
- **Scope:** Run `gradle test` with a Docker daemon so the Testcontainers path runs with no `RP_TEST_DATABASE_URL`. Build the image with `docker build`, start the compose stack on loopback, and check `/health`. Confirm the container runs as non-root with a read-only root filesystem.
- **Rationale:** The local run covers the SQL and the HTTP contract. It does not cover the Testcontainers setup or the image, which CI and deployment depend on.
- **Dependencies:** Docker on a machine you control. Not the sandbox.
- **Acceptance criteria:** `gradle test installDist` passes in `backend.yml` with Testcontainers. The image builds in CI. `curl` on `/health` from the compose stack returns `{"status":"ok"}`.
- **Effort:** 6 to 10 hours.

## 4. Run the Compose UI and database tests on an emulator

- **Priority:** P0. **Status:** unverified, 129 instrumented test functions. None has run.
- **Scope:** Run `./gradlew connectedDebugAndroidTest` on an API 34 x86_64 emulator, as the CI job does. Fix failures. Confirm that the migration chain and the persistence test open a real database. Check the WorkManager test setup.
- **Rationale:** Compose rendering, Room on a device, and WorkManager's test setup have not run.
- **Dependencies:** Step 1.
- **Acceptance criteria:** The `instrumented` CI job passes. `docs/testing.md` records the emulator image and API level.
- **Effort:** 10 to 16 hours.

## 5. Add exported-schema migration tests

- **Priority:** P1. **Status:** unverified. The schema for version 8 was exported by this pass's build; versions 1 to 7 were not.
- **Scope:** Add `MigrationTestHelper` tests for each step from version 1 to 8, using the schema files from step 1. Check each migrated table against its entity. Keep the full-chain test.
- **Rationale:** The current test opens the whole chain, but it does not check each step's data movement. The 5 to 6 and 7 to 8 migrations were written without a compiler.
- **Dependencies:** Step 1, for the schema files.
- **Acceptance criteria:** One migration test per step passes on the emulator. The note in the README that says the helper test is not yet written is removed.
- **Effort:** 6 to 10 hours.

## 6. Share the sync and planner test vectors between the app and the backend

- **Priority:** P1. **Status:** unwritten.
- **Scope:** Write golden JSON vectors of inputs and expected outputs for the wire format, the four sync decisions (`ACCEPTED`, `DUPLICATE`, `CONFLICT`, `REJECTED`), and the merge rules. Load the same file from the Android JVM tests and from the backend's `SyncRulesTest`. Add the planner's cases as vectors too.
- **Rationale:** The client and the backend implement the same rules separately. The current checks compare names and fields, not behavior.
- **Dependencies:** Steps 2 and 3.
- **Acceptance criteria:** Both test suites read the same vector file. A change to the rules on one side fails the other side's build.
- **Effort:** 10 to 16 hours.

## 7. Run sync end to end against the real backend

- **Priority:** P1. **Status:** unverified. The simulations use a fake server, and the backend has been run only from this shell, not from a phone.
- **Scope:** Run the backend with compose on a development host. Run the debug app on two emulators, which reach it at `10.0.2.2:8080`. Script these cases: register and sign in; create routines offline on each device; reconnect in both orders; a lost response; an expired device session; a revoked device; sign-out; account deletion; and the absence of the previous account's data after a switch.
- **Rationale:** Nothing has shown the HTTP client, the JSON decoding, and the server validation working together.
- **Dependencies:** Steps 1, 3, and 4.
- **Acceptance criteria:** Each case has an automated test or a dated manual record with the device and commit. Two devices converge. No local change is lost in the expired-session or outage cases.
- **Effort:** 10 to 16 hours.

## 8. Fix the order of account switching during sign-in

- **Priority:** P1, correctness. **Status:** defect found by review; not fixed.
- **Scope:** `AccountSessionManager.establish` erases local data and saves the new account before the new device is registered. A network failure at registration leaves the previous account's local copy erased and the device half signed in. Reorder the steps: authenticate, check the plan, register the device, then erase and seed. Add a test where registration fails and the local data is still there.
- **Rationale:** No unsent change is lost, because the plan requires zero pending changes. But a local copy is erased before the new account is proven to work.
- **Dependencies:** None. Can be done early.
- **Acceptance criteria:** The new test passes. A failed registration leaves the local data and the owner unchanged.
- **Effort:** 3 to 6 hours.

## 9. Let users repair a refused change

- **Priority:** P1, correctness. **Status:** unwritten. The limit is documented in `docs/offline-sync.md`.
- **Scope:** A change the server refuses stays queued, and it blocks later changes to the same record. Add an action on the account screen to edit the record so it passes validation, or to discard the queued change, with confirmation.
- **Rationale:** A record can stay stuck forever after a server-side validation change, and nothing in the app clears it.
- **Dependencies:** Step 7, for end-to-end testing.
- **Acceptance criteria:** A test shows that discarding a refused change lets later changes sync. The "needs attention" count returns to zero after the action.
- **Effort:** 6 to 10 hours.

## 10. Remove the email oracle at registration and add abuse controls

- **Priority:** P1, security. **Status:** unwritten. Listed as a known weakness in `backend/README.md`.
- **Scope:** Registration returns `409 email_taken`, which tells anyone whether an address has an account. Return the same response for new and existing addresses. Complete registration by an email verification step, and add password reset. Choose and document the email provider and its data handling.
- **Rationale:** The rate limit slows enumeration, but does not stop it.
- **Dependencies:** A decision on an email provider.
- **Acceptance criteria:** An API test shows the same status, body shape, and timing bounds for taken and free addresses. A reset test passes. The known-weaknesses list in `backend/README.md` is updated.
- **Effort:** 12 to 20 hours.

## 11. Add session lifecycle controls

- **Priority:** P2, security. **Status:** unwritten.
- **Scope:** Sessions last 30 days with no refresh and no rotation. Add token rotation or a refresh token. Add a device list in the app with revoke. The server already signs out other sessions on password change; confirm the app reflects it.
- **Rationale:** A stolen token stays valid for the full session length.
- **Dependencies:** Steps 3 and 7.
- **Acceptance criteria:** A backend test shows that a rotated token is required. The app lists devices and can revoke one.
- **Effort:** 8 to 14 hours.

## 12. Share rate limits across backend instances

- **Priority:** P2, operations. **Status:** unwritten. Rate limits are in memory per process.
- **Scope:** Move the counters to PostgreSQL or another shared store, keeping the fixed-window rule and the `429` with `Retry-After` contract.
- **Rationale:** Several instances would each allow the full budget.
- **Dependencies:** Step 3.
- **Acceptance criteria:** A two-instance test shows one shared budget. The "per process" note in `backend/README.md` is removed.
- **Effort:** 6 to 10 hours.

## 13. Add change-log retention and snapshots

- **Priority:** P2, scale. **Status:** unwritten. The log is never compacted.
- **Scope:** Design a snapshot of current records at a sequence number, and a retention rule. Devices that are behind the retention window re-bootstrap from the snapshot or from `/v1/records`. Document the cursor contract.
- **Rationale:** The log grows with every accepted change, without limit.
- **Dependencies:** Steps 3 and 6. This changes the cursor contract, so the vectors must be updated.
- **Acceptance criteria:** A device offline past retention converges after a resync, shown by a test. The cursor contract is documented.
- **Effort:** 12 to 20 hours.

## 14. Harden the release build

- **Priority:** P1, release blocker. **Status:** unwritten.
- **Scope:** Enable minification and verify the keep rules on a device. Decide on certificate pinning. Make the release address check run only for release builds. Today its `require` runs for every build. Produce a signed bundle with a test keystore kept outside the repository. Prepare the store listing, the privacy URL, and the data-safety declaration from `docs/privacy.md`.
- **Rationale:** Minification is off because its keep rules were not verified. A release that breaks only when minified would not be seen until a device run.
- **Dependencies:** Steps 1, 4, and 15.
- **Acceptance criteria:** A minified release passes the device checklist from step 15. The signed bundle installs. Each item in the checklist in `docs/release.md` is ticked with a date.
- **Effort:** 8 to 14 hours.

## 15. Run the device checks for notifications, boot, background work, calendar, and widgets

- **Priority:** P1, hardware. **Status:** unverified. Needs physical devices or a device lab, covering at least Android 10, 13, 14, and 15.
- **Scope:** Run a manual checklist and automate what can be automated:
  - Run notifications, including the permission on Android 13 and later.
  - The foreground service with its special-use subtype on Android 14 and later.
  - Restoring a run after a reboot.
  - Sync passes under Doze, with the network off and then on.
  - Calendar export and UID adoption on each provider version.
  - Widgets updating after changes.
  - The Open action on exported events.
- **Rationale:** These behaviors depend on the operating system and the device, and none has been observed.
- **Dependencies:** Steps 1 and 4.
- **Acceptance criteria:** Each item has a dated record in `docs/testing.md` with device model, Android version, and result. Each failure becomes a bug with a reproduction.
- **Effort:** 12 to 20 hours.

## 16. Measure accessibility and performance

- **Priority:** P2. **Status:** partially implemented. Nothing measured.
- **Scope:** A TalkBack pass over every screen. Hardware keyboard traversal. 200 percent font scale set in system settings. A reduced-motion check. A contrast audit on wallpaper colours. Then a macrobenchmark for cold start, and Room query timings on a seeded database of about 10,000 scheduled occurrences. Store the results in a new benchmarks document, with the device, the Android version, and the dataset size.
- **Rationale:** The accessibility work was checked only through the semantics tree. Performance was audited by reading query text.
- **Dependencies:** Steps 1 and 4, and a device.
- **Acceptance criteria:** A written findings list from the TalkBack and keyboard pass. Benchmark numbers recorded with their context. A regression threshold, chosen from the first run, that fails CI when crossed.
- **Effort:** 12 to 20 hours.

## 17. Close product gaps in planning and calendar

- **Priority:** P3. **Status:** unwritten.
- **Scope:** Planning covers one day and does not refresh in the background. Add a multi-day window and a background refresh, and test the planner on daylight-saving days. Calendar export is one way: edits made in the calendar app are not read back. Decide whether to read them back or document the limit. Add a cleanup of calendar links on account switch. Also record the `createdAt` limit, which is not synced.
- **Rationale:** These are the largest product limits that users will notice.
- **Dependencies:** Steps 1, 2, and 4.
- **Acceptance criteria:** A multi-day plan test and a daylight-saving planning test pass. The calendar decision is recorded in `docs/privacy.md` and the README.
- **Effort:** 16 to 30 hours.

## 18. Restrict the backend's network bind and make local runs loopback-only

- **Priority:** P1, security. **Status:** unwritten. A local test run in this pass bound the backend to `*:21580`, all interfaces, because the default bind address is `0.0.0.0`.
- **Scope:** Make the bind address configurable (`rp.bindHost`, default `127.0.0.1` for local runs, and `0.0.0.0` only in the container). Document the setting in `backend/README.md`. Check the compose file binds the published port to loopback, which it already does.
- **Rationale:** An unguarded local service that accepts connections from the network is a risk, even with a throwaway database.
- **Dependencies:** None.
- **Acceptance criteria:** A test shows the default bind is loopback. The container still serves on all interfaces inside the network namespace.
- **Effort:** 2 to 4 hours.

## 19. Stop test and dev processes reliably in the orchestration sandbox

- **Priority:** P2, operations. **Status:** observed. In this pass, the sandbox denied signals to two owned processes after starting them, so they had to be left running and documented. The backend (PID 6057, port 21580) and PostgreSQL (PID 4929, port 21547) may still be running when this pass ends.
- **Scope:** Write a stop script that uses each server's own shutdown path (`pg_ctl stop`, and an admin endpoint or a `SIGTERM` handler for the backend), and check the port is free afterward. Record the process ids in a file under `.agent-local/run/` so a later cleanup can find them.
- **Rationale:** Leftover listeners hold ports and data directories, and the next run can fail or see stale data.
- **Dependencies:** Step 18, for a shutdown endpoint if one is added.
- **Acceptance criteria:** The script stops both processes and frees their ports. A test run starts and stops them without a leftover listener.
- **Effort:** 2 to 4 hours.

## 20. Burn down the 42 structural detekt findings

- **Priority:** P2, maintainability. **Status:** recorded in `config/detekt/baseline.xml`. Detekt passes with the baseline. New findings still fail.
- **Scope:** Split the DAO interfaces (`SyncOutboxDao`, `PlanningDao`) and the database class. Split the large repositories and the largest Compose screens (`RoutinePilotNavHost`, `CalendarSettingsScreen`, `ScheduleForm`, `ScheduleListRoute`, `EditorForm`, `PlanningScreen`). Shorten the long parameter lists with parameter objects. Remove each entry from the baseline as it is fixed.
- **Rationale:** These findings are size and count metrics, not defects. The baseline keeps them visible, but they make the code harder to change.
- **Dependencies:** Step 4, so the Compose screens can be checked on an emulator as they are split.
- **Acceptance criteria:** `config/detekt/baseline.xml` is empty, and detekt passes without it. Each split keeps its tests passing.
- **Effort:** 12 to 20 hours.

## 21. Add Docker-independent integration tests for the backend

- **Priority:** P1, correctness. **Status:** partly done. The backend tests can use an existing PostgreSQL through `RP_TEST_DATABASE_URL`. The local run used a project-local server, but no CI job uses that path.
- **Scope:** Add a CI job that starts a PostgreSQL 16 service container and sets `RP_TEST_DATABASE_URL`, so the database and API tests run without Testcontainers. Keep the Testcontainers path as the default for developers with Docker.
- **Rationale:** A second, independent way to run the database tests reduces the chance that a Docker-only setup failure hides a real defect.
- **Dependencies:** Step 3.
- **Acceptance criteria:** The new CI job passes the 63 backend tests.
- **Effort:** 3 to 5 hours.

## 22. Bundle the full third-party license texts into the release

- **Priority:** P1, release blocker. **Status:** partly done. `THIRD_PARTY_NOTICES.md` records each runtime artifact's license from its POM. It does not include the license texts, and it names logback's EPL-1.0 or LGPL choice as needing a decision.
- **Scope:** Generate a complete notice bundle from the release runtime with a license-report Gradle plugin (for example `licensee` or `com.github.jk1.dependency-license-report`), pinned in the version catalog. Decide the logback license. Include the bundle in the APK's assets and in the backend image.
- **Rationale:** Distributing an app or image requires the license texts of what it ships.
- **Dependencies:** Step 1 (for a clean build), and a decision on logback.
- **Acceptance criteria:** The bundle lists every runtime artifact with its license text. The APK and the image contain it. A reviewer signs off on the logback choice.
- **Effort:** 4 to 8 hours.

## 23. Verify the backend image and its runtime behavior under Docker

- **Priority:** P1, release blocker. **Status:** unverified. The distribution runs locally, but the image is not built or started.
- **Scope:** Build the image with the existing Dockerfile. Start the compose stack. Run the API contract checks from this pass (register, device, upload, download, replay, conflict, and the account-deletion route) against the container. Confirm the non-root user and the read-only root filesystem.
- **Rationale:** The Dockerfile has never been run, so a missing file or a wrong path would only show at deployment.
- **Dependencies:** Step 3, and Docker.
- **Acceptance criteria:** The image builds. The contract checks pass against the container. `docker inspect` shows the non-root user and the license label.
- **Effort:** 4 to 6 hours.

## Total estimate and assumptions

The 23 steps sum to 175 to 299 hours. A 20 percent contingency gives **210 to 359 hours**, or about 26 to 45 working days at 8 hours each. Steps 1 to 17 were estimated before this pass, and their ranges are unchanged except where their status changed.

Assumptions:

- One engineer who knows Kotlin, Compose, Room, WorkManager, Ktor, and PostgreSQL.
- Access to a JDK 17, Android SDK platform 35, an API 34 emulator, a few physical devices for step 15, Docker, and a Linux or macOS host for the backend.
- The estimates include writing or changing tests and documentation.
- Not included: waiting for store review, legal review, an email provider decision, device-lab scheduling, and code review queues.
- The sync protocol and schema stay as they are. Steps 6 and 13 change behavior at the boundary and add time.
- The first build found 38 compile errors in the Android app and 3 in the backend. All are fixed. The remaining uncertainty is in the instrumented tests and on devices, which have not run.
- The contingency covers that uncertainty only partly.

## Known limits that apply today

- The app and backend have never run together.
- Minification is off. Keep rules are unverified.
- Notification, boot, widget, calendar, and Keystore behavior is unverified on a device.
- Sync cannot repair a refused change. See step 9.
- Account switching can erase a local copy before the new device is registered. See step 8.
- Registration reveals whether an email address has an account. See step 10.
- The change log is never compacted, and rate limits are per process.
- No privacy policy URL, store listing, or legal review exists.

The detailed notes are in `docs/testing.md`, `docs/release-readiness.md`, `docs/privacy.md`, and `backend/README.md`.
