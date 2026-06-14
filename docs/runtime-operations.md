# Runtime Operations

This document covers startup, logging, and packaged-app environment behavior.

## Startup sequence

Entry point: `src/main/java/com/github/drafael/chat4j/App.java` delegates to `ApplicationBootstrap.start()`.

Startup order:

1. Configure platform integration before Swing/AWT use.
2. Configure early FlatLaf look and feel.
3. Run `EnvironmentBootstrapper.initialize()`.
4. Resolve chat storage backend, run pending storage migration if needed, initialize data source, Flyway, and repositories.
5. Apply saved appearance.
6. Initialize JCEF with a modal progress dialog when the configured/fallback web-view path may need Chromium.
7. Create and show `MainFrame` on the EDT.
8. Show non-blocking environment warning if needed.

Key classes:

- `ApplicationBootstrap` — high-level startup coordinator.
- `EnvironmentBootstrapper` — macOS jpackage environment loading and warning decision.
- `JcefStartupInitializer` — optional startup JCEF initialization with progress reporting.
- `AppServices` — startup-created services passed into `MainFrame`.
- `EnvironmentInitResult` — loaded shell env and warning flag.

Add future startup concerns as explicit named steps in `ApplicationBootstrap.start()`.

## Chat storage backend

Chat persistence supports SQLite and H2. SQLite is the default backend. The active backend is stored in `chat.storage.backend.active`; a Settings UI change writes `chat.storage.backend.pending` and takes effect the next time Chat4J starts.

Files live under the app config directory:

```text
<app-config>/data/chat4j.mv.db      # H2
<app-config>/data/chat4j.sqlite3    # SQLite
<app-config>/db.credentials         # H2 credentials
```

Startup migrates `active -> pending` before `MainFrame` is created, using backend-specific Flyway migrations in `db/migration/h2` and `db/migration/sqlite`. Existing unconfigured H2 storage is migrated to SQLite on first startup with the SQLite default. Migration copies logical chat rows into staged target files, verifies table counts, promotes the staged database, and keeps existing target files in `data/backups/`.

## macOS jpackage environment loading

macOS `.app` bundles launched from Dock/Finder run under `launchd`, not the user's shell. Shell profile variables such as `ANTHROPIC_API_KEY` and `OPENAI_API_KEY` are often missing from `System.getenv()`.

Chat4J handles this only for macOS jpackage launches:

```text
ApplicationBootstrap
  -> EnvironmentBootstrapper
    -> ShellEnvironmentLoader
      -> /bin/zsh -l -i -c env
    -> CredentialResolver.init(shellEnv)
```

Detection:

```java
System.getProperty("jpackage.app-path") != null && SystemInfo.isMacOS
```

Lookup policy throughout provider code:

```text
CredentialResolver.getenv(name)
  -> System.getenv(name)
  -> shellEnv.get(name)
```

Important details:

- `-l -i` is intentional: many users put API keys in `.zshrc`, which is read only by interactive shells.
- Timeout defaults to 5 seconds; override with `-Dchat4j.shellEnvTimeoutSeconds=<n>`.
- The loader falls back gracefully and Chat4J continues launching.
- If no provider credentials are visible, Chat4J shows a warning suggesting terminal launch, `launchctl setenv`, Ollama, or doctor diagnostics.

Troubleshooting commands:

```bash
/bin/zsh -l -i -c "env" | grep ANTHROPIC_API_KEY
launchctl setenv ANTHROPIC_API_KEY "sk-..."
bash "/Applications/Chat4J.app/Contents/app/tools/chat4j-doctor.sh" --app "/Applications/Chat4J.app"
```

Common failure causes:

- Variable is assigned but not exported in `.zshrc`.
- Shell profile starts interactive tools (`tmux`, prompts, `ssh-agent`) and times out.
- Gatekeeper/signing/quarantine failure occurs before the JVM starts; use `chat4j-doctor.sh`.

## Logging

Stack:

- API: SLF4J
- Backend: Logback
- Code pattern: Lombok `@Slf4j`
- Config: `src/main/resources/logback.xml`

Log level precedence:

1. JVM property `chat4j.log.level`
2. Environment variable `CHAT4J_LOG_LEVEL`
3. macOS shell-loaded environment
4. `INFO`

Supported values: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`.

Destinations:

- Console when launched from terminal.
- Rotating file: `${chat4j.log.dir}/chat4j.log`.
- Default log dir: `$XDG_CONFIG_HOME/chat4j/logs`, else `~/.config/chat4j/logs`.
- Emergency bootstrap fallback: `~/.config/chat4j/logs/bootstrap-fallback.log`.

Noise dampening in `logback.xml`:

- `org.flywaydb` → `WARN`
- `org.h2` → `WARN`
- `okhttp3` → `WARN`

JCEF native stderr notes:

- JCEF/Chromium may write directly to native stderr before Java logging can intercept it.
- On macOS, `NativeStderrNoiseFilter` removes the known bare `Exception in thread "AppKit Thread"` fragment from fd `2`; other stderr output is preserved.
- JCEF logs are configured at `LOGSEVERITY_FATAL` for normal runs.

Enable DEBUG locally:

```bash
mvn -q -DskipTests exec:java -Dchat4j.log.level=DEBUG
CHAT4J_LOG_LEVEL=DEBUG mvn -q -DskipTests exec:java
```
