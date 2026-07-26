-- Legacy H2 TIMESTAMP values are interpreted in the migration-time database/JVM zone,
-- converted once to epoch milliseconds, and are UTC-stable thereafter.
CREATE TABLE v7_stable_message_prerequisites (
    invalid_row_count INT NOT NULL,
    CONSTRAINT chk_v7_stable_message_prerequisites CHECK (invalid_row_count = 0)
);
INSERT INTO v7_stable_message_prerequisites (invalid_row_count)
SELECT COUNT(*) FROM messages
WHERE id IS NULL OR conversation_id IS NULL OR created_at IS NULL;
DROP TABLE v7_stable_message_prerequisites;

ALTER TABLE messages ADD COLUMN IF NOT EXISTS ordinal INT;

UPDATE messages
SET role = CASE
    WHEN role IS NULL OR TRIM(role) = '' OR UPPER(role) NOT IN ('USER', 'ASSISTANT', 'SYSTEM') THEN 'USER'
    ELSE UPPER(role)
END;

UPDATE messages m
SET ordinal = (
    SELECT COUNT(*)
    FROM messages earlier
    WHERE earlier.conversation_id = m.conversation_id
      AND (earlier.created_at < m.created_at
        OR (earlier.created_at = m.created_at AND earlier.id <= m.id))
);

ALTER TABLE messages ALTER COLUMN id SET NOT NULL;
ALTER TABLE messages ALTER COLUMN conversation_id SET NOT NULL;
ALTER TABLE messages ALTER COLUMN role SET NOT NULL;
ALTER TABLE messages ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE messages ALTER COLUMN ordinal SET NOT NULL;

ALTER TABLE messages ALTER COLUMN created_at BIGINT
    USING CAST(FLOOR(EXTRACT(EPOCH FROM CAST(created_at AS TIMESTAMP WITH TIME ZONE)) * 1000) AS BIGINT);

ALTER TABLE messages ADD CONSTRAINT chk_messages_ordinal_positive CHECK (ordinal > 0);
ALTER TABLE messages ADD CONSTRAINT uq_messages_conversation_ordinal UNIQUE (conversation_id, ordinal);
DROP INDEX IF EXISTS idx_messages_conversation;
CREATE INDEX idx_messages_conversation_ordinal ON messages(conversation_id, ordinal);
