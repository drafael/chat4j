ALTER TABLE messages ADD COLUMN IF NOT EXISTS content_json CLOB;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS meta_json CLOB;

CREATE TABLE IF NOT EXISTS attachments (
    id            UUID PRIMARY KEY,
    storage_path  VARCHAR(1024) NOT NULL,
    original_name VARCHAR(255),
    mime_type     VARCHAR(120),
    size_bytes    BIGINT DEFAULT 0,
    sha256        VARCHAR(64),
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS message_attachments (
    message_id    UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    attachment_id UUID NOT NULL REFERENCES attachments(id) ON DELETE CASCADE,
    part_index    INT NOT NULL,
    PRIMARY KEY (message_id, part_index)
);

UPDATE messages
SET content_json = '[{"type":"text","text":""}]'
WHERE content_json IS NULL AND content IS NULL;
