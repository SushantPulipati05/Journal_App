-- +goose Up

-- Encryption at rest: each user's random data key, stored encrypted ("wrapped")
-- with the master key from ENCRYPTION_MASTER_KEY. Deleting the account deletes
-- the key, which makes every copy of their entries unreadable, backups included.
CREATE TABLE user_keys (
    user_id            UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    wrapped_key        BYTEA NOT NULL,
    master_key_version INT NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Semantic search, step 1: entries waiting to be embedded. One row per entry;
-- saving again just resets it. The worker "leases" a job by pushing run_after
-- into the future, and deletes it when done (only if queued_at hasn't changed,
-- i.e. nobody saved the entry again in the meantime).
CREATE TABLE embed_jobs (
    entry_id   UUID PRIMARY KEY,
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    attempts   INT NOT NULL DEFAULT 0,
    run_after  TIMESTAMPTZ NOT NULL DEFAULT now(),
    queued_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error TEXT
);

CREATE INDEX embed_jobs_run_after_idx ON embed_jobs (run_after);

-- Semantic search, step 2: one vector per paragraph-sized chunk of an entry.
-- The chunk text itself is NOT stored (it would be readable); only a keyed
-- hash, to notice which paragraphs changed. The vectors stay unencrypted
-- because they have to be compared.
CREATE TABLE entry_chunks (
    entry_id    UUID NOT NULL,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    chunk_index INT NOT NULL,
    text_hash   BYTEA NOT NULL,
    model       TEXT NOT NULL,
    embedding   REAL[] NOT NULL,
    PRIMARY KEY (entry_id, chunk_index)
);

CREATE INDEX entry_chunks_user_idx ON entry_chunks (user_id);

-- +goose Down
DROP TABLE entry_chunks;
DROP TABLE embed_jobs;
DROP TABLE user_keys;
