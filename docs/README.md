# Chat4J Documentation Index

Use this page as the canonical entry point for implementation and operational details.

## Core runtime

- [provider-capability-architecture.md](provider-capability-architecture.md)
  - Provider subsystem architecture, module/capability design, and extension guidance.

- [provider-auth-runtime.md](provider-auth-runtime.md)
  - Copilot and Codex authentication, runtime routing, token storage, and provider-specific troubleshooting.

- [agent-mode.md](agent-mode.md)
  - Agent Mode behavior, adapter selection, and fallback/runtime rules.

- [jcef-intellij-findings.md](jcef-intellij-findings.md)
  - IntelliJ/JBCEF implementation findings, native JCEF caveats, and Chat4J production guidance.

- [chat-rendering.md](chat-rendering.md)
  - `JEditorPane` / SwingWebView rendering, settings, fallback behavior, math/chem rendering, packaging, and validation.

- [configurable-web-view-engine.md](configurable-web-view-engine.md)
  - Message rendering boundary and dev-only JCEF spike notes.

## Operations and packaging

- [runtime-operations.md](runtime-operations.md)
  - Startup flow, macOS jpackage shell-environment loading, logging, and diagnostics.

- [calver.md](calver.md)
  - CalVer versioning, pre-commit bump flow, and packaging version alignment.

- [ui-platform-notes.md](ui-platform-notes.md)
  - macOS HiDPI font rules and app icon regeneration notes.

## Notices

- [../THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)
  - Bundled KaTeX/mhchem and GraalJS Community license notes.
