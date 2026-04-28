# Codex Auth and Runtime

This document describes OpenAI Codex authentication and runtime behavior in Chat4J.

## Current status summary

- Authentication is **Chat4J-owned OAuth** (`CODEX_OAUTH`).
- Provider availability in UI/runtime is driven by `CodexAuthResolver` status.
- Runtime remains **hybrid by design**:
  - chat requests use `CodexCliChatCompletionClient` for Codex,
  - model listing tries HTTP first, then falls back to local Codex cache when needed.
- Agent Mode uses **Codex CLI-first adapter selection** for OpenAI Codex, not OpenAI-compatible tool-calling first.

## Authentication model

- Codex provider auth type: `CODEX_OAUTH`
- Token owner: Chat4J
- Resolver: `CodexAuthResolver`
- Stored token file: `~/.config/chat4j/codex-auth.json`

Chat4J does **not** rely on `~/.codex/auth.json` as the source of truth for Codex auth.

## Login flow (OAuth authorization code + PKCE)

When user clicks **Login** for OpenAI Codex in Settings:

1. Chat4J generates PKCE verifier/challenge and OAuth state.
2. Chat4J opens browser to the OAuth authorize URL.
3. Chat4J starts a local callback listener (default redirect URI host/port/path).
4. If callback cannot complete, Chat4J prompts for manual pasted redirect URL/code.
5. Chat4J exchanges authorization code for token(s), then resolves a usable bearer token.
6. Chat4J stores token metadata in `~/.config/chat4j/codex-auth.json`.

When already authorized, the same button performs **Log out** (deletes local token file).

## OAuth configuration

OAuth client ID resolution order:

1. JVM property: `chat4j.codex.oauthClientId`
2. Environment variable: `CHAT4J_CODEX_OAUTH_CLIENT_ID`
3. `build.properties` key: `codexOAuthClientId` (populated from `pom.xml`)
4. bundled resource fallback: `/oauth/chat4j-codex-client-id.txt`

Defaults in current build are set in `pom.xml` and mapped to `build.properties`.

Optional overrides:

- OAuth issuer (default `https://auth.openai.com`):
  - `chat4j.codex.oauthIssuer`
- OAuth scopes:
  - `chat4j.codex.oauthScopes`
- OAuth originator:
  - `chat4j.codex.oauthOriginator`

Endpoint/flow overrides:

- OAuth authorize endpoint: `chat4j.codex.oauthAuthorizeEndpoint`
- OAuth token endpoint: `chat4j.codex.oauthTokenEndpoint`
- OAuth redirect URI: `chat4j.codex.oauthRedirectUri`
- OAuth callback host binding: `chat4j.codex.oauthCallbackHost`
- Login wait timeout seconds: `chat4j.codex.oauthLoginTimeoutSeconds`

## Provider/runtime wiring

- `ProviderFacade` resolves Codex bearer token via `CodexAuthResolver`.
- `ProviderRuntimePolicy` uses resolver status to determine Codex availability.
- `ProvidersPanel` shows Codex auth status and login/logout actions.

## Chat and model runtime behavior

### Chat

- Codex chat runtime is routed to `CodexCliChatCompletionClient` in `OpenAiCompatibleModule`.
- This keeps Codex usable even when direct HTTP API scope/token constraints exist.

### Models

- Primary path: OpenAI-compatible `/v1/models` via `OpenAiModelCatalogClient`.
- Fallback for Codex when HTTP listing fails or returns empty:
  - `~/.codex/models_cache.json`
- Chat4J then persists the result in its provider cache:
  - `~/.config/chat4j/models-cache/OpenAI_Codex.txt`

If model picker appears empty after auth changes, clear the Chat4J Codex model cache file and refresh provider models.

## Agent Mode note

For provider `OpenAI Codex`, `AgentProviderAdapterFactory` selects `ProviderServiceAgentAdapter` directly.
This avoids attempting OpenAI-compatible Codex tool-calling first and prevents expected quota/auth fallback warnings in normal Codex agent runs.