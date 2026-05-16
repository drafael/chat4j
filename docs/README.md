# Chat4J Documentation Index

Use this page as the canonical entry point for implementation and operational details.

## Core runtime and provider behavior

- [agent-mode.md](agent-mode.md)
  - Current Agent Mode behavior, adapter selection, and fallback/runtime rules.

- [provider-capability-architecture.md](provider-capability-architecture.md)
  - Provider subsystem architecture, module/capability design, and extension guidance.

- [configurable-web-view-engine.md](configurable-web-view-engine.md)
  - Message rendering boundary for future Swing/JCEF web-view engine selection.

- [copilot-auth-device-flow.md](copilot-auth-device-flow.md)
  - Current GitHub Copilot authentication and runtime behavior.

- [codex-auth-device-flow.md](codex-auth-device-flow.md)
  - Current OpenAI Codex OAuth (authorization code + PKCE) and runtime behavior.

- [copilot-integration-header-behavior.md](copilot-integration-header-behavior.md)
  - Header-routing evidence, curl proofs, and upstream reference links for Copilot model behavior.

## Operations, packaging, and versioning

- [logging.md](logging.md)
  - Current logging stack, level resolution, and operational logging behavior.

- [calver.md](calver.md)
  - Current CalVer versioning, pre-commit bump flow, and packaging alignment.

- [macos-jpackage-environment-variables.md](macos-jpackage-environment-variables.md)
  - Why environment variables are missing in macOS `.app` launches and how Chat4J works around it.

## Additional guides

- [startup-architecture.md](startup-architecture.md)
  - Startup flow, bootstrap responsibilities, and extension points.

- [macos-hidpi-font-rendering.md](macos-hidpi-font-rendering.md)
  - Notes and guidance for macOS HiDPI font rendering behavior.

- [icon-creation-guide.md](icon-creation-guide.md)
  - Instructions for creating app icons and source assets.
