-- Accounts, devices, and sessions.
CREATE TABLE users (
    id UUID PRIMARY KEY,
    email TEXT NOT NULL CONSTRAINT users_email_key UNIQUE CHECK (char_length(email) BETWEEN 3 AND 254),
    password_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE devices (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
    platform TEXT NOT NULL CHECK (char_length(platform) BETWEEN 1 AND 32),
    created_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX devices_user_id_idx ON devices (user_id);

-- Only the SHA-256 of a session token is stored. A device session carries device_id and can sync.
CREATE TABLE sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_id UUID REFERENCES devices (id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL CONSTRAINT sessions_token_hash_key UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CHECK (expires_at > created_at)
);
CREATE INDEX sessions_user_id_idx ON sessions (user_id);
CREATE INDEX sessions_device_id_idx ON sessions (device_id);

-- Current state of each synced record. version is the number of accepted changes to that record.
CREATE TABLE sync_entities (
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    entity_type TEXT NOT NULL CHECK (entity_type IN ('routine', 'routine_step', 'task', 'schedule', 'preference')),
    entity_id UUID NOT NULL,
    version BIGINT NOT NULL CHECK (version >= 1),
    deleted BOOLEAN NOT NULL,
    payload JSONB,
    last_change_seq BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, entity_type, entity_id),
    CHECK (deleted = (payload IS NULL))
);

-- Append-only log of accepted changes. seq is the download cursor. A UNIQUE(user_id, change_id) key makes a retried
-- upload return its original result instead of applying twice.
CREATE TABLE sync_changes (
    seq BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    change_id UUID NOT NULL,
    device_id UUID REFERENCES devices (id) ON DELETE SET NULL,
    entity_type TEXT NOT NULL CHECK (entity_type IN ('routine', 'routine_step', 'task', 'schedule', 'preference')),
    entity_id UUID NOT NULL,
    operation TEXT NOT NULL CHECK (operation IN ('UPSERT', 'DELETE')),
    base_version BIGINT NOT NULL CHECK (base_version >= 0),
    version BIGINT NOT NULL CHECK (version >= 1),
    payload JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK ((operation = 'UPSERT') = (payload IS NOT NULL)),
    CONSTRAINT sync_changes_change_id_key UNIQUE (user_id, change_id),
    CONSTRAINT sync_changes_entity_version_key UNIQUE (user_id, entity_type, entity_id, version)
);
CREATE INDEX sync_changes_user_seq_idx ON sync_changes (user_id, seq);
