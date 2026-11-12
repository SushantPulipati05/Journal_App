-- name: CreateAttachment :one
-- Idempotent: creating the same id again returns the existing row. An id that
-- belongs to another user matches nothing, so no row comes back (pgx.ErrNoRows).
INSERT INTO attachments (id, user_id, entry_id, content_type, size_bytes, width, height, position)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
ON CONFLICT (id) DO UPDATE SET id = attachments.id
WHERE attachments.user_id = EXCLUDED.user_id
RETURNING *;

-- name: GetAttachment :one
SELECT * FROM attachments WHERE id = $1 AND user_id = $2;

-- name: CountEntryAttachments :one
SELECT count(*) FROM attachments
WHERE entry_id = $1 AND user_id = $2 AND deleted_at IS NULL AND id <> $3;

-- name: MarkAttachmentUploaded :one
UPDATE attachments
SET uploaded = true, size_bytes = $3, version = nextval('entries_version_seq')
WHERE id = $1 AND user_id = $2 AND deleted_at IS NULL
RETURNING *;

-- name: DeleteAttachment :one
UPDATE attachments
SET deleted_at = now(), version = nextval('entries_version_seq')
WHERE id = $1 AND user_id = $2 AND deleted_at IS NULL
RETURNING *;

-- name: DeleteEntryAttachments :many
-- When an entry is deleted, its photos go with it.
UPDATE attachments
SET deleted_at = now(), version = nextval('entries_version_seq')
WHERE entry_id = $1 AND user_id = $2 AND deleted_at IS NULL
RETURNING id;

-- name: ListAttachmentChanges :many
-- Like ListEntryChanges. Photos still being uploaded are left out until done.
SELECT * FROM attachments
WHERE user_id = sqlc.arg(user_id)
  AND version > sqlc.arg(since)
  AND (uploaded OR deleted_at IS NOT NULL)
ORDER BY version ASC
LIMIT sqlc.arg(page_size);

-- name: ListUserAttachmentIDs :many
-- Every photo of an account, for removing the files when the account is deleted.
SELECT id FROM attachments WHERE user_id = $1;
