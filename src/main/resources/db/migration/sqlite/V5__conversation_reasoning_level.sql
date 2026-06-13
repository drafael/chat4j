ALTER TABLE conversations
    ADD COLUMN reasoning_level TEXT DEFAULT 'off';

UPDATE conversations
SET reasoning_level = 'off'
WHERE reasoning_level IS NULL;
