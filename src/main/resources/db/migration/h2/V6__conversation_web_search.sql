ALTER TABLE conversations ADD COLUMN IF NOT EXISTS web_search_enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS web_search_option VARCHAR(80);

UPDATE conversations SET web_search_enabled = FALSE WHERE web_search_enabled IS NULL;
