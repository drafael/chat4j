# Logging

This document describes logging behavior in Chat4J.

## Logging stack

- API: **SLF4J**
- Backend: **Logback**
- Logger pattern in code: Lombok **`@Slf4j`**
- Runtime config file: `src/main/resources/logback.xml`

## Log level resolution

Chat4J resolves its application log level in this precedence order:

1. JVM system property: `chat4j.log.level`
2. Environment variable: `CHAT4J_LOG_LEVEL`
3. macOS shell-loaded environment (second startup pass)
4. fallback: `INFO`

Supported values are case-insensitive:
- `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`

Invalid values silently fall back to `INFO`.

## Scope of dynamic level

Dynamic level changes are applied to:
- `com.github.drafael.chat4j`

Global root logging remains:
- `INFO`

## Log destinations

By default, logs are written to:

- Console (when launched from terminal)
- Rotating file log:
  - `${chat4j.log.dir}/chat4j.log`

If `chat4j.log.dir` is not set, Chat4J initializes it to:

- `$XDG_CONFIG_HOME/chat4j/logs` when `XDG_CONFIG_HOME` is available
- fallback: `~/.config/chat4j/logs`

For macOS app-bundle launches (`open /Applications/Chat4J.app` or Dock), use the file log path above to inspect startup failures.

Additionally, Chat4J writes an emergency fallback startup log before full SLF4J initialization:

- `~/.config/chat4j/logs/bootstrap-fallback.log`

This file captures fatal bootstrap failures and includes a `chat4j-doctor` command hint.

## Noise dampening

`logback.xml` explicitly sets:

- `org.flywaydb` → `WARN`
- `org.h2` → `WARN`
- `okhttp3` → `WARN`

## Runtime behavior highlights

Chat4J emits operational logs for key lifecycle and provider/model paths:

- Startup lifecycle stage markers with elapsed time
- Environment bootstrap summary (shell env size, credential presence, warning flag)
- Provider runtime configuration apply summary
- Provider availability/model summary (resolved/authenticated/available and per-provider model counts)
- OAuth login/logout lifecycle for Copilot and Codex
- Model fetch fallback decisions (HTTP/cache recovery)
- Streaming/chat failure summaries with provider/model/conversation context
- Conversation load failure summaries

## Error/warning style

Warnings/errors are logged as concise messages, usually with Apache Commons Lang:
- `ExceptionUtils.getMessage(e)`

This keeps WARN output readable and avoids stack-trace noise in normal operation.

## Examples

Enable DEBUG for Chat4J package:

```bash
mvn -q -DskipTests exec:java -Dchat4j.log.level=DEBUG
```

Or via environment variable:

```bash
CHAT4J_LOG_LEVEL=DEBUG mvn -q -DskipTests exec:java
```
