# Codex Auth and Runtime (Current State)

This document describes the **current** OpenAI Codex authentication and runtime behavior in Chat4J.

## Current status summary

- Authentication is **Chat4J-owned OAuth** (`CODEX_OAUTH`).
- Provider availability in UI/runtime is driven by `CodexAuthResolver` status.
- Runtime is currently **compatibility-hybrid**:
  - chat requests use `CodexCliChatCompletionClient` for Codex,
  - model listing tries HTTP first, then falls back to local Codex cache when needed.

This hybrid mode is intentional while temporary/unsupported client-id reuse can produce tokens without required OpenAI API scopes.

## Authentication model

- Codex provider auth type: `CODEX_OAUTH`
- Token owner: Chat4J
- Resolver: `CodexAuthResolver`
- Stored token file: `~/.config/chat4j/codex-auth.json`

Chat4J does **not** rely on `~/.codex/auth.json` as the source of truth for Codex auth.

## Login flow (device authorization)

When user clicks **Login** for OpenAI Codex in Settings:

1. Chat4J requests device login code from the configured Codex OAuth issuer.
2. Chat4J automatically:
   - copies the one-time code to clipboard,
   - opens the verification URL in browser.
3. Settings shows progress text with fallback code/URL.
4. Chat4J polls for completion and exchanges the login result for a usable bearer token when possible.
5. Chat4J stores the resolved token in `~/.config/chat4j/codex-auth.json`.

When already authorized, the same button performs **Log out** (deletes local token file).

## OAuth configuration

OAuth client ID resolution order:

1. JVM property: `chat4j.codex.oauthClientId`
2. Environment variable: `CHAT4J_CODEX_OAUTH_CLIENT_ID`
3. `build.properties` key: `codexOAuthClientId`
4. bundled resource: `/oauth/chat4j-codex-client-id.txt`

Optional overrides:

- OAuth issuer (default `https://auth.openai.com`):
  - JVM property: `chat4j.codex.oauthIssuer`
- OAuth scopes:
  - JVM property: `chat4j.codex.oauthScopes`

Endpoint overrides:

- Device code request endpoint: `chat4j.codex.deviceUserCodeEndpoint`
- Device token polling endpoint: `chat4j.codex.deviceTokenEndpoint`
- OAuth token exchange endpoint: `chat4j.codex.oauthTokenEndpoint`

## Provider/runtime wiring

- `ProviderFacade` resolves Codex bearer token via `CodexAuthResolver`.
- `ProviderRuntimePolicy` uses resolver status to determine Codex availability.
- `ProvidersPanel` shows Codex auth status and login/logout actions.

## Chat and model runtime behavior

### Chat

- Codex is currently routed to `CodexCliChatCompletionClient` in `OpenAiCompatibleModule`.
- This keeps Codex usable even when temporary OAuth tokens are missing required HTTP API scopes.

### Models

- Primary path: OpenAI-compatible `/v1/models` via `OpenAiModelCatalogClient`.
- Fallback for Codex when HTTP listing fails or returns empty:
  - `~/.codex/models_cache.json`
- Chat4J then persists the result in its provider cache:
  - `~/.config/chat4j/models-cache/OpenAI_Codex.txt`

If model picker appears empty after auth changes, clear the Chat4J Codex model cache file and refresh provider models.

## Known limitation

In local temporary/unsupported client-id reuse setups, issued tokens can miss required scopes (for example `api.model.read` / `api.responses.write`). In that case:

- pure HTTP Codex runtime is unreliable,
- hybrid fallback is used to keep Codex functional.

With official API-capable OAuth credentials/scopes, Codex can move back to full HTTP-only runtime.
