# Chat4J

Lightweight desktop AI chat client built with **Java 21**, **Swing**, and **Maven**.

## Quick start

```bash
mvn clean compile
mvn exec:java
```

## Build & test

```bash
mvn clean package
mvn test
```

Run packaged jar:

```bash
java --enable-preview -jar target/chat4j-<version>.jar
```

## What it does

- Desktop chat UI (Swing + FlatLaf)
- Multi-provider model selection
- Streaming assistant responses
- Local history/settings persistence (H2 + Flyway)
- Agent Mode with local workspace tools

## Supported providers

### API-key providers (env vars)

- `ANTHROPIC_API_KEY`
- `GEMINI_API_KEY` (aliases: `GOOGLEAI_API_KEY`, `GOOGLE_AI_API_KEY`)
- `OPENAI_API_KEY`
- `OPENROUTER_API_KEY`
- `GROQ_API_KEY`
- `DEEPSEEK_API_KEY`
- `MISTRAL_API_KEY`
- `XAI_API_KEY`

### OAuth providers

- **OpenAI Codex**
- **GitHub Copilot**

### Local providers

- **LM Studio** — OpenAI-compatible server at `http://localhost:1234/v1` (no API key required)
- **Ollama** — OpenAI-compatible endpoint at `http://localhost:11434/v1` (no API key required)

## Documentation

- [docs/README.md](docs/README.md) — full documentation index

## Packaging

Native installers are built with `jpackage` via Maven profiles.
Each profile must run on the target OS (cross-packaging is not supported).

Use OS-specific profiles:

```bash
# macOS (.dmg)
mvn -Pjpackage-mac verify

# Windows (.msi) — requires WiX Toolset 3.x
mvn -Pjpackage-win verify

# Linux (.deb) — requires dpkg
mvn -Pjpackage-linux verify
```

Output artifacts are written to `target/dist/`.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
