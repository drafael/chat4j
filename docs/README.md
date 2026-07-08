# Chat4J Documentation Index

Use this page as the canonical entry point for implementation and operational details.

## Core runtime

- [provider-capability-architecture.md](provider-capability-architecture.md)
  - Provider subsystem architecture, module/capability design, and extension guidance.

- [provider-auth-runtime.md](provider-auth-runtime.md)
  - Copilot and Codex authentication, runtime routing, token storage, and provider-specific troubleshooting.

- [agent-mode.md](agent-mode.md)
  - Agent Mode behavior, adapter selection, and fallback/runtime rules.

- [chat-rendering.md](chat-rendering.md)
  - Swing HTML Renderer, System WebView, JCEF, fallback behavior, math/diagram/chem rendering, packaging, and validation.

- [text-to-speech.md](text-to-speech.md)
  - Read aloud architecture, provider settings, credentials, playback, UI behavior, and testing expectations.

- [speech-to-text.md](speech-to-text.md)
  - Speech to Text settings, Groq, ElevenLabs, Deepgram, and AssemblyAI cloud transcription, Vosk local model management/transcription, recording UX, privacy, and cancellation.

- [configurable-web-view-engine.md](configurable-web-view-engine.md)
  - Short architecture note for the configurable WebView boundary.

## Operations and packaging

- [runtime-operations.md](runtime-operations.md)
  - Startup flow, configurable chat storage, macOS jpackage shell-environment loading, logging, and diagnostics.

- [calver.md](calver.md)
  - CalVer versioning, pre-commit bump flow, and packaging version alignment.

- [ui-platform-notes.md](ui-platform-notes.md)
  - macOS HiDPI font rules and app icon regeneration notes.

## Notices

- [../THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)
  - Bundled web assets, GraalJS Community, and Vosk runtime license notes.
