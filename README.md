# Chat4J

A lightweight desktop AI chat client built with **Java 21**, **Swing**, and **Maven**.

Chat4J supports multiple LLM providers (OpenAI-compatible, Anthropic, GitHub Copilot, and local runtimes), streams responses token-by-token, and stores conversation history locally using H2.

## Features

- Native desktop UI (Swing + FlatLaf)
- Streaming assistant responses
- Multi-provider model selector
- Conversation sidebar with:
  - grouped history (Today, Yesterday, This Week, This Month, Older)
  - favorites
  - rename / delete
- Settings dialog:
  - appearance (themes + accent colors)
  - provider status and base URLs
  - general preferences
- Local persistence (H2) for:
  - conversations + messages
  - app settings
- Flyway SQL migrations (`src/main/resources/db/migration`)

## Supported providers

Enable one or more providers by setting API keys in your environment:

- `ANTHROPIC_API_KEY`
- `GEMINI_API_KEY` (aliases: `GOOGLEAI_API_KEY`, `GOOGLE_AI_API_KEY`)
- `OPENAI_API_KEY`
- `OPENROUTER_API_KEY`
- `GROQ_API_KEY`
- `DEEPSEEK_API_KEY`
- `MISTRAL_API_KEY`
- `XAI_API_KEY`
- **OpenAI Codex** via Chat4J OAuth device login
- **GitHub Copilot** via Chat4J OAuth device login (no `gh auth` dependency)
- **LM Studio** — local OpenAI-compatible server at `localhost:1234`, no API key needed
- **Ollama** — local models at `localhost:11434`, no API key needed

If no provider key is available and no local provider (LM Studio/Ollama) is running, the model list will be empty and chat requests cannot be sent.

### GitHub Copilot OAuth setup

Chat4J Copilot login requires a GitHub OAuth App client ID. Configure one of:

- JVM property: `chat4j.copilot.oauthClientId`
- Environment variable: `CHAT4J_COPILOT_OAUTH_CLIENT_ID`
- Build property resource key: `copilotOAuthClientId` (from `build.properties`)

Optional scope override (default is least-privilege `read:user user:email`):

- JVM property: `chat4j.copilot.oauthScopes`

### OpenAI Codex OAuth setup

Chat4J Codex login requires an OAuth client ID. Configure one of:

- JVM property: `chat4j.codex.oauthClientId`
- Environment variable: `CHAT4J_CODEX_OAUTH_CLIENT_ID`
- Build property resource key: `codexOAuthClientId` (from `build.properties`)

Optional overrides:

- OAuth issuer: `chat4j.codex.oauthIssuer` (default: `https://auth.openai.com`)
- OAuth scopes: `chat4j.codex.oauthScopes`

## Requirements

- Java 21+
- Maven 3.9+

## Run locally

```bash
# from project root
mvn clean compile
mvn exec:java
```

## Build

```bash
# build fat jar
mvn clean package
```

Then run:

```bash
java --enable-preview -jar target/chat4j-<version>.jar
```

> Note: `--enable-preview` is kept for consistency with project build config.

## CalVer and Git hooks

Chat4J uses CalVer in `YY.M.N` format (example: `26.4.0`).

- `YY` = 2-digit year
- `M` = month (`1-12`)
- `N` = monthly build counter
- Counter resets monthly and starts at `0`

A pre-commit hook updates `pom.xml` and `.buildnumber` on every commit.

Set up hooks once per clone:

```bash
git config core.hooksPath .githooks
```

> If you bypass hooks with `--no-verify`, builds or CI checks may fail due to invalid or stale version values.

## Tests

```bash
mvn test
```

## Documentation

- [docs/README.md](docs/README.md) — documentation index
- [docs/copilot-auth-device-flow.md](docs/copilot-auth-device-flow.md) — current Copilot auth/runtime behavior
- [docs/codex-auth-device-flow.md](docs/codex-auth-device-flow.md) — current Codex auth/runtime behavior
- [docs/copilot-integration-header-behavior.md](docs/copilot-integration-header-behavior.md) — Copilot header-routing evidence and curl proofs
- [docs/calver.md](docs/calver.md) — Current CalVer format, pre-commit bump behavior, and hook setup

## Configuration and data storage

Chat4J stores its local database in:

- `$XDG_CONFIG_HOME/chat4j/data/chat4j.mv.db`
- fallback when `XDG_CONFIG_HOME` is not set: `~/.config/chat4j/data/chat4j.mv.db`

Database credentials are stored in:

- `$XDG_CONFIG_HOME/chat4j/db.credentials`
- fallback when `XDG_CONFIG_HOME` is not set: `~/.config/chat4j/db.credentials`

Chat4J uses this location directly and does not perform automatic migration from older paths.

Schema creation and upgrades are managed by Flyway on startup.

## Keyboard shortcuts

- **New chat**: `Cmd+N` (macOS) / `Ctrl+N`
- **Settings**: `Cmd+,` / `Ctrl+,`
- **Toggle sidebar**: `Cmd+B` / `Ctrl+B`

## Packaging

Native installers are built with `jpackage` via Maven profiles. Each profile must be run **on the target OS** — cross-packaging is not supported by `jpackage`.

```bash
# macOS (.dmg)
mvn -Pjpackage-mac package

# Windows (.msi) — requires WiX Toolset 3.x
mvn -Pjpackage-win package

# Linux (.deb) — requires dpkg; use --type rpm in pom.xml for RPM
mvn -Pjpackage-linux package
```

Output goes to `target/dist/`.

## License

This project is licensed under the **Apache License 2.0**.
See [LICENSE](LICENSE).
