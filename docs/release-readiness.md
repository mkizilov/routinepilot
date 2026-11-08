# Release readiness: implementation and testing summary

The prioritized remaining work, with status and estimates, is in [ROADMAP.md](../ROADMAP.md). This page describes the release pass as it was when it was written.

This is the final summary of the release-preparation pass. It records what exists, what was executed, and what must happen before the app or the backend is published. Nothing in it was compiled or run on a device.

## Status at a glance

| Area | State | Verified here | Still needed |
| --- | --- | --- | --- |
| Android release configuration | Written | Build script and manifest read back; syntax reviewed | `./gradlew assembleRelease` on a machine with the SDK |
| Signing | Written, environment-driven | Nothing is committed; ignore rules added | A keystore, and a signed build |
| Network security | Written | Release refuses cleartext; debug allows the emulator host only | A device check of an `http://` address |
| Backups | Disabled | Manifest read back | None |
| Backend contract | Corrected and cross-checked | Routes, names, statuses, fields, error codes compared by script | Running both sides together |
| Account deletion | Written in app and backend | Route and client path compared; tests written | Compose and API test runs |
| Onboarding and help | Written | Strings checked; rules tested as pure functions | Compose tests on an emulator |
| Migration tests | Extended | Test code reviewed | Running on an emulator |
| End-to-end sync scenarios | Written as simulations | Test code reviewed | Running |
| Offline and restart tests | Written | Test code reviewed | Running |
| Notification and scheduling tests | Written | Test code reviewed | Running on an emulator |
| Time-zone tests | Extended | Expected values checked against the IANA database | Running |
| CI workflows | Written | All three parse as YAML | A first run on GitHub |
| Privacy and deletion | Documented | Checked against the code paths | Legal review, and a store declaration |
| Backend deployment | Documented | Configuration keys checked against `application.conf` | A real deployment |

## What changed in this pass

- **Account deletion.** The backend route was a DELETE with a body, which the Android HTTP client cannot send. It is now `POST /v1/me/delete`. The app can delete the account after the password and a confirmation, and it erases local data only after the server confirms.
- **Backup and cleartext.** Android backup is off, because the local data depends on device-bound keys. The release build refuses cleartext, and a debug-only configuration allows the emulator's host address.
- **Build configuration.** The sync address and signing come from environment variables, so no secret or deployment address is written into the repository.
- **Onboarding and help.** A first-run introduction appears only on a fresh install. Help is reachable from the agenda.
- **Documentation.** Release, privacy and deletion, testing, backend deployment, and this summary.
- **CI.** The Android check job also builds the release variant. The backend job builds the container image. A new release workflow runs the unit tests and builds the signed bundle when signing is configured.
- **Time-zone expectations.** Three cases for zones with other transition rules.
- **Restart and outbox persistence.** Simulated process restarts at four points in the sync protocol, plus a test that the outbox survives closing the database.

## Defects found by the checks in this pass

1. **Account deletion could not be sent.** The Android HTTP client cannot send a body with DELETE. Fixed by moving the route to POST, in the backend, its test, and its documentation.
2. **Release builds could reach a plain-HTTP server.** The sync address was hard-coded to the emulator's `http://`. Fixed by a per-build-type address, a release check that refuses non-HTTPS, and a network security configuration.
3. **Restored backups would be inconsistent.** Automatic backup restored the queue and cursor without the tokens that match them. Fixed by turning backup off.
4. **The migration test's comment claimed the chain ended at version 6.** It now covers the whole chain, and a test checks the new tables.

No defect was found in the sync wire contract. Every route, name, status, operation, required field, and error code matched once the deletion route was corrected.

## Test results

Nothing has been executed. The counts are the number of test functions present:

- 303 JVM unit tests. This pass added 11 of them: 4 restart tests, 4 onboarding rule tests, and 3 time-zone cases. The planning race tests were added in the previous pass.
- 129 instrumented tests, of which the migration, persistence, scheduling, onboarding, help, and account-deletion tests were added this pass.
- 63 backend tests, of which one changed, for the deletion route.

The checks that did run are listed in `docs/testing.md`: the daylight-saving expectations against the IANA database, the client-backend contract comparison, the YAML parse of three workflows, and static checks on the changed code.

## Before the first release

The release checklist is in `docs/release.md`. The essential items are:

1. Run `./gradlew check`, then `./gradlew connectedDebugAndroidTest` on an emulator. Expect the first compile to surface errors, since none of this code has been built.
2. Generate and commit the Room schema files that the first build writes, so later migrations can be tested against them.
3. Deploy the backend once, with the steps in `backend/README.md`, and sync a routine between two devices.
4. Sign in on a device, delete the test account, and confirm the device's data is erased.
5. Decide whether to enable minification, after a minified release has run on a device.
6. Prepare the store listing and the data-safety form from `docs/privacy.md`.

## Known limitations

- The app and the backend have never been compiled or run together.
- Minification is off, and the keep rules have not been verified.
- Notification, boot, widget, and calendar behaviour are unverified on a device.
- The backend's rate limits are per process, and the change log is never compacted.
- No privacy policy URL, store listing, or legal review exists yet.
