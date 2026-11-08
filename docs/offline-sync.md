# Offline change tracking and conflict resolution

Status: the device-side model, the Room outbox, and the merge rules are written. They have not been compiled or run in the environment that wrote them, because it has no JDK or Android SDK. The sync client is not connected to the backend (`backend/`). Those are the next steps.

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

- **Local app data is not yet reconciled from the merged view.** The merge produces the view the user should see (`MergeResult.fields`). Writing that view back into the Room tables is the sync integration step. `SimulatedDevice` does this for the tests only.
- **Duplicate step positions** can result from concurrent reordering. The display sort must break ties by step id. That sort is not implemented in this change.
- **Schedules** share the generic merge and the field mapping. The field names of the recurrence kinds are not checked against the backend's validator yet.
- **No network.** The transport is a seam (`SyncTransport`). The tests use `FakeSyncServer`, which copies the backend's rules, not the backend itself.
- **Room schema.** The Room schema for version 7 is generated on the first build and must be reviewed and committed then.
- **Not run.** No Kotlin compiler, JDK, Android SDK, or Gradle was available. The JVM tests in `domain/sync` are written, not executed.
