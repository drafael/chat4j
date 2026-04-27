# Agent Mode

## Overview

Agent Mode lets Chat4J run multi-step workspace tasks with local tools (`read`, `write`, `edit`, `ls`, `find`, `grep`, `bash`) inside a selected folder.

The selected folder is treated as a **workspace root** and can contain any content type (code, docs, notes, configs, datasets, mixed assets). Agent Mode does not assume software-project-only structure.

## UI behavior

- Agent Mode toggle is shown only when the selected model is tool-capable (`ProviderCapabilityResolver.supportsToolInvocation(...)`).
- Enabling Agent Mode requires selecting a valid folder.
- Composer shows selected folder as a button (click to change folder) only while Agent Mode is enabled.
- Agent Mode button is icon-only in composer (`/icons/input/agent.svg`).
- Composer status shows `Full access` when Agent Mode is active.

## Settings

General settings include:

- **Prompt addendum** (appended to default Agent Mode system prompt)

Stored key (`SettingsKeys`):

- `chat4j.chat.agent.systemPromptAppend`

Agent Mode toggle state and selected folder are stored per conversation in the local database.

## Runtime architecture

Core orchestrator: `chat.agent.AgentOrchestrator`

- Validates selected folder exists.
- Executes provider turns through adapter factory.
- Runs tool loop with max `8` rounds.
- Executes local tools through `LocalToolRuntime` with root confinement.

Local process execution uses `zt-exec`; filesystem behavior is based on `java.nio.file` checks and normalization.

## Provider adapter strategy

Adapter factory: `AgentProviderAdapterFactory`

- **Anthropic**: native tool adapter (`AnthropicToolAgentAdapter`).
- **OpenAI-compatible providers**: `OpenAiToolAgentAdapter`.
- **OpenAI Codex**: wrapped by `CodexFallbackAgentAdapter`.
  - Falls back to provider-service/Codex CLI path on quota/auth failures (401/403/429, `insufficient_quota`).
- **Google AI / GitHub Copilot**: wrapped by `OpenAiCompatibleFallbackAgentAdapter`.
  - Falls back when tool endpoint/protocol fails (for example HTTP 400 invalid argument/unsupported tool behavior).
  - Falls back when first turn returns planning-only text without actual tool activity.

## Fallback behavior and folder visibility

When provider-native tool calling is unavailable, fallback mode still gets folder context:

- `ProviderServiceAgentAdapter` sets execution directory context to selected root.
- Fallback path prepends a workspace snapshot message with:
  - selected root path,
  - top-level entries,
  - sampled readable file excerpts (truncated).

This keeps non-native-tool providers useful for arbitrary folders, not only code repos.

## Prompt model

Shared builder: `AgentSystemPromptBuilder`

- Produces default Agent Mode system prompt for tool adapters.
- Appends user-configured **Prompt addendum**.
- Includes current date and selected working directory.
- Codex fallback uses dedicated fallback prompt notes (read-only discovery guidance).

## Safety and scope

- Tool filesystem operations are confined to selected root.
- `bash` executes with selected root as working directory.
- v1 behavior is full-access within selected root (no supervised approval flow UI in current state).
