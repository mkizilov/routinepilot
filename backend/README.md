# RoutinePilot sync backend

A Kotlin service built with Ktor 3.0.3, on PostgreSQL 16. It is separately deployable: it has its own Gradle build in this directory and shares nothing at build time with the Android app. The Android app does not use it yet. Android synchronization is a later step.

## Status

**Not compiled or run in the environment that wrote it.** That environment had no JDK, Gradle, Docker daemon, or network, so none of the following was executed: the Kotlin build, the unit tests, the database tests, the API tests, the Docker image, or the compose stack. Every pinned artifact version was confirmed to exist on Maven Central. The Ktor 3.0.3 APIs used were checked against Ktor's published source, and the Testcontainers usage against its documentation. The PostgreSQL JDBC calls were not checked against any source. Run the verification steps below before relying on any of it.

## Stack and why

| Concern | Choice | Reason |
| --- | --- | --- |
| HTTP | Ktor 3.0.3 on Netty | Kotlin-first, coroutine-based, same Kotlin 2.0.21 as the app |
| Database | PostgreSQL 16 | Transactions, row locks for per-user ordering, JSONB payloads, identity sequences for the change cursor |
| Access | Plain JDBC with HikariCP | Explicit SQL that is easy to review, and no ORM version risk |
| Migrations | Numbered SQL scripts with an advisory lock | Small and transparent; no extra dependency |
| Passwords | PBKDF2-HMAC-SHA256, 600,000 iterations, 16-byte salt | JDK-provided, so no native library. The cost is stored per hash so it can be raised |
| Sessions | Opaque 256-bit random tokens, stored as SHA-256 | A leaked database does not reveal usable tokens. Revocation is immediate |
| Tests | JUnit 5, `testApplication`, Testcontainers | Real PostgreSQL and the real module, not mocks |

## API (version 1)

All paths start with `/v1`. Bodies and responses are JSON. Errors have the shape `{"error": {"code", "message", "field"?}}`.

| Method and path | Session | Success | Notes |
| --- | --- | --- | --- |
| `POST /v1/auth/register` | none | 201 token | Email is normalized. A taken email returns 409 `email_taken` |
| `POST /v1/auth/login` | none | 200 token | Wrong password and unknown email return the same 401 |
| `POST /v1/auth/logout` | any | 204 | Revokes the current session |
| `GET /v1/me` | any | 200 profile | |
| `PUT /v1/me/password` | account | 204 | Needs the current password. Signs out every other session |
| `POST /v1/me/delete` | account | 204 | Needs the password in the body (`{"password"}`). Deletes the account and all its data. A POST, because common HTTP clients, including the Android app's, cannot send a body with DELETE |
| `POST /v1/devices` | account | 201 device and device token | Each device gets its own session |
| `GET /v1/devices` | account | 200 list | Active devices only |
| `DELETE /v1/devices/{id}` | account | 204 | Revokes the device and its sessions at once |
| `POST /v1/sync/changes` | device | 200 per-change results | Uploads a batch of changes |
| `GET /v1/sync/changes?since=&limit=` | device | 200 page | Downloads the change log after a cursor |
| `GET /v1/records?entityType=&after=&limit=` | any | 200 page | Current live records, paged by id |
| `GET /health` | none | 200 | Liveness only |

### Session scopes

- An **account session** comes from login or registration. It manages the account and devices, and it can read records.
- A **device session** comes from `POST /v1/devices`. It can sync and read records. It cannot change the account or manage devices, and revoking the device ends it.
- Sessions last `rp.auth.sessionDays` (default 30) and then expire. There is no refresh token in this version.

## Sync model

The server stores versioned change records, not a copy of the database. Each synced record has a `version`, which counts the accepted changes to it.

**Upload.** A device sends a batch of changes (at most 200). Each change has:
- `changeId`, a client-generated UUID that makes retries safe,
- `entityType`: `routine`, `routine_step`, `task`, `schedule`, or `preference`,
- `entityId`,
- `operation`: `UPSERT` (needs a payload) or `DELETE` (no payload),
- `baseVersion`, the version the device last saw. `0` means the device has never seen the record.

The server applies a change only when `baseVersion` equals the current version. Otherwise it returns `CONFLICT` with the server's current version and payload, and the device decides what to keep. A change never overwrites a version the device has not seen.

Each result has a `status`:
- `ACCEPTED`: applied, with its new `version` and log `seq`.
- `DUPLICATE`: the `changeId` was already applied. The original `version` and `seq` are returned, and nothing is applied again.
- `CONFLICT`: the base version was stale. The server's current state is returned.
- `REJECTED`: the `changeId` was reused for a different change.

A batch is validated as a whole before anything is written. An invalid change rejects the whole batch with 400. Within a valid batch, each change is judged on its own, so conflicts do not block the rest.

**Download.** `GET /v1/sync/changes?since=<cursor>` returns accepted changes with `seq` greater than the cursor, in order. Pass the returned `cursor` back as `since`. `hasMore` says whether another page exists. A `DELETE` appears in the log as a tombstone with no payload.

**Ordering.** Every upload takes a row lock on the user, so one account's changes commit one at a time. The `seq` order therefore matches commit order for that account. A cursor cannot skip a change that another device was still writing.

**Records.** `GET /v1/records` returns the current live records, excluding tombstones. Use it to bootstrap a new device, then continue from the log.

**Limits.** At most 200 changes per batch. At most 16 KiB of payload per change. At most 1 MiB per request body (checked against the declared `Content-Length`). A download page has at most `rp.sync.pageSize` (default 500) records.

## Security

- **Transport.** Set `RP_REQUIRE_HTTPS=true` in production. Plain HTTP then gets `426 https_required`. HTTPS responses carry HSTS.
- **Reverse proxy.** Behind a proxy you control, set `RP_TRUST_FORWARDED_HEADERS=true`. The scheme and client address then come from `X-Forwarded-Proto` and `X-Forwarded-For`. Never set this when clients can reach the service directly, because they could forge those headers.
- **Passwords.** 12 to 128 characters. Only length is checked, as NIST SP 800-63B recommends. Hashes are salted PBKDF2. Verification is constant-time, and an unknown email takes as long to reject as a wrong password.
- **Rate limits.** Fixed windows, in memory, and each rejection returns `429` with `Retry-After`:

  | Limit | Key | Default |
  | --- | --- | --- |
  | `rp.rateLimit.authPerIp` | client address, on register and login | 30 per 60 s |
  | `rp.rateLimit.loginPerAccount` | email and client address, on login | 10 per 900 s |
  | `rp.rateLimit.apiPerSession` | session, on sync and records | 600 per 60 s |

- **Validation.** Every field is checked before storage. Payloads are checked against the rules for their entity type (`domain/Sync.kt`). Unknown fields are ignored.
- **Errors.** Internal failures return a generic `internal_error` and log the detail on the server.

### Known weaknesses

- **Registration reveals whether an email exists** (409 `email_taken`). The rate limit slows enumeration, but it does not remove it.
- **Rate limits are per process.** Several instances would each allow the full budget. A shared store is needed before scaling out.
- **No email verification and no password reset.** Neither is part of this version.
- **Access tokens are not rotated** and last the full session length.
- **The change log is never compacted.** It grows with every accepted change.

## Configuration

Values come from `src/main/resources/application.conf`, and environment variables override them.

| Variable | Purpose | Default |
| --- | --- | --- |
| `RP_PORT` | HTTP port | 8080 |
| `RP_SSL_PORT`, `RP_KEYSTORE_PATH`, `RP_KEYSTORE_PASSWORD`, `RP_KEY_ALIAS`, `RP_KEY_PASSWORD` | Direct HTTPS. Set `RP_SSL_PORT` only with a keystore | unset |
| `RP_DATABASE_URL` | JDBC URL | `jdbc:postgresql://localhost:5432/routinepilot` |
| `RP_DATABASE_USER` | Database user | `routinepilot` |
| `RP_DATABASE_PASSWORD` | Database password. **No default: the service will not connect until it is set** | empty |
| `RP_DATABASE_MAX_POOL` | Connection pool size | 10 |
| `RP_SESSION_DAYS` | Session lifetime, 1 to 90 | 30 |
| `RP_PASSWORD_ITERATIONS` | PBKDF2 cost, at least 100,000 | 600,000 |
| `RP_REQUIRE_HTTPS` | Refuse plain HTTP | false |
| `RP_TRUST_FORWARDED_HEADERS` | Trust `X-Forwarded-Proto` and `X-Forwarded-For` | false |

### TLS

Choose one:

1. **TLS at a proxy (recommended).** Terminate TLS at a reverse proxy you control, forward to the service on loopback, set `RP_REQUIRE_HTTPS=true` and `RP_TRUST_FORWARDED_HEADERS=true`. Use a current TLS configuration at the proxy.
2. **Direct TLS.** Create a keystore with a certificate for your host, for example with `keytool -genkeypair -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore rp.p12 -alias rp -validity 365`, then set `RP_SSL_PORT=8443`, `RP_KEYSTORE_PATH`, `RP_KEYSTORE_PASSWORD`, `RP_KEY_ALIAS=rp`, and `RP_KEY_PASSWORD`. Keep the keystore out of the repository.

The `ktor.security.ssl` keys follow the Ktor documentation. This path has not been run.

## Running

Local stack with PostgreSQL:

```sh
export RP_DATABASE_PASSWORD='choose-a-long-random-value'
docker compose up --build
curl -s http://127.0.0.1:8080/health
```

Without Docker, with a PostgreSQL you run yourself:

```sh
gradle installDist
export RP_DATABASE_PASSWORD=...   # plus RP_DATABASE_URL and RP_DATABASE_USER if they differ from the defaults
build/install/routinepilot-backend/bin/routinepilot-backend
```

The first `gradle` run needs a local Gradle 8.9 because this directory has no wrapper yet. Generate it once with `gradle wrapper --gradle-version 8.9`, the same way the Android project does.

Migrations run at startup. They are idempotent, and an advisory lock stops two instances from migrating at once.

## Deployment

This describes one host running the API and PostgreSQL. It has not been run end to end in this environment (see Status). CI builds the image but does not start it.

### Before you start

- A host with Docker and Docker Compose, and a DNS name for the service.
- A TLS-terminating reverse proxy you control, or a keystore for direct TLS (see TLS above).
- A long random database password. Generate one with `openssl rand -base64 32`.

### Steps

1. Create `backend/.env` (git ignores it) with the password, and the two proxy settings when a proxy is in front:

   ```sh
   RP_DATABASE_PASSWORD=the-generated-value
   RP_REQUIRE_HTTPS=true
   RP_TRUST_FORWARDED_HEADERS=true
   ```

2. Start the stack. The API listens on `127.0.0.1:8080` only, and PostgreSQL is not published to the host:

   ```sh
   cd backend
   docker compose up -d --build
   ```

3. Put the proxy in front. It must set `X-Forwarded-Proto` and `X-Forwarded-For`, and forward only to `127.0.0.1:8080`. Caddy's `reverse_proxy` sets both headers by default. With nginx, set them yourself with `proxy_set_header`. Trust forwarded headers only behind that proxy, because a client that can reach the service directly could forge them.

4. Check it, using the public address:

   ```sh
   curl -s https://sync.your-domain.example/health   # expect {"status":"ok"}
   ```

5. Point the app's release build at it, with `ROUTINEPILOT_SYNC_URL=https://sync.your-domain.example`. `docs/release.md` describes the build.

### Upgrades

- Migrations run at startup. They only move forward, so there is no downgrade path. Back up the database before every upgrade.
- Rebuild and restart: `docker compose up -d --build`. The advisory lock stops two instances from migrating at once, but run one instance at a time anyway, because rate limits are per process.
- If an upgrade fails, restore the backup taken before it, and run the previous image version against that restored database.

### Backups and restore

- Back up with `docker compose exec postgres pg_dump -U routinepilot routinepilot > routinepilot-$(date +%F).sql`. Store the file somewhere the API host cannot be the only copy.
- Test a restore on a scratch database before you rely on the backup: `docker compose exec -T postgres psql -U routinepilot -d scratch < routinepilot-DATE.sql`.
- Backups contain account email addresses and password hashes, and the synced records. Treat them as personal data.
- The change log is not compacted, so the database grows with use. Plan its storage and watch its size.

### Running it safely

- Keep `RP_DATABASE_PASSWORD` and any keystore password out of the repository and out of the image. Pass them through the environment or a secret store.
- The container runs as UID 10001, with a read-only root filesystem and `no-new-privileges`, as set in `docker-compose.yml`.
- Logs contain unhandled failures with the request path. They do not contain request bodies or passwords.
- `/health` reports liveness only. There is no metrics endpoint in this version.
- Run one instance. Rate limits are kept in memory, so several instances would each allow the full budget.

### Account deletion

A user deletes their account in the app, or with `POST /v1/me/delete` and an account session. The server removes the account, its devices, its sessions, and its records, through cascading foreign keys. Backups keep the deleted data until they expire.

### Not verified

- The Docker image has not been built or started in this environment, and the compose stack has not been run.
- The direct TLS path (keystore) has not been run.
- The reverse-proxy header handling has not been tested against a real proxy.
- Behaviour under load, and recovery from a database failure, have not been measured.

## Verification

```sh
cd backend
gradle test          # unit, database, and API tests; needs a Docker daemon for Testcontainers
gradle installDist   # distribution build
```

Test layout:

- `security/`: password hashing, token format, and rate limiting, with a fake clock.
- `domain/`: validation and the sync rules. This includes conflict decisions and per-entity payload checks.
- `db/` and `persistence/`: migrations, constraints, the change log, and cascades, against a real PostgreSQL. Each test rolls back.
- `api/`: the HTTP contract, end to end, including scopes, rate limits, HTTPS, conflicts, retries, paging, and limits.

The database and API tests need Docker. Without it, they fail with the Testcontainers error, and nothing is skipped silently. CI runs them on an Ubuntu runner, which has Docker.

The Docker image is built with `docker build -t routinepilot-backend backend`. The Gradle and JRE tags are pinned by version, but not by digest.
