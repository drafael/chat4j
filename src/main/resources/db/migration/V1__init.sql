CREATE TABLE IF NOT EXISTS conversations (
    id          UUID PRIMARY KEY,
    title       VARCHAR(255),
    provider    VARCHAR(50),
    model       VARCHAR(100),
    is_favorite BOOLEAN DEFAULT FALSE,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS messages (
    id              UUID PRIMARY KEY,
    conversation_id UUID REFERENCES conversations(id) ON DELETE CASCADE,
    role            VARCHAR(10),
    content         TEXT,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_messages_conversation
    ON messages(conversation_id, created_at);

CREATE TABLE IF NOT EXISTS provider_configs (
    id             UUID PRIMARY KEY,
    provider_name  VARCHAR(50) UNIQUE,
    base_url       VARCHAR(255),
    selected_model VARCHAR(100),
    is_enabled     BOOLEAN DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS settings (
    "key"   VARCHAR(100) PRIMARY KEY,
    "value" VARCHAR(500)
);
