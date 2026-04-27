ALTER TABLE conversations
    ADD COLUMN IF NOT EXISTS reasoning_level VARCHAR(20) DEFAULT 'off';

UPDATE conversations
SET reasoning_level = 'off'
WHERE reasoning_level IS NULL;
