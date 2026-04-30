# Copilot Auth and Runtime

This document describes GitHub Copilot authentication and runtime behavior in Chat4J.

## Authentication model

- Copilot provider auth type: `COPILOT_OAUTH`
- Token owner: Chat4J (not IDE reuse)
- Resolver: `CopilotAuthResolver`
- Stored token file: `~/.config/chat4j/copilot-auth.json`

## Login flow (device authorization)

When user clicks **Login** for GitHub Copilot in Settings:

1. Chat4J starts GitHub OAuth device flow (`/login/device/code`).
2. Chat4J automatically:
   - copies the user code to clipboard,
   - opens the verification URL in browser.
3. Settings shows a modeless login dialog with the user code, verification URL, **Copy code**, and **Open browser** actions.
4. Chat4J polls GitHub OAuth token endpoint (`/login/oauth/access_token`) for the GitHub user token while the dialog remains available.
5. Chat4J exchanges that token at Copilot token endpoint (`/copilot_internal/v2/token`) to obtain the Copilot session token (`token`, `expires_at`).
6. Chat4J closes the login dialog after completion and stores:
   - session token as `accessToken`
   - GitHub token as `refreshToken` (used for re-exchange)
   - `expiresAtEpochMs`

When already authorized, the same button performs **Log out** (deletes local token file).

## Runtime token refresh behavior

- At token resolution time, if `expiresAtEpochMs` is expired and `refreshToken` exists,
  Chat4J re-exchanges `refreshToken` via `/copilot_internal/v2/token` and updates local storage.
- This keeps chat/model runtime aligned with Copilot session-token semantics.

## OAuth configuration

OAuth client ID resolution order:

1. JVM property: `chat4j.copilot.oauthClientId`
2. Environment variable: `CHAT4J_COPILOT_OAUTH_CLIENT_ID`
3. `build.properties` key: `copilotOAuthClientId` (populated from `pom.xml`)
4. bundled resource fallback: `/oauth/chat4j-copilot-client-id.txt`

OAuth scopes:

- Default: `read:user`
- Override: `chat4j.copilot.oauthScopes`

Optional enterprise domain:

- JVM property: `chat4j.copilot.enterpriseDomain`
- Environment variable: `CHAT4J_COPILOT_ENTERPRISE_DOMAIN`

Optional endpoint overrides (primarily for testing/custom setups):

- Device code endpoint: `chat4j.copilot.deviceCodeEndpoint`
- OAuth access token endpoint: `chat4j.copilot.accessTokenEndpoint`
- Copilot session token endpoint: `chat4j.copilot.tokenEndpoint`

## Provider runtime wiring

- `ProviderFacade` resolves Copilot bearer token via `CopilotAuthResolver`.
- `ProviderRuntimePolicy` uses resolver status to determine availability.
- `ProvidersPanel` displays simplified Copilot auth status (`Authorized` / `Not authorized`) and login/logout actions.

## Copilot request behavior

- Chat4J sends `Copilot-Integration-Id` header for Copilot API calls.
- Default integration id: `copilot-developer-cli`
- Override: `chat4j.copilot.integrationId`
- Chat4J does **not** send `Editor-Version` / `Editor-Plugin-Version` at runtime chat calls.

## Model and endpoint behavior

- Model catalog accepts Copilot models supporting HTTP chat endpoints:
  - `/chat/completions`
  - `/responses`
- Websocket-only declarations such as `ws:/responses` are filtered out from selectable models and are not treated as runnable endpoints.
- Chat4J persists Copilot `supported_endpoints` metadata from `/models` and carries it into runtime model selection.
- Chat runtime prefers the endpoint declared by Copilot for the selected model, then caches observed fallback results per model.
- Automatic fallback remains in place when runtime metadata is missing or stale.

## Reference evidence

The Copilot provider settings panel intentionally omits inline diagnostics. For reproducible curl proofs, upstream code permalinks, and verified external docs links:

- `docs/copilot-integration-header-behavior.md`