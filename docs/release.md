# Release preparation

Status: the release configuration, signing hooks, and CI workflow are written. No release build has been produced or run in the environment that wrote them, because it had no working JDK, Android SDK, or Gradle. Every step below is unverified until a machine with those tools runs it.

## Build types

| Build | Sync address | Cleartext HTTP | Minification | Signing |
| --- | --- | --- | --- | --- |
| debug | `http://10.0.2.2:8080`, the emulator's view of the host | Allowed only for `10.0.2.2` and `localhost`, by a debug-only network security config | Off | Debug key |
| release | `ROUTINEPILOT_SYNC_URL`, or the Gradle property `routinepilot.syncBaseUrl` | Refused by the main network security config | Off (see below) | `RP_*` environment variables, if set |

- A release sync address must start with `https://`. The build fails otherwise.
- An empty release sync address turns sync off. Sign-in then reports that the service is unavailable, and nothing is sent.
- **Minification is off.** The keep rules for Room, Hilt, Glance, and kotlinx-serialization were not verified, and a release that breaks only when minified would be invisible until a device run. Turn `isMinifyEnabled` on only after a minified release has been run on a device.
- **Backups are off** (`android:allowBackup="false"`). Local data is tied to this device's Keystore key and to the sync session, so a restored copy would not match its tokens.

## Version

`versionCode` is 1 and `versionName` is `0.1.0` in `app/build.gradle.kts`. Raise `versionCode` for every release that is uploaded, because stores reject a code that does not increase.

## Signing

The commands on this page use `./gradlew`, which needs the Gradle wrapper. The wrapper is not committed yet. Generate it once with `gradle wrapper --gradle-version 8.9`.

Gradle reads signing from environment variables only. Nothing is written into the repository, and `keystore.properties`, `*.jks`, `*.keystore`, `*.p12`, and `.env` are ignored by git.

1. Create the upload keystore outside the repository:

   ```sh
   keytool -genkeypair -v -keystore ~/routinepilot-release.jks -alias routinepilot \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Store the keystore file and both passwords in a password manager. A lost upload key blocks every future update.

3. Build a signed bundle on your machine:

   ```sh
   export RP_KEYSTORE_FILE="$HOME/routinepilot-release.jks"
   export RP_KEYSTORE_PASSWORD='the store password'
   export RP_KEY_ALIAS=routinepilot
   export RP_KEY_PASSWORD='the key password'
   export ROUTINEPILOT_SYNC_URL='https://your-sync-host.example'   # omit to turn sync off
   ./gradlew bundleRelease
   ```

   The bundle is `app/build/outputs/bundle/release/app-release.aab`. Without `RP_KEYSTORE_FILE`, the same command produces an unsigned bundle.

## CI release

`.github/workflows/release.yml` runs on a `v*` tag, or by hand. It runs the unit tests first, then builds the bundle. It needs these settings in the repository:

| Kind | Name | Value |
| --- | --- | --- |
| Secret | `RP_KEYSTORE_BASE64` | The keystore file, base64-encoded on one line |
| Secret | `RP_KEYSTORE_PASSWORD` | The store password |
| Secret | `RP_KEY_ALIAS` | The key alias |
| Secret | `RP_KEY_PASSWORD` | The key password |
| Variable | `ROUTINEPILOT_SYNC_URL` | The HTTPS sync address, or empty |

Encode the keystore with `base64 -i ~/routinepilot-release.jks | tr -d '\n'` on macOS, or `base64 -w0 ~/routinepilot-release.jks` on Linux. The workflow decodes it into the runner's temporary directory and deletes it at the end. Secrets reach Gradle only as environment variables.

## Checks before a release is published

- [ ] `./gradlew spotlessCheck detekt testDebugUnitTest` passes.
- [ ] `./gradlew connectedDebugAndroidTest` passes on an API 34 emulator. This includes the migration, outbox persistence, and WorkManager scheduling tests.
- [ ] A signed bundle is converted to an APK with `bundletool` and installed on a physical device.
- [ ] On that device: sign in to a test deployment over HTTPS, sync a routine, sign out, sign in again, and delete the test account. Local data should be gone after deletion.
- [ ] A release build refuses an `http://` sync address at build time, and the app refuses cleartext at run time.
- [ ] Run notifications, boot restore, calendar permission prompts, and the home-screen widgets work on the device.
- [ ] `versionCode` has been raised.
- [ ] The store's data-safety form matches `docs/privacy.md`.

## Known gaps

- Minification is off (see above).
- No store listing, screenshots, or privacy policy URL have been prepared. `docs/privacy.md` is the source text for them.
- Notification, boot, calendar, and widget behaviour have not been checked on a device.
- TLS relies on the device's system trust store. Certificate pinning is not used.
