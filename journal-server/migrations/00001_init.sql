-- +goose Up
CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         TEXT NOT NULL UNIQUE,
    password_hash TEXT,
    google_sub    TEXT UNIQUE,
    display_name  TEXT NOT NULL DEFAULT '',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE SEQUENCE entries_version_seq;

CREATE TABLE entries (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title       TEXT NOT NULL DEFAULT '',
    body        TEXT NOT NULL DEFAULT '',
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    deleted_at  TIMESTAMPTZ,
    version     BIGINT NOT NULL DEFAULT nextval('entries_version_seq')
);

CREATE INDEX entries_user_version_idx ON entries (user_id, version);

-- +goose Down
DROP TABLE entries;
DROP SEQUENCE entries_version_seq;
DROP TABLE users;