-- name: CreateUser :one
INSERT INTO users (email, password_hash, display_name)
VALUES ($1, $2, $3)
RETURNING *;

-- name: GetUserByID :one
SELECT * FROM users WHERE id = $1;

-- name: GetUserByEmail :one
SELECT * FROM users WHERE email = $1;

-- name: GetUserByGoogleSub :one
SELECT * FROM users WHERE google_sub = $1;

-- name: CreateGoogleUser :one
INSERT INTO users (email, google_sub, display_name)
VALUES ($1, $2, $3)
RETURNING *;

-- name: LinkGoogleAccount :one
UPDATE users SET google_sub = $2, updated_at = now()
WHERE id = $1
RETURNING *;

-- name: DeleteUser :execrows
DELETE FROM users WHERE id = $1;