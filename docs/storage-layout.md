# Storage layout

Chat4J separates configuration, durable data, replaceable cache files, and application state on non-Windows systems. An XDG override is used only when it is an absolute path.

| Scope | XDG override | Default root | Contents under `chat4j/` |
|---|---|---|---|
| Configuration | `XDG_CONFIG_HOME` | `~/.config` | `chat4j.properties`, `mcp.json` |
| Data | `XDG_DATA_HOME` | `~/.local/share` | databases, backups, attachments, prompts, credentials, OAuth sessions, installed speech models |
| Cache | `XDG_CACHE_HOME` | `~/.cache` | provider and speech catalogs, catalog metadata, JCEF files, temporary speech files |
| State | `XDG_STATE_HOME` | `~/.local/state` | logs, doctor reports, window placement |

Windows ignores XDG variables and keeps all four scopes under `%APPDATA%/chat4j`, or `%USERPROFILE%/AppData/Roaming/chat4j` when `%APPDATA%` is unavailable.

## Migration

At startup, Chat4J moves known files from the former config-only layout before opening databases, caches, credentials, JCEF, or speech storage.

- Durable data is never overwritten. A conflicting destination stops startup so both copies remain available for recovery.
- Durable sources containing symbolic links are rejected.
- Identical source remnants are removed, making migration safe to retry.
- Cache, temporary files, and old logs are migrated on a best-effort basis.
- Legacy window-placement keys move from `chat4j.properties` to the state home.
- Catalog snapshot pointers move from `chat4j.properties` to cache metadata.

Custom speech-model directories and explicit logging or JCEF overrides remain authoritative.
