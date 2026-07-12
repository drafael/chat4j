# Provider Authentication and Runtime Notes

This document collects provider-specific auth/runtime behavior that is not covered by the general provider architecture.

## API token vault

API-key providers (`AuthType.ENV_VAR`) resolve credentials in this order: saved UI token override, process environment, shell-loaded environment, then provider fallback. Saved UI tokens are stored under the app config directory in `secrets/token-vault.json`, encrypted with an app-local AES-256-GCM master key in `secrets/master.key`.

The vault is intentionally app-local convenience encryption, not OS keychain storage. Anyone who can read both secret files can decrypt saved API tokens. Saved tokens are never injected into `CredentialResolver.mergedEnvironment()` or subprocess environments.

OAuth providers such as GitHub Copilot and OpenAI Codex keep their existing auth files and are not stored in this vault.

## GitHub Copilot

Authentication:

- Auth type: `COPILOT_OAUTH`
- Resolver: `CopilotAuthResolver`
- Stored token: `$XDG_CONFIG_HOME/chat4j/copilot-auth.json`, falling back to `~/.config/chat4j/copilot-auth.json` when `XDG_CONFIG_HOME` is unset.
- Login flow: GitHub OAuth device authorization.

Login behavior:

1. Chat4J calls GitHub `/login/device/code`.
2. It copies the user code, opens the verification URL, and shows a modeless dialog.
3. It polls `/login/oauth/access_token` for the GitHub token.
4. It exchanges that token at `/copilot_internal/v2/token` for the Copilot session token.
5. It stores the Copilot token as `accessToken`, the GitHub token as `refreshToken`, and `expiresAtEpochMs`.

At runtime, expired Copilot session tokens are refreshed by re-exchanging `refreshToken`.

Configuration:

- Client id resolution: `chat4j.copilot.oauthClientId`, `CHAT4J_COPILOT_OAUTH_CLIENT_ID`, `build.properties` `copilotOAuthClientId`, then `/oauth/chat4j-copilot-client-id.txt`.
- Scopes: `chat4j.copilot.oauthScopes` (default `read:user`).
- Enterprise domain: `chat4j.copilot.enterpriseDomain` / `CHAT4J_COPILOT_ENTERPRISE_DOMAIN`.
- Endpoint overrides for tests/custom setups: `chat4j.copilot.deviceCodeEndpoint`, `chat4j.copilot.accessTokenEndpoint`, `chat4j.copilot.tokenEndpoint`.

Runtime behavior:

- `ProviderFacade` resolves the bearer token via `CopilotAuthResolver`.
- `ProviderRuntimePolicy` drives availability from resolver status.
- Chat4J sends `Copilot-Integration-Id` on Copilot API calls.
- Default integration id: `copilot-developer-cli`; override with `chat4j.copilot.integrationId`.
- Chat runtime does not send `Editor-Version` or `Editor-Plugin-Version`.
- Model catalog filters out websocket-only endpoint declarations such as `ws:/responses`.
- Runtime prefers the endpoint declared by Copilot for the selected model, with cached fallback when metadata is missing/stale.

Header evidence summary:

- `Copilot-Integration-Id` materially changes the returned model catalog.
- A sanitized test run returned 7 models with no header/`vscode-chat`, and 25 with `copilot-developer-cli`.
- `Editor-Version` and `Editor-Plugin-Version` did not change the model count in that run.
- Some modern models are `/responses`-only and fail on `/chat/completions` with `unsupported_api_for_model`.

Useful verification commands:

```bash
export TOKEN="$(jq -r '.accessToken // empty' "${XDG_CONFIG_HOME:-$HOME/.config}/chat4j/copilot-auth.json")"
export BASE="https://api.githubcopilot.com"

curl -sS "$BASE/models" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/json" \
  -H "Copilot-Integration-Id: copilot-developer-cli"
```

References:

- GitHub OAuth device flow: https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#device-flow
- GitHub Copilot supported models: https://docs.github.com/en/copilot/reference/ai-models/supported-models
- Copilot Chat upstream reference commit used during investigation: `9e668cb12144c701cf0f2c6b3458c00fe3da20f1`

## OpenAI Codex

Authentication:

- Auth type: `CODEX_OAUTH`
- Resolver: `CodexAuthResolver`
- Stored token: `$XDG_CONFIG_HOME/chat4j/codex-auth.json`, falling back to `~/.config/chat4j/codex-auth.json` when `XDG_CONFIG_HOME` is unset.
- Login flow: OAuth authorization code + PKCE.
- Chat4J owns Codex auth and does not use `~/.codex/auth.json` as the auth source of truth.

Login behavior:

1. Chat4J generates PKCE verifier/challenge and OAuth state.
2. It starts a localhost callback listener when possible.
3. It shows a modal login dialog with URL/open/copy controls and manual callback input.
4. Browser callback closes the dialog automatically when it succeeds.
5. If localhost callback is blocked, the user pastes the full callback URL into the dialog.
6. Chat4J exchanges the authorization code for token metadata and stores it.

Configuration:

- Client id resolution: `chat4j.codex.oauthClientId`, `CHAT4J_CODEX_OAUTH_CLIENT_ID`, `build.properties` `codexOAuthClientId`, then `/oauth/chat4j-codex-client-id.txt`.
- Optional overrides: `chat4j.codex.oauthIssuer`, `chat4j.codex.oauthScopes`, `chat4j.codex.oauthOriginator`.
- Endpoint/flow overrides: `chat4j.codex.oauthAuthorizeEndpoint`, `chat4j.codex.oauthTokenEndpoint`, `chat4j.codex.oauthRedirectUri`, `chat4j.codex.oauthCallbackHost`, `chat4j.codex.oauthLoginTimeoutSeconds`.

Runtime behavior:

- Codex chat uses `CodexCliChatCompletionClient` by design.
- Model listing tries OpenAI-compatible `/v1/models` first, then falls back to `~/.codex/models_cache.json`.
- Chat4J persists the final model list in `<app-config>/models-cache/OpenAI_Codex.txt`.
- Agent Mode chooses the Codex CLI-first adapter path for OpenAI Codex to avoid noisy HTTP tool-calling fallback warnings.

Troubleshooting:

- If the Codex model picker is empty after auth changes, remove `<app-config>/models-cache/OpenAI_Codex.txt` and refresh provider models.
