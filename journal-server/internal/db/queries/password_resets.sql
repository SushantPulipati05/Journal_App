-- name: CreatePasswordReset :one
INSERT INTO password_resets (user_id, code_hash, expires_at)
VALUES ($1, $2, $3)
RETURNING *;

-- name: GetActivePasswordReset :one
SELECT * FROM password_resets
WHERE user_id = $1 AND used_at IS NULL AND expires_at > now()
ORDER BY created_at DESC
LIMIT 1;

-- name: RecordPasswordResetFailure :one
UPDATE password_resets
SET attempts = attempts + 1,
    used_at = CASE WHEN attempts + 1 >= 5 THEN now() ELSE used_at END
WHERE id = $1
RETURNING attempts;

-- name: MarkPasswordResetUsed :execrows
UPDATE password_resets SET used_at = now()
WHERE id = $1 AND used_at IS NULL;

-- name: InvalidatePasswordResets :exec
UPDATE password_resets SET used_at = now()
WHERE user_id = $1 AND used_at IS NULL;
