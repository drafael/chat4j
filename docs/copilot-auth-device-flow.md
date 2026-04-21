# Copilot Auth and Runtime (Current State)

This document describes the **current** GitHub Copilot authentication and runtime behavior in Chat4J.

## Authentication model

- Copilot provider auth type: `COPILOT_OAUTH`
- Token owner: Chat4J (not IDE reuse)
- Resolver: `CopilotAuthResolver`
- Stored token file: `~/.config/chat4j/copilot-auth.json`

## Login flow (device authorization)

When user clicks **Login** for GitHub Copilot in Settings:

1. Chat4J requests device/user codes from GitHub OAuth device-flow endpoint.
2. Chat4J automatically:
   - copies the user code to clipboard,
   - opens the verification URL in browser.
3. Settings shows progress text with fallback code/URL.
4. Chat4J polls token endpoint and stores the access token on success.

When already authorized, the same button performs **Log out** (deletes local token file).

## OAuth configuration

OAuth client ID resolution order:

1. JVM property: `chat4j.copilot.oauthClientId`
2. Environment variable: `CHAT4J_COPILOT_OAUTH_CLIENT_ID`
3. `build.properties` key: `copilotOAuthClientId`
4. bundled resource: `/oauth/chat4j-copilot-client-id.txt`

OAuth scopes:

- Default: `read:user user:email`
- Override: `chat4j.copilot.oauthScopes`

## Provider runtime wiring

- `ProviderFacade` resolves Copilot bearer token via `CopilotAuthResolver`.
- `ProviderRuntimePolicy` uses resolver status to determine availability.
- `ProvidersPanel` displays Copilot auth status and login/logout actions.

## Copilot request behavior

- Chat4J sends `Copilot-Integration-Id` header for Copilot API calls.
- Default integration id: `copilot-developer-cli`
- Override: `chat4j.copilot.integrationId`
- Chat4J does **not** send `Editor-Version` / `Editor-Plugin-Version` at runtime.

## Model and endpoint behavior

- Model catalog accepts Copilot models supporting:
  - `/chat/completions`
  - `/responses`
  - `ws:/responses`
- Chat runtime is responses-capable and caches endpoint mode per model.
- Current default strategy is **responses-first**, with automatic fallback when endpoint is unsupported.

## Diagnostics and evidence

For reproducible curl proofs, upstream code permalinks, and verified external docs links:

- `docs/copilot-integration-header-behavior.md`
