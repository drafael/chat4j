CREATE TABLE IF NOT EXISTS conversations (
    id          TEXT PRIMARY KEY,
    title       TEXT,
    provider    TEXT,
    model       TEXT,
    is_favorite INTEGER DEFAULT 0,
    created_at  TEXT DEFAULT CURRENT_TIMESTAMP,
    updated_at  TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS messages (
    id              TEXT PRIMARY KEY,
    conversation_id TEXT REFERENCES conversations(id) ON DELETE CASCADE,
    role            TEXT,
    content         TEXT,
    created_at      TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_messages_conversation
    ON messages(conversation_id, created_at);

CREATE TABLE IF NOT EXISTS provider_configs (
    id             TEXT PRIMARY KEY,
    provider_name  TEXT UNIQUE,
    base_url       TEXT,
    selected_model TEXT,
    is_enabled     INTEGER DEFAULT 1
);

CREATE TABLE IF NOT EXISTS settings (
    "key"   TEXT PRIMARY KEY,
    "value" TEXT
);
