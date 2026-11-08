# Offline change tracking and conflict resolution

Status: the device-side model, the Room outbox, the merge rules, the sync engine, the backend client, sign-in, and the account screen are written. None of it has been compiled or run, because the environment that wrote it has no working JDK, Android SDK, or Gradle. The JVM tests are written but not executed. The app is connected to the backend's API as described in the sync client section below.

## What is tracked

Routines, routine steps, tasks, and schedules. Each record has a stable UUID that never changes. A repository write records a change in the same Room transaction as the write itself, so an edit and its outbox entry are saved together or not at all. A save that changes no tracked field records nothing.

Tracked fields are listed in `SyncType` (`domain/sync/SyncModel.kt`). Timestamps that only say when a device saved are not tracked, so an unchanged save does not look like a change.

## Model

- **Outbox** (`mutation_outbox`, `mutation_outbox_field`): queued changes, in this device's order. Each change stores its full snapshot and the fields that changed (`before`, `after`).
- **Base** (`sync_base`, `sync_base_field`): the last state the server reported for each record, with its version. A deleted record keeps a tombstone with its version.
- **Seen version**: the server version the device's view was built on when the change was recorded. It is used only for conflict detection.
- **Mutation id**: each change's id is also its change id on the server. A resend is recognized as the same change, so it is never applied twice.
- **Server order**: the server assigns each accepted change a sequence number and a per-record version. Only that order decides which of two concurrent changes wins. Device clocks are stored for display and are never read by the merge.

The merge (`OfflineMerge`) replays a record's queue, in local order, on top of its base. It is a pure function of the base and the queue.

## Conflict rules

| Situation | Result | Automatic or user | Rule |
| --- | --- | --- | --- |
| Changes to different fields, on one or more devices | Both survive | Automatic, no report | Each change lists only the fields it changed, and the merge applies them one by one. |
| Changes to the same field | The change that reaches the server later wins | Automatic, reported as `Superseded` with the value it replaced | A change applies only when the field still holds the value it saw. Otherwise it replaces the field and reports the old value. |
| Edit that matches what the server already has | Nothing to do | Automatic, no conflict | The field already holds the new value. |
| Delete of a record another device edited after this device last saw it | The delete waits; the edit stays | **User**: `KEEP_EDIT` drops the delete, `DELETE_ANYWAY` deletes | `DeleteOfEdited` when the server version is above the version the delete was made against. |
| Edit of a record another device deleted | The edit waits; the record stays deleted | **User**: `RESTORE_EDITS` writes the edited record back, `DISCARD_EDITS` drops the edit | `EditOfDeleted`. Edits to a tombstone are never applied silently. |
| Delete of a record that is already deleted | Dropped | Automatic, no conflict | `redundant`. |
| Reordering steps on two devices | Each step's `position` settles like any other field | Automatic, reported as `Superseded` for each position both devices changed | Server order decides each position. Two steps can end up with the same position. |
| Duplicate submission, same change sent twice | Applied once | Automatic | The server returns `DUPLICATE` with the original version, and the device settles the change. |
| Lost response, then retry | Applied once | Automatic | The retry sends the same request. The server matches it by change id, base version, and payload, which is why a request is always rebuilt from the same base. |
| Changes from three or more devices | Converge to the server's state | Automatic | Each device pulls the log in order. Field-level rules above apply to every pair. |
| Server rejects a change id that was reused for a different change | Sync stops with an error | Not expected in practice | `Rejected` is never ignored. |

## Invariants the design depends on

1. **Version order is device order.** A device sends a record's changes one at a time and waits for each answer. A change accepted at version `v` means no other change to that record came between `v-1` and `v`. So while a request is unanswered, the device cannot move its base past it without absorbing its own log entry first. The retry therefore rebuilds the same request.
2. **The base moves only on an answer or on a log entry in version order.** A conflict answer sets the base to the server's head. An accepted or duplicate answer sets it to that version only when that version is the next one. Log entries are absorbed in sequence order.
3. **Queued changes never leave the outbox until the server answers or the log confirms them.** A conflict keeps the change and merges it again on the new base.

## Known gaps and limits

- **Duplicate step positions** can result from concurrent reordering. The display sort must break ties by step id. That sort is not implemented in this change.
- **Schedules** share the generic merge and the field mapping. The field names of the recurrence kinds are not checked against the backend's validator yet.
- **The tests use a fake network.** `FakeSyncRemote` wraps `FakeSyncServer`, which copies the backend's rules, not the backend itself. The real HTTP client is not exercised by any test here.
- **Server address.** The client points at the emulator loopback, `http://10.0.2.2:8080`, which Android only allows with a debug network security config. Release builds must use HTTPS, and that address is not set up yet.
- **Room schema.** The Room schema for version 7 is generated on the first build and must be reviewed and committed then.
- **Not run.** No Kotlin compiler, JDK, Android SDK, or Gradle was available. The JVM tests in `domain/sync` are written, not executed.

## Sync client

Components, from the bottom up:

- **Wire format** (`data/sync/SyncWire.kt`). Matches the backend's DTOs. Numbers and booleans are sent as JSON values. Every other field is sent as text. Entity types the app does not sync, such as the server's preference records, are skipped, and the cursor still moves past them.
- **HTTP** (`data/sync/BackendClient.kt`). Uses the platform's `HttpURLConnection`, so no networking library is added. Server-busy and server-error answers become `RemoteBusyException`, which is retried. Other failures become `BackendException` with the server's error code.
- **Remote** (`data/sync/BackendSyncRemote.kt`). Sync calls use a device session. A device session that has expired by its stored time is registered again, once. A device session the server rejects before its stored expiry means the device was revoked, so the user must sign in again rather than having the device quietly re-added.
- **Engine** (`domain/sync/SyncEngine.kt`). One pass pulls the log, sends each queued record's changes in local order, and pulls again. A mutex serializes passes, so a background job and "Sync now" never interleave. A failure leaves the outbox and base in a state the next pass continues from. The engine applies merged views to the app's tables, so server changes appear in the app.
- **Background** (`infrastructure/sync/`). A pass runs only with a network connection. A pass that could not reach the server is retried by WorkManager with exponential backoff starting at 30 seconds. A periodic pass runs every 6 hours for a signed-in account. A change to the queued count, while signed in, schedules a pass after a 1-second pause, so a burst of edits syncs once.
- **Account** (`data/sync/AccountSessionManager.kt`). Sign-in, registration, and sign-out. See the table below.
- **Tokens** (`data/sync/TokenVault.kt`). Tokens are encrypted with an AES-GCM key held in the Android Keystore. DataStore holds only the ciphertext. If the key is lost, the tokens cannot be read, and the user is asked to sign in again. Local data is not affected.

### Account data isolation

Each device's data belongs to one account, its owner. Signing in decides what happens to local data:

| Device state | Sign in to | Result |
| --- | --- | --- |
| No owner yet, for example a fresh install | Any account | The device adopts the account. Records made before sign-in are queued and uploaded. |
| Owned by the same account | That account | Nothing is erased. The cursor carries on. |
| Owned by another account, with unsent changes | A different account | Refused. Nothing is erased, and the user is asked to sync the other account first. |
| Owned by another account, everything sent | A different account | The local data is erased, then the new account adopts the device. |

Signing out keeps local data, the owner, the cursor, and queued changes, so the app keeps working offline. It tries to remove the device from the account. If the server cannot be reached, the device session expires on its own, and the user is told so.

A cursor belongs to one account's log. Changing the owner resets it to zero.

### Sync states

| State | Meaning |
| --- | --- |
| Not signed in | No account session. Nothing is sent. |
| Syncing | A pass is running. |
| Up to date | The last pass completed with nothing held or refused. |
| Waiting for a connection | The server could not be reached. Changes are kept, and the next pass runs when the connection returns. |
| Sign in again | The session expired or the device was revoked. Changes are kept. |
| Needs attention | An edit or delete waits for a decision, or the server refused a change. Refused changes stay queued and are reported. |

Two ways a queued change can stay stuck, both surfaced in the screen:

- A held edit or delete waits until the user decides.
- A refused change is never dropped, and it blocks later changes to the same record until it is fixed on the server. The app cannot repair it yet.

### Known limits of the client

- Execution history, scheduled occurrences, and calendar links are device-local. They are not synced, and on an account switch they are erased with the rest of the local data. Calendar events already exported stay in the calendar app, because the app cannot delete them after its own links are gone.
- `createdAt` is not synced. A record that arrives from the server gets the local time it arrived, and keeps that time afterwards.
- Changes to a record made in the calendar app are not read back.
- The account screen offers sign-in, registration, sign-out, sync now, and the two conflict choices. Other settings are not included.
