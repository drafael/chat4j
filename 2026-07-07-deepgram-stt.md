# Deepgram Speech-to-Text Provider Plan

## Goal

Add Deepgram as a complete cloud Speech-to-Text provider for Chat4J. It should fit the existing batch STT flow: record a temporary WAV file, upload it to a selected cloud provider after the user stops recording, parse the transcript, and append it to the composer.

This provider should match the Groq/ElevenLabs cloud-provider experience: explicit provider selection, environment-variable credentials, model selection/catalog behavior, provider-specific endpoints, privacy copy, docs, and tests. Do not add Deepgram advanced options such as smart formatting, diarization, language, punctuate, utterances, numerals, keywords, search, replace, or callback/webhook mode in this pass.

## Decisions

- Provider ID: `deepgram`.
- Display name: `Deepgram`.
- Credential source: process environment variable `DEEPGRAM_API_KEY`.
- Base URL: fixed provider-local constant `https://api.deepgram.com`. Do not add a configurable Deepgram base URL setting.
- Pre-recorded transcription endpoint: `POST https://api.deepgram.com/v1/listen`.
- Request shape: raw WAV file body, not multipart.
- Auth header: `Authorization: Token <DEEPGRAM_API_KEY>`.
- Default model: `nova-3`.
- Bundled models: small curated list of documented pre-recorded model IDs:
  - `nova-3` — Deepgram Nova 3
  - `nova-3-general` — Deepgram Nova 3 General
  - `nova-2-general` — Deepgram Nova 2 General
- Provider upload guard: `DeepgramSpeechToTextProvider.MAX_UPLOAD_BYTES = 100L * 1024L * 1024L`.
- Do not change Chat4J's existing global microphone capture cap in this plan.
- Send only `model=<selected model or nova-3>` as a query parameter for transcription.
- Omit provider-specific optional request parameters for now, including `smart_format`, `diarize`, `language`, `punctuate`, `utterances`, `numerals`, keywords, search/replace, alternatives, and callbacks/webhooks.

## Current Codebase Constraints To Handle

- `SpeechToTextProviderRegistry.createDefault()` currently registers Groq, ElevenLabs, and Vosk. Deepgram must be registered in the intended provider order.
- `SpeechToTextSettings.resolveEndpoint(...)` currently has explicit handling for Groq and ElevenLabs. Deepgram must get explicit endpoint handling and must not inherit Groq or ElevenLabs endpoints.
- `SpeechToTextPanel.refreshCatalogs(...)` now uses provider-specific snapshot endpoints. Deepgram refresh should use Deepgram context, preserve cached catalogs on refresh failure, and avoid noisy automatic-refresh errors like ElevenLabs.
- `MicrophoneAudioCapture.MAX_CAPTURED_WAV_BYTES` is currently 24 MiB, and `SpeechToTextService` rejects larger captured WAVs before provider upload. The Deepgram 100 MiB limit is a provider/API defensive guard for direct provider calls and future larger capture limits, not a promise that current microphone recordings can upload 100 MiB.
- `CredentialResolver` does not currently list `DEEPGRAM_API_KEY`; add it so packaged app shell-env loading and supported-provider credential discovery recognize the key consistently.

## Implementation Steps

### 1. Add Deepgram STT provider package

Create package:

```text
src/main/java/com/github/drafael/chat4j/stt/provider/deepgram
```

Add classes such as:

```text
DeepgramSpeechToTextProvider.java
DeepgramSpeechToTextModels.java
DeepgramSttEndpointResolver.java
```

Provider contract:

- `id()` returns `deepgram`.
- `displayName()` returns `Deepgram`.
- `requiredEnvVar()` returns `DEEPGRAM_API_KEY`.
- `defaultModel()` returns `nova-3`.
- `bundledModels()` returns the curated bundled list.
- `fetchModels(context)` fetches and filters models from Deepgram's official `GET /v1/models` endpoint when credentials are available; otherwise returns bundled models.
- `transcribe(request, context)` uploads the WAV body to Deepgram and returns `SpeechToTextResult`.

Keep Deepgram independent from Groq and ElevenLabs provider implementations. Small duplication is acceptable at this third provider stage only where request shapes differ; extract helpers only if duplication becomes clearly harmful.

### 2. Register provider and constants

Add `SettingsKeys.STT_PROVIDER_DEEPGRAM = "deepgram"` for consistency with existing `groq`, `elevenlabs`, and `vosk` constants.

Update `SpeechToTextProviderRegistry.createDefault()` provider order:

```text
Groq
ElevenLabs
Deepgram
Vosk
```

The Settings provider combo prepends `Off`, so expected UI order is:

```text
Off
Groq
ElevenLabs
Deepgram
Vosk
```

Update tests that assert provider order.

### 3. Add Deepgram endpoint resolver and settings plumbing

Add a small endpoint resolver/constants class that produces:

```text
baseUri          = https://api.deepgram.com
transcriptionUri = https://api.deepgram.com/v1/listen
modelsUri        = https://api.deepgram.com/v1/models
```

Use HTTPS with host validation. Since the base is fixed, no user-provided base URL needs to be accepted.

Keep the current `SpeechToTextProviderContext` shape unless a broader provider-contract refactor is deliberately chosen. The existing context has `baseUri` and `transcriptionUri`, not `modelsUri`; `DeepgramSpeechToTextProvider.fetchModels(...)` should derive `/v1/models` from `context.baseUri()` with the Deepgram resolver, matching the ElevenLabs pattern.

Update `SpeechToTextSettings.resolveEndpoint(...)` or its provider-neutral replacement so:

- Groq keeps current configured Groq endpoint behavior.
- ElevenLabs keeps official ElevenLabs endpoint behavior.
- Deepgram resolves to official Deepgram base/transcription URIs.
- Vosk remains endpoint-free.
- Unknown registered non-local providers remain unavailable with a clear endpoint message rather than inheriting another provider's endpoint.

Update/keep `SpeechToTextPanel.refreshCatalogs(...)` so Deepgram receives a `SpeechToTextProviderContext` built from the Deepgram snapshot. Do not route Deepgram through Groq or ElevenLabs endpoint resolvers.

### 4. Model catalog behavior

Bundled fallback models:

```text
nova-3
nova-3-general
nova-2-general
```

Default model is `nova-3`.

Do not bundle bare `nova-2` unless Deepgram documentation/API confirms it is accepted as a pre-recorded `model` query value. The documented Nova-2 option syntax is `nova-2-{option}`, so `nova-2-general` is the safe curated fallback.

Use Deepgram's official model-list endpoint:

```text
GET https://api.deepgram.com/v1/models
```

The documented response is an object with model families such as `stt` and `tts`. STT entries include fields such as `name`, `canonical_name`, `architecture`, `languages`, `version`, `uuid`, `batch`, `streaming`, and `formatted_output`.

Catalog fetch rules:

- Use a bounded response size, around `2 MiB`.
- Use `Authorization: Token <DEEPGRAM_API_KEY>` and `Accept: application/json`.
- Parse only the `stt` array from the documented response shape.
- Include pre-recorded STT models where `batch == true`.
- Exclude entries where `batch == false`, including live/streaming-only models.
- Exclude TTS or non-transcription model families by ignoring non-`stt` arrays entirely.
- Model ID: prefer `canonical_name`, then `name`.
- Label: prefer readable `name`, then `canonical_name`, then model ID; include architecture/version in the description when available.
- Reject blank IDs and IDs containing control characters.
- Deduplicate by ID while preserving API order.
- If credentials are missing, return bundled models for direct provider calls.
- If credentials are present but refresh fails, returns non-2xx, returns malformed JSON, omits the `stt` array, or yields no eligible batch STT models, throw `SpeechToTextException` so Settings can preserve existing cached Deepgram catalog/timestamp.
- A successful refresh should save the parsed Deepgram models and timestamp through the existing `SpeechToTextCatalogStore` path.

Docs should state that the curated bundled list is a fallback and the current Deepgram account/API may expose a different model set.

### 5. Implement transcription upload

Call:

```text
POST https://api.deepgram.com/v1/listen?model=<url-encoded selected model id or nova-3>
```

Headers:

```text
Authorization: Token <DEEPGRAM_API_KEY>
Content-Type: audio/wav
Accept: application/json
```

Body:

```text
<raw WAV file bytes>
```

Behavior:

- Reject files larger than `100 MiB` in `DeepgramSpeechToTextProvider.transcribe(...)` before calling transport.
- Preserve existing app-level `SpeechToTextService` oversized-recording behavior.
- Use the selected model ID, falling back to `nova-3` when request model ID is blank.
- Build the transcription URI safely with URL encoding for the `model` query parameter.
- Do not use multipart.
- Stream the raw WAV from disk with `HttpRequest.BodyPublishers.ofFile(...)`; do not read the whole recording into memory before upload.
- Do not send optional query parameters such as `smart_format`, `diarize`, `language`, `punctuate`, `utterances`, `numerals`, `keywords`, `search`, `replace`, `alternatives`, or `callback`.
- Use existing cancellation support by passing `context.cancellationToken()` to `transport.send(...)`.
- Bound transcription JSON response, around `2–5 MiB`.
- Parse Deepgram response transcript from the documented result shape, expected:

```text
results.channels[0].alternatives[0].transcript
```

- Trim transcript text.
- If response JSON is invalid, throw `SpeechToTextException("Transcription response was invalid.")`.
- If transcript is missing or blank, throw `SpeechToTextException("No speech was recorded.")`.

### 6. Error handling and safety

Map Deepgram HTTP failures to user-facing messages:

- `401`/`403`: `Deepgram credentials were rejected.`
- `404`: `Deepgram speech-to-text endpoint or model was not found.`
- `413`: `Recording is too large to upload.`
- `429`: `Deepgram rate limit reached. Try again later.`
- `5xx`: `Deepgram speech-to-text is temporarily unavailable.`
- Other: `Deepgram transcription failed: <safe API detail>`

Safe API detail rules:

- Parse common Deepgram/error response shapes defensively, including fields such as `err_msg`, `err_code`, `message`, `detail`, and nested `error.message` if present.
- Strip CR/LF/control characters from displayed details.
- Redact any occurrence of the configured API key if present.
- Abbreviate final displayed detail to around `300` characters.
- Never include request headers, API keys, local file paths, raw audio, or full response bodies in errors/logs.

Cancellation behavior:

- If shared transport throws `SpeechToTextException("Transcription canceled.")`, do not remap it to a Deepgram failure message.
- Preserve existing `SpeechToTextService.cancel(...)` behavior.

### 7. Settings UI and docs

Settings UI should work through existing generic provider/model controls. No new Deepgram-specific UI fields.

Update settings helper/status copy so Deepgram has provider-specific privacy text, for example:

```text
Recorded audio is sent to Deepgram for transcription. No API key is stored by Chat4J.
```

Update docs:

- `docs/speech-to-text.md`
  - Add Deepgram to provider list and settings provider list.
  - Document `DEEPGRAM_API_KEY`.
  - Document fixed official base URL `https://api.deepgram.com`.
  - Document pre-recorded endpoint `/v1/listen`.
  - Document that finalized audio is uploaded to Deepgram for transcription.
  - Do not claim local, offline, or zero-retention behavior for Deepgram; retention/logging is governed by the user's Deepgram account/API terms.
  - Document default model `nova-3` and bundled curated fallbacks. Do not document bare `nova-2` as bundled unless implementation verified it is accepted.
  - Document that model refresh calls Deepgram `GET /v1/models`, parses batch-capable STT models from the `stt` array, and preserves cached models on refresh failure.
  - Document that the curated bundled list is only a fallback and Deepgram account/API model availability may differ.
  - Mention advanced Deepgram options are not exposed yet.
- `docs/README.md` if the STT summary needs provider-list wording.
- Root `README.md` API-key env-var list should be updated or its scope clarified so `DEEPGRAM_API_KEY` support is discoverable. If adding Deepgram there, avoid making the existing `ELEVENLABS_API_KEY` omission more confusing; either include feature-level STT/TTS keys together or clarify the list is chat-provider-only.
- `THIRD_PARTY_NOTICES.md` only if needed. API use alone likely does not require a bundled third-party notice.

### 8. Tests

Add tests under:

```text
src/test/java/com/github/drafael/chat4j/stt/provider/deepgram
```

Provider/unit coverage:

- metadata/default model/bundled models, including that unsupported bare `nova-2` is not bundled unless explicitly verified.
- `available()` requires `DEEPGRAM_API_KEY`.
- endpoint resolver produces official base and `/v1/listen` transcription URI.
- `fetchModels()` missing-credential behavior returns bundled models.
- `fetchModels()` parses the documented `/v1/models` object response and its `stt` array.
- `fetchModels()` includes entries with `batch == true` and excludes `batch == false` streaming-only entries.
- `fetchModels()` ignores non-`stt` families such as `tts`.
- `fetchModels()` uses `canonical_name` then `name` as model ID, labels models readably, deduplicates, rejects blank/control IDs, throws on non-2xx/malformed/no eligible models when credentials are present, and forwards the cancellation token.
- transcription uses `POST https://api.deepgram.com/v1/listen?model=<encoded>` and falls back to `nova-3` for blank request model IDs.
- request uses `Authorization: Token <key>`, not Bearer and not `xi-api-key`.
- request uses `Content-Type: audio/wav` and raw file body from `HttpRequest.BodyPublishers.ofFile(...)`, not multipart or an in-memory byte array.
- no optional Deepgram query params are sent, including `smart_format`, `diarize`, `language`, `punctuate`, `utterances`, `numerals`, `keywords`, `search`, `replace`, `alternatives`, and `callback`.
- selected model ID is URL encoded in query.
- upload size guard rejects files over `100 MiB` before transport send.
- successful response parses `results.channels[0].alternatives[0].transcript`.
- invalid JSON throws `SpeechToTextException("Transcription response was invalid.")`.
- blank/missing transcript throws `SpeechToTextException("No speech was recorded.")`.
- HTTP error mapping for 401/403/404/413/429/5xx.
- safe detail extraction/redaction for Deepgram error shapes.
- transport cancellation exception is not remapped.
- transcription passes the context cancellation token to transport.

Integration/settings coverage:

- Provider order: `Off`, `Groq`, `ElevenLabs`, `Deepgram`, `Vosk`.
- `CredentialResolver.supportedProviderEnvVars()` includes `DEEPGRAM_API_KEY`.
- Missing-credential status mentions `DEEPGRAM_API_KEY` using `SpeechToTextSettings` with a fake missing `CredentialSource`.
- Selecting Deepgram with no saved model falls back to `nova-3`.
- `SpeechToTextSettings.resolve()` for Deepgram returns Deepgram base/transcription URIs.
- `SpeechToTextPanel.refreshCatalogs(...)` uses Deepgram context and not Groq/ElevenLabs endpoints.
- Successful Deepgram refresh saves parsed remote models and updates the catalog timestamp.
- Failed Deepgram refresh preserves existing cached catalog and handles explicit vs automatic status consistently.
- Settings helper copy says recorded audio is sent to Deepgram and no API key is stored. Avoid environment-dependent panel tests: use `SpeechToTextSettings` with fake credentials, a custom panel provider with no required env var, or an explicit test seam instead of relying on the developer machine not having `DEEPGRAM_API_KEY`.
- Existing Groq/ElevenLabs/Vosk behavior remains unchanged.

### 9. Validation

Run focused validation:

```bash
mvn -q -DskipTests compile
mvn -q -Dtest='*Deepgram*Test,*ElevenLabs*Test,SpeechToTextSettingsTest,SpeechToTextPanelTest,GroqSpeechToTextProviderTest,SpeechToTextVoskSettingsTest,Vosk*Test,InputBarValidationTest,ChatPanelTest' test
git diff --check
```

Because endpoint/settings plumbing is shared, run the full suite before considering the implementation complete:

```bash
mvn -q test
```

## Explicitly Deferred Product Decisions

These are not part of this provider/model transcription implementation, but should be considered later if users need them:

- Smart formatting.
- Diarization.
- Language selection.
- Punctuation/numerals/utterances.
- Keywords/search/replace.
- Alternatives.
- Callback/webhook or asynchronous transcription.
- Live/streaming Deepgram STT.
- Configurable Deepgram base URL.
- Shared cloud STT request/error helper extraction across Groq, ElevenLabs, and Deepgram.

## Open Follow-Up Ideas

- Add Deepgram advanced transcription settings after product requirements are clear, including persistence, UI validation, query mapping, privacy/cost copy, and tests.
- Consider shared cloud STT helpers only after Deepgram lands and duplication across three providers is concrete enough to justify extraction.
- Revisit provider-specific capture/upload limits if Chat4J raises `MAX_MAX_DURATION_SECONDS` or supports importing arbitrary audio/video files for STT.
