-- name: CreateRefreshToken :exec
INSERT INTO refresh_tokens (user_id, token_hash, family_id, expires_at)
VALUES ($1, $2, $3, $4);

-- name: GetRefreshTokenByHash :one
SELECT * FROM refresh_tokens WHERE token_hash = $1;

-- name: RevokeRefreshToken :execrows
UPDATE refresh_tokens SET revoked_at = now()
WHERE id = $1 AND revoked_at IS NULL;

-- name: RevokeTokenFamily :exec
UPDATE refresh_tokens SET revoked_at = now()
WHERE family_id = $1 AND revoked_at IS NULL;

-- name: RevokeAllUserTokens :exec
UPDATE refresh_tokens SET revoked_at = now()
WHERE user_id = $1 AND revoked_at IS NULL;