# Privacy and data deletion

This describes what the RoutinePilot app and its optional sync backend (`backend/`) do with data. It is not legal advice. Whoever runs a backend is responsible for that backend's users.

## What stays on the device

Everything below is kept on the device unless the user deletes it or uninstalls the app.

- **Routines, steps, tasks, and schedules**, with the agenda's occurrences, execution sessions, and step completions. Room database `routinepilot.db`.
- **Planning**: working hours, breaks, planned tasks, and the applied plan for each day. DataStore and Room. Planning is not synced.
- **Calendar export**: the on/off choice, destination calendar, all-day choice, and the link between each schedule and its calendar event. The events themselves are written to the calendar provider on this device.
- **Sync account state**: the signed-in email, the owning account, the device identifier, the sync cursor, and the time of the last sync. Session tokens are encrypted with an AES-GCM key held in the Android Keystore.
- **Unsent sync changes** (the outbox) and the last server state of each synced record. Room.
- **Onboarding**: one flag that records whether the introduction was finished or skipped.

Android's automatic backup is disabled for the app, so this data is not uploaded to a cloud backup by the app.

## What is sent to a server

Nothing is sent unless the user signs in to an account. Signing in is optional, and the app works fully offline without it.

When sync is on, the app sends:

- **Account**: the email address, and the password over HTTPS. The server stores the email address and a salted PBKDF2-HMAC-SHA256 hash of the password. It never stores the password itself.
- **Device**: a device name of "Android device" and the platform "android".
- **Synced records** (the fields are listed in `backend/src/main/kotlin/com/routinepilot/backend/domain/Sync.kt` and `app/.../domain/sync/SyncModel.kt`):
  - routines: title, description, and position;
  - routine steps: routine, title, description, duration in seconds, and position;
  - tasks: title, completion state, routine, and position;
  - schedules: the routine or task, the repeat rule (daily, weekdays, or every few days), the time of day in minutes, the start date, and the time zone identifier.
- **Operational data**: the client's network address, used for rate limits and held only in memory. Every accepted change is kept in the server's change log with its time and the device that made it.

**Not sent:** calendar events, execution history, step completion events, planning data, contacts, location, advertising identifiers, or analytics. The app contains no analytics or crash-reporting library, which can be checked in `gradle/libs.versions.toml`.

Session tokens are random values. The server stores only their SHA-256 hashes, so a database read does not reveal a usable token.

## Permissions and what they are for

- **Calendar (read and write)**: asked for only when the user turns calendar export on. The app reads only the events it created, which it finds by their identifier, and it creates, updates, and deletes only those events. It never reads other events.
- **Notifications**: to show the step and countdown of a running routine.
- **Boot completed**: to restore reminders after the phone restarts. No data leaves the device.
- **Foreground service**: to keep a running routine's timer alive while the app is in the background.

## How long data is kept

- **On the device**: until the user deletes it or uninstalls the app. Uninstalling removes the app's storage, but calendar events the app created remain in the calendar app until they are removed. Use "Remove exported events" in calendar settings before uninstalling to delete them.
- **On a server**: until the account is deleted. The change log is never compacted in this version, so it grows with use.
- **Rate-limit counters**: in memory only, and reset when the server restarts.
- **Backups of a server database**: the operator's responsibility. Data that has been deleted can remain in a backup until that backup expires.

## How to delete data

| What | How |
| --- | --- |
| A routine, step, task, or schedule | Delete it in the app. Synced deletions are sent to the server like any other change. |
| One device's access to the account | Account screen, then Sign out. The app tries to remove the device from the account. If the device is offline, the device session expires on its own, and the app says so. |
| The account and all its synced data | Account screen, then Delete account. Enter the current password, then confirm. The server deletes the account, its devices, its sessions, and its records. The app erases the routines, schedules, history, and queued changes on the device. Changes that never reached the server are lost. |
| Calendar events the app created | Calendar settings, then Remove exported events. Turning export off asks first and keeps your events in the calendar. |
| Everything on the device | Uninstall the app, after removing calendar events as above. |

Account deletion needs the device to reach the server. If it cannot, nothing is erased on the device, and the app says that the server was not reached. Account deletion on a self-hosted backend is also available to its operator through the API documented in `backend/README.md`.

## Security measures

- Release builds send data only over HTTPS. Cleartext traffic is refused by the network security configuration. Debug builds allow it only for the emulator's host address.
- Session tokens are encrypted at rest on the device. Passwords are hashed on the server with PBKDF2, at 600,000 iterations by default.
- The backend limits request rates, validates every field before storing it, and runs as a non-root user in its container.
- App data is excluded from automatic backup.

## Open items

- No public privacy policy URL, and no store data-safety declaration, has been published. This document is the source text for both.
- Rights under specific laws (for example, access or portability requests) are not covered here.
