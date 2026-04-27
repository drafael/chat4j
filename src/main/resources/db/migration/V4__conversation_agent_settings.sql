ALTER TABLE conversations
    ADD COLUMN IF NOT EXISTS agent_mode_enabled BOOLEAN DEFAULT FALSE;

ALTER TABLE conversations
    ADD COLUMN IF NOT EXISTS agent_project_root VARCHAR(1024);

UPDATE conversations
SET agent_mode_enabled = FALSE
WHERE agent_mode_enabled IS NULL;
