# Copilot Integration Header Behavior (Evidence + References)

Scope: Copilot API behavior for header-based model routing and endpoint compatibility, with reproducible curl examples.

## TL;DR

- `Copilot-Integration-Id` materially changes returned model catalog.
- In the sample verification matrix below:
  - no integration header or `vscode-chat` => **7 models** (legacy set)
  - `copilot-developer-cli` => **25 models** (modern set)
- `Editor-Version` and `Editor-Plugin-Version` did **not** change model count in this test matrix.
- Some modern models are `/responses`-only and fail on `/chat/completions` with `unsupported_api_for_model`.

---

## Reproducible curl proofs (sanitized)

> Use your own token in `$TOKEN`. Do not commit token values.

```bash
export TOKEN="<REDACTED_GITHUB_OAUTH_TOKEN>"
export BASE="https://api.githubcopilot.com"

# Helper: count model ids across both possible payload shapes (data[] or models[])
count_models() {
  jq '([.data[]?.id, .models[]?.id] | map(select(. != null)) | length)'
}

printf "no extra headers: "
curl -sS "$BASE/models" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/json" | count_models

printf "integration=vscode-chat: "
curl -sS "$BASE/models" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/json" \
  -H "Copilot-Integration-Id: vscode-chat" | count_models

printf "integration=copilot-developer-cli: "
curl -sS "$BASE/models" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/json" \
  -H "Copilot-Integration-Id: copilot-developer-cli" | count_models

printf "integration=cli + editor headers: "
curl -sS "$BASE/models" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/json" \
  -H "Copilot-Integration-Id: copilot-developer-cli" \
  -H "Editor-Version: vscode/1.99.3" \
  -H "Editor-Plugin-Version: github.copilot/1.388.0" | count_models
```

Example output from a sanitized verification run:

```text
no extra headers: 7
integration=vscode-chat: 7
integration=copilot-developer-cli: 25
integration=cli + editor headers: 25
```

### Endpoint compatibility proof (`/chat/completions` vs `/responses`)

```bash
# Same model, same token, same integration id

curl -sS "$BASE/chat/completions" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/json" \
  -H "Content-Type: application/json" \
  -H "Copilot-Integration-Id: copilot-developer-cli" \
  -d '{"model":"gpt-5.4-mini","stream":false,"messages":[{"role":"user","content":"Say OK"}]}'
```

Observed response (sanitized):

```json
{"error":{"message":"model \"gpt-5.4-mini\" is not accessible via the /chat/completions endpoint","code":"unsupported_api_for_model"}}
```

```bash
curl -sS "$BASE/responses" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/json" \
  -H "Content-Type: application/json" \
  -H "Copilot-Integration-Id: copilot-developer-cli" \
  -d '{"model":"gpt-5.4-mini","input":"Say OK"}'
```

Observed response: HTTP 200 (body omitted; contains normal response object payload).

---

## Official docs references

1. GitHub OAuth app authorization (includes device flow)
   - https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#device-flow
2. GitHub Copilot supported models reference
   - https://docs.github.com/en/copilot/reference/ai-models/supported-models

## Upstream code references (GitHub Copilot Chat)

Permalink commit: `9e668cb12144c701cf0f2c6b3458c00fe3da20f1`

1. `Copilot-Integration-Id` usage in networking layer (`vscode-chat`)
   - https://github.com/microsoft/vscode-copilot-chat/blob/9e668cb12144c701cf0f2c6b3458c00fe3da20f1/src/platform/networking/vscode-node/fetcherServiceImpl.ts#L132-L136
2. `Editor-Version` and `Editor-Plugin-Version` header construction
   - https://github.com/microsoft/vscode-copilot-chat/blob/9e668cb12144c701cf0f2c6b3458c00fe3da20f1/src/platform/env/common/envService.ts#L138-L142
3. Model endpoint enum (`/chat/completions`, `/responses`, `ws:/responses`)
   - https://github.com/microsoft/vscode-copilot-chat/blob/9e668cb12144c701cf0f2c6b3458c00fe3da20f1/src/platform/endpoint/common/endpointProvider.ts#L73-L78
4. Responses endpoint selection logic from `supported_endpoints`
   - https://github.com/microsoft/vscode-copilot-chat/blob/9e668cb12144c701cf0f2c6b3458c00fe3da20f1/src/platform/endpoint/node/chatEndpoint.ts#L233-L242

---

## Link health check

All URLs above were checked with HTTP `HEAD` and returned `200` during the latest documentation update.

Example checker:

```bash
python3 - <<'PY'
import urllib.request
urls = [
  "https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#device-flow",
  "https://docs.github.com/en/copilot/reference/ai-models/supported-models",
  "https://github.com/microsoft/vscode-copilot-chat/blob/9e668cb12144c701cf0f2c6b3458c00fe3da20f1/src/platform/networking/vscode-node/fetcherServiceImpl.ts#L132-L136",
  "https://github.com/microsoft/vscode-copilot-chat/blob/9e668cb12144c701cf0f2c6b3458c00fe3da20f1/src/platform/env/common/envService.ts#L138-L142",
  "https://github.com/microsoft/vscode-copilot-chat/blob/9e668cb12144c701cf0f2c6b3458c00fe3da20f1/src/platform/endpoint/common/endpointProvider.ts#L73-L78",
  "https://github.com/microsoft/vscode-copilot-chat/blob/9e668cb12144c701cf0f2c6b3458c00fe3da20f1/src/platform/endpoint/node/chatEndpoint.ts#L233-L242",
]
for u in urls:
  req = urllib.request.Request(u, method="HEAD", headers={"User-Agent": "chat4j-link-check"})
  with urllib.request.urlopen(req, timeout=15) as r:
    print(r.status, u)
PY
```

---

## Current Chat4J runtime policy

- Runtime sends only: `Copilot-Integration-Id`.
- Runtime no longer sends: `Editor-Version`, `Editor-Plugin-Version`.
- Docs keep those headers for interoperability/reference and debugging context.
