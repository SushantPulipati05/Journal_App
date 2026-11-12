-- +goose Up
-- Photos attached to entries. The image bytes live in object storage
-- (Backblaze B2 / any S3-compatible bucket); this table holds what each device needs to know about them.
-- The id comes from the device (like entries), so a photo can be created offline.
CREATE TABLE attachments (
    id            UUID PRIMARY KEY,
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    entry_id      UUID NOT NULL,
    content_type  TEXT NOT NULL,
    size_bytes    BIGINT NOT NULL,
    width         INT NOT NULL,
    height        INT NOT NULL,
    position      INT NOT NULL DEFAULT 0,
    -- false until the device has finished uploading the file
    uploaded      BOOLEAN NOT NULL DEFAULT false,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ,
    -- shares the entries' version sequence, so sync works the same way
    version       BIGINT NOT NULL DEFAULT nextval('entries_version_seq')
);

CREATE INDEX attachments_user_version_idx ON attachments (user_id, version);
CREATE INDEX attachments_entry_idx ON attachments (entry_id);

-- +goose Down
DROP TABLE attachments;
