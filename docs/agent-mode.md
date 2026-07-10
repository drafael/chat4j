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
- Tool invocations render as compact activity bubbles, for example `✓ read note.txt`.
- Provider-service paths that cannot expose per-tool events render a compact context bubble, for example `✓ workspace-context project — using workspace snapshot`.
- Redacted tool activity summaries are persisted with assistant messages and restored when a conversation is loaded.

## Settings

General settings include:

- **Prompt addendum** (appended to default Agent Mode system prompt)

Stored setting key:

- `chat4j.chat.agent.systemPromptAppend`

Agent Mode toggle state and selected folder are stored per conversation in the local database.

## Tool activity UI and persistence

Agent Mode emits redacted activity events for tool starts, successes, failures, and skipped loop-guard batches.

- `AgentToolActivity` carries status, tool name, invocation id, safe argument summary, and optional message.
- `AgentRunCallbacks.onToolActivity` lets adapters and the orchestrator stream live activity to the chat UI.
- `ChatPanel` renders one compact, non-collapsible `ActivityBubble` per invocation, keyed by invocation id.
- Tool bubbles render before the final assistant answer when tools run before the answer.
- `MessageMeta.agentToolActivities` stores the latest redacted status per invocation so history restore recreates the bubbles.
- Tool output and sensitive raw arguments are not displayed by default; `bash` commands are redacted before display.

## Runtime architecture

Core orchestrator: `chat.agent.AgentOrchestrator`

- Validates selected folder exists.
- Executes provider turns through adapter factory.
- Runs tool loop with max `8` rounds.
- Executes local tools through `LocalToolRuntime` with root confinement.

Filesystem behavior is based on `java.nio.file` checks, normalization, and real-path validation to prevent symlink escapes. Local process execution uses `zt-exec` and runs with the selected root as the process working directory.

## Provider adapter strategy

Adapter factory: `AgentProviderAdapterFactory`

- **Anthropic**: native tool adapter (`AnthropicToolAgentAdapter`).
- **OpenAI-compatible providers**: `OpenAiToolAgentAdapter`.
  - Preserves provider reasoning fields such as DeepSeek `reasoning_content` across tool-result turns when required by the API.
- **OpenAI Codex**: direct provider-service/Codex CLI path (`ProviderServiceAgentAdapter`).
- **GitHub Copilot Claude models**: Copilot bearer-auth variant of `AnthropicToolAgentAdapter` against `/v1/messages`, wrapped by `OpenAiCompatibleFallbackAgentAdapter`.
- **Google AI / other GitHub Copilot models**: wrapped by `OpenAiCompatibleFallbackAgentAdapter` around `OpenAiToolAgentAdapter`.
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

## Safety and scope

- Tool filesystem operations (`read`, `write`, `edit`, `ls`, `find`, `grep`) are confined to selected root.
- `bash` executes with selected root as working directory, but is not sandboxed and can access files outside selected root with the Chat4J app user's OS permissions.
- All `bash` tool invocations are logged at INFO level.
- Filesystem root escape attempts are logged at WARN level; `bash` outside-root detection is best-effort because shell behavior cannot be parsed reliably without sandboxing.
- v1 behavior has no supervised approval flow UI.
