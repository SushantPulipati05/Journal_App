-- name: CreateEntry :one
INSERT INTO entries (id, user_id, title, body, created_at, updated_at)
VALUES ($1, $2, $3, $4, now(), now())
RETURNING *;

-- name: GetEntry :one
SELECT * FROM entries
WHERE id = $1 AND user_id = $2 AND deleted_at IS NULL;

-- name: ListEntries :many
SELECT * FROM entries
WHERE user_id = $1 AND deleted_at IS NULL
ORDER BY created_at DESC
LIMIT $2 OFFSET $3;

-- name: UpdateEntry :one
UPDATE entries
SET title = $3,
    body = $4,
    updated_at = now(),
    version = nextval('entries_version_seq')
WHERE id = $1 AND user_id = $2 AND deleted_at IS NULL
RETURNING *;

-- name: SoftDeleteEntry :execrows
UPDATE entries
SET deleted_at = now(),
    updated_at = now(),
    version = nextval('entries_version_seq')
WHERE id = $1 AND user_id = $2 AND deleted_at IS NULL;