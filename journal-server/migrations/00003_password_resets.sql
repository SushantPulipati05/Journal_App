-- +goose Up
-- One row per "forgot password" request. We store only a hash of the
-- 6-digit code, never the code itself.
CREATE TABLE password_resets (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash   TEXT NOT NULL,
    attempts    INT NOT NULL DEFAULT 0,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX password_resets_user_idx ON password_resets (user_id, created_at DESC);

-- +goose Down
DROP TABLE password_resets;
