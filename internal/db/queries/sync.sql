-- name: UpsertEntry :one
-- Insert a client's version of an entry, or update the server's copy, but only
-- when the client's updated_at is strictly newer (last write wins). Rows
-- belonging to another user never match the WHERE, so they are never touched.
-- When nothing is inserted or updated, no row comes back (pgx.ErrNoRows).
INSERT INTO entries (id, user_id, title, body, created_at, updated_at, deleted_at)
VALUES ($1, $2, $3, $4, $5, $6, $7)
ON CONFLICT (id) DO UPDATE
SET title      = EXCLUDED.title,
    body       = EXCLUDED.body,
    updated_at = EXCLUDED.updated_at,
    deleted_at = EXCLUDED.deleted_at,
    version    = nextval('entries_version_seq')
WHERE entries.user_id = EXCLUDED.user_id
  AND EXCLUDED.updated_at > entries.updated_at
RETURNING *;

-- name: GetEntryIncludingDeleted :one
-- Like GetEntry, but also finds tombstones. Used to tell "stale" from "not yours".
SELECT * FROM entries
WHERE id = $1 AND user_id = $2;

-- name: ListEntryChanges :many
-- Everything that changed after the client's cursor, tombstones included,
-- oldest change first.
SELECT * FROM entries
WHERE user_id = sqlc.arg(user_id)
  AND version > sqlc.arg(since)
ORDER BY version ASC
LIMIT sqlc.arg(page_size);
