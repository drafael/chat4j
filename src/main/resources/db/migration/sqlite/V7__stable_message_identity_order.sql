-- SQLite CURRENT_TIMESTAMP text is UTC. Validate stable-entry prerequisites before
-- rebuilding so legacy corruption fails at a named, readable boundary.
CREATE TEMP TABLE v7_stable_message_prerequisites (
    invalid_row_count INTEGER NOT NULL,
    CONSTRAINT chk_v7_stable_message_prerequisites CHECK (invalid_row_count = 0)
);
INSERT INTO v7_stable_message_prerequisites (invalid_row_count)
SELECT COUNT(*) FROM messages
WHERE id IS NULL
   OR conversation_id IS NULL
   OR created_at IS NULL
   OR julianday(created_at) IS NULL;
DROP TABLE v7_stable_message_prerequisites;

-- Rebuild the tables so all stable-entry constraints and attachment foreign keys
-- are preserved with foreign_keys enabled.
CREATE TABLE messages_v7 (
    id              TEXT NOT NULL PRIMARY KEY,
    conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role            TEXT NOT NULL,
    content         TEXT,
    created_at      INTEGER NOT NULL CHECK (typeof(created_at) = 'integer'),
    content_json    TEXT,
    meta_json       TEXT,
    ordinal         INTEGER NOT NULL CHECK (typeof(ordinal) = 'integer' AND ordinal > 0),
    UNIQUE (conversation_id, ordinal)
);

INSERT INTO messages_v7 (
    id, conversation_id, role, content, created_at, content_json, meta_json, ordinal
)
SELECT id,
       conversation_id,
       CASE
           WHEN role IS NULL OR TRIM(role) = '' OR UPPER(role) NOT IN ('USER', 'ASSISTANT', 'SYSTEM') THEN 'USER'
           ELSE UPPER(role)
       END,
       content,
       CAST(ROUND((julianday(created_at) - 2440587.5) * 86400000) AS INTEGER),
       content_json,
       meta_json,
       ROW_NUMBER() OVER (PARTITION BY conversation_id ORDER BY julianday(created_at), id)
FROM messages;

CREATE TABLE message_attachments_v7 (
    message_id    TEXT NOT NULL REFERENCES messages_v7(id) ON DELETE CASCADE,
    attachment_id TEXT NOT NULL REFERENCES attachments(id) ON DELETE CASCADE,
    part_index    INTEGER NOT NULL,
    PRIMARY KEY (message_id, part_index)
);

INSERT INTO message_attachments_v7 (message_id, attachment_id, part_index)
SELECT message_id, attachment_id, part_index FROM message_attachments;

DROP TABLE message_attachments;
DROP TABLE messages;
ALTER TABLE messages_v7 RENAME TO messages;
ALTER TABLE message_attachments_v7 RENAME TO message_attachments;

DROP INDEX IF EXISTS idx_messages_conversation;
CREATE INDEX idx_messages_conversation_ordinal ON messages(conversation_id, ordinal);

-- A bare PRAGMA only returns rows and cannot fail Flyway. Materialize the count
-- through a CHECK constraint so any surviving violation aborts the migration.
CREATE TEMP TABLE v7_foreign_key_validation (
    violation_count INTEGER NOT NULL CHECK (violation_count = 0)
);
INSERT INTO v7_foreign_key_validation (violation_count)
SELECT COUNT(*) FROM pragma_foreign_key_check;
DROP TABLE v7_foreign_key_validation;
