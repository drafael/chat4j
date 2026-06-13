ALTER TABLE conversations
    ADD COLUMN agent_mode_enabled INTEGER DEFAULT 0;

ALTER TABLE conversations
    ADD COLUMN agent_project_root TEXT;

UPDATE conversations
SET agent_mode_enabled = 0
WHERE agent_mode_enabled IS NULL;
