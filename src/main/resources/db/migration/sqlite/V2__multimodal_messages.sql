ALTER TABLE messages ADD COLUMN content_json TEXT;
ALTER TABLE messages ADD COLUMN meta_json TEXT;

CREATE TABLE IF NOT EXISTS attachments (
    id            TEXT PRIMARY KEY,
    storage_path  TEXT NOT NULL,
    original_name TEXT,
    mime_type     TEXT,
    size_bytes    INTEGER DEFAULT 0,
    sha256        TEXT,
    created_at    TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS message_attachments (
    message_id    TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    attachment_id TEXT NOT NULL REFERENCES attachments(id) ON DELETE CASCADE,
    part_index    INTEGER NOT NULL,
    PRIMARY KEY (message_id, part_index)
);

UPDATE messages
SET content_json = '[{"type":"text","text":""}]'
WHERE content_json IS NULL AND content IS NULL;
