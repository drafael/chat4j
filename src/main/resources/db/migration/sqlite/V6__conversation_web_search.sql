ALTER TABLE conversations ADD COLUMN web_search_enabled INTEGER DEFAULT 0;
ALTER TABLE conversations ADD COLUMN web_search_option TEXT;

UPDATE conversations SET web_search_enabled = 0 WHERE web_search_enabled IS NULL;
