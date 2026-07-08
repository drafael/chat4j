# ElevenLabs Speech-to-Text Provider Plan

## Goal

Add ElevenLabs as a complete cloud Speech-to-Text provider for Chat4J. It should fit the existing batch STT flow: record a temporary WAV file, upload it to a selected cloud provider after the user stops recording, parse the transcript, and append it to the composer.

This plan is intentionally limited to the provider/model transcription product surface we selected: provider selection, model catalog refresh, transcription upload, privacy copy, docs, and tests. Advanced ElevenLabs request options are not part of this implementation because we explicitly chose a Groq-like cloud-provider integration first; they are listed as follow-up product decisions, not skipped implementation details.

## Current Codebase Constraints To Handle

- `SpeechToTextProviderRegistry.createDefault()` currently registers only Groq and Vosk. ElevenLabs must be registered there in the intended provider order.
- `SpeechToTextSettings.resolveEndpoint(...)` is currently Groq-shaped: Groq uses the configured Groq chat/provider base URL, and every other non-Vosk provider falls back to Groq default endpoints. ElevenLabs must not flow through Groq endpoint defaults.
- `SpeechToTextPanel.refreshCatalogs(...)` currently re-resolves non-Vosk catalog refreshes with `GroqSttEndpointResolver`. ElevenLabs catalog refresh must use ElevenLabs endpoints/context, not `https://api.elevenlabs.io/models` produced by the Groq path.
- Settings-panel tests currently construct `SpeechToTextPanel` with `CredentialSource.SYSTEM`, so any new missing-credential UI test can become flaky on a developer machine that has `ELEVENLABS_API_KEY` set. Prefer `SpeechToTextSettings` tests with fake credentials for missing-key behavior, or add an explicit test seam before asserting panel labels that depend on credentials.
- `MicrophoneAudioCapture.MAX_CAPTURED_WAV_BYTES` is currently 24 MiB, and `SpeechToTextService` rejects larger captured WAVs before any provider sees them. With the current 600-second max recording duration and 16 kHz mono PCM target format, normal app recordings should stay below that cap. The ElevenLabs 100 MiB limit is therefore a provider/API defensive guard for direct provider calls and future larger capture limits, not a promise that the current UI can upload 100 MiB recordings.
- `CredentialResolver` already lists `ELEVENLABS_API_KEY`; verify before adding anything new.

## Decisions

- Provider ID: `elevenlabs`.
- Display name: `ElevenLabs`.
- Credential source: process environment variable `ELEVENLABS_API_KEY`.
- Base URL: fixed provider-local constant `https://api.elevenlabs.io`, matching the current ElevenLabs TTS style. Do not add a new base URL setting.
- Transcription endpoint: `POST https://api.elevenlabs.io/v1/speech-to-text`.
- Model catalog endpoint: `GET https://api.elevenlabs.io/v1/models`.
- Default/bundled model: `scribe_v2`.
- Fetch/filter ElevenLabs models when credentials are available, while preserving existing cached models on remote refresh failures.
- Provider upload guard: `ElevenLabsSpeechToTextProvider.MAX_UPLOAD_BYTES = 100L * 1024L * 1024L`.
- Do not change the existing global microphone capture cap in this plan.
- Send only required multipart fields now: `model_id` and `file`.
- Omit optional ElevenLabs API fields now: language code, diarization, speaker count, audio-event tagging, no-verbatim, entity detection/redaction, keyterms, webhook mode, source URL, and `enable_logging`.
- Because `enable_logging` is omitted, Chat4J is not requesting ElevenLabs zero-retention mode. Document that ElevenLabs cloud transcription is subject to the user's ElevenLabs account/API retention behavior.

## Implementation Steps

### 1. Add ElevenLabs STT provider package

Create package:

```text
src/main/java/com/github/drafael/chat4j/stt/provider/elevenlabs
```

Add provider classes such as:

```text
ElevenLabsSpeechToTextProvider.java
ElevenLabsSpeechToTextModels.java
ElevenLabsSttEndpointResolver.java
```

Provider contract:

- `id()` returns `elevenlabs`.
- `displayName()` returns `ElevenLabs`.
- `requiredEnvVar()` returns `ELEVENLABS_API_KEY`.
- `defaultModel()` returns `scribe_v2`.
- `bundledModels()` returns at least `scribe_v2`.
- `fetchModels(context)` fetches and filters models when credentials exist.
- `transcribe(request, context)` uploads WAV and returns `SpeechToTextResult`.

Keep the provider independent from TTS for now. It may duplicate the `https://api.elevenlabs.io` constant, matching the user-selected “small duplication” approach.

### 2. Register provider and constants

Update `SpeechToTextProviderRegistry.createDefault()` to include the ElevenLabs provider alongside Groq and Vosk.

Expected settings provider order:

```text
Off
Groq
ElevenLabs
Vosk
```

Add `SettingsKeys.STT_PROVIDER_ELEVENLABS = "elevenlabs"` unless implementation consistently uses `ElevenLabsSpeechToTextProvider.ID` and a new setting constant would be redundant. Prefer the setting constant for consistency with existing `groq` and `vosk` constants.

Avoid changing Vosk model-management behavior.

### 3. Fix endpoint plumbing for non-Groq cloud providers

Do not route ElevenLabs through `GroqSttEndpointResolver`.

Add a small ElevenLabs endpoint resolver or constants that produce:

```text
baseUri          = https://api.elevenlabs.io
transcriptionUri = https://api.elevenlabs.io/v1/speech-to-text
modelsUri        = https://api.elevenlabs.io/v1/models
```

Keep the current `SpeechToTextProviderContext` shape unless a broader provider-contract refactor is deliberately chosen. The existing context has `baseUri` and `transcriptionUri`, not `modelsUri`; `ElevenLabsSpeechToTextProvider.fetchModels(...)` should derive `/v1/models` from `context.baseUri()` with the ElevenLabs resolver, just as Groq derives its models URI from its base URI today.

Update `SpeechToTextSettings.resolveEndpoint(...)` or its replacement so:

- Groq keeps the existing Groq base URL behavior.
- ElevenLabs snapshots contain ElevenLabs base/transcription URIs.
- Vosk remains endpoint-free.
- The implementation does not force `SpeechToTextSettings.resolveEndpoint(...)` to return `GroqSttEndpointResolver.Endpoint` for all providers. Prefer a small provider-neutral endpoint record, switch, or helper so ElevenLabs does not depend on Groq types.
- Registered non-local cloud providers do not silently inherit Groq endpoints. Keep the change scoped: Groq and ElevenLabs get explicit endpoint handling now; Vosk stays endpoint-free; any other registered provider should either provide explicit endpoint handling or resolve to a clear unavailable status without breaking existing local-model test seams.

Update `SpeechToTextPanel.refreshCatalogs(...)` so non-Vosk refresh creates `SpeechToTextProviderContext` from the resolved provider-specific snapshot/context. It must not call `GroqSttEndpointResolver` for ElevenLabs. Groq may continue to use its resolver inside the Groq provider as needed.

Add tests that assert:

- ElevenLabs settings snapshots use ElevenLabs base/transcription URIs.
- ElevenLabs catalog refresh sends a request to `/v1/models` on `api.elevenlabs.io`.
- Groq settings/catalog behavior still uses Groq endpoints.

### 4. Implement model catalog fetch/filter

Call:

```text
GET https://api.elevenlabs.io/v1/models
```

Headers:

```text
xi-api-key: <ELEVENLABS_API_KEY>
Accept: application/json
```

Use the existing `SttHttpTransport` path with a bounded response size, around `2 MiB`.

Parse model entries from either:

- a root JSON array, or
- an object with a `models` array.

Parsing rules:

- ID: `model_id`, then `id`.
- Label: `name`, then `label`, then `model_id`, then `id`.
- Description: `description` if present.
- Reject blank IDs and IDs containing control characters.
- Deduplicate by ID while preserving API order.

Filtering rules:

1. Include known batch STT model IDs compatible with `POST /v1/speech-to-text`, especially `scribe_v2`, and `scribe_v1` if the API returns it and it is still accepted by the batch endpoint.
2. If deprecated `scribe_v1` is included, surface that in the catalog item label or description, for example `Scribe v1 (deprecated)`, so users are not nudged toward it over `scribe_v2`.
3. Exclude realtime-only STT models such as `scribe_v2_realtime` unless ElevenLabs documentation explicitly says the batch `/v1/speech-to-text` endpoint accepts them.
4. Include entries with explicit positive batch STT/transcription capability flags if ElevenLabs adds fields such as `can_do_speech_to_text`, `can_do_transcription`, or equivalent.
5. Exclude entries with explicit negative STT/transcription capability flags.
6. Exclude obvious TTS-only entries when capability fields prove they are only text-to-speech or voice-conversion models.
7. Do not infer arbitrary non-Scribe models as STT-capable only because they appear in `/v1/models`; the documented model list currently includes many TTS model fields and realtime STT models that are not necessarily valid for batch transcription.

Catalog failure/cache behavior:

- If credentials are missing, return bundled `scribe_v2` for direct provider calls. In normal Settings refresh, the refresh button is disabled while the provider is unavailable.
- If credentials are present but the HTTP request fails, returns non-2xx, returns malformed JSON, or yields no eligible STT models, throw a `SpeechToTextException` from `fetchModels(...)` instead of returning bundled models.
- `SpeechToTextPanel.refreshCatalogs(...)` should catch that failure and preserve any previously cached ElevenLabs catalog/timestamp. It must not overwrite a rich cached catalog with only bundled `scribe_v2` after a temporary outage.
- For explicit user-triggered refresh failures, show a clear error such as `Could not refresh ElevenLabs Speech to Text models.`.
- For automatic stale-catalog refresh failures (`explicit=false`), preserve cache and avoid replacing the normal settings helper/status with a noisy error; a later explicit refresh can surface the problem.
- The model dropdown should still be usable because `SpeechToTextCatalogStore.models(...)` merges bundled models with cached models and the selected model.

### 5. Implement transcription upload

Call:

```text
POST https://api.elevenlabs.io/v1/speech-to-text
```

Headers:

```text
xi-api-key: <ELEVENLABS_API_KEY>
Accept: application/json
Content-Type: multipart/form-data; boundary=...
```

Multipart fields:

```text
model_id = <selected model id or scribe_v2>
file = <recording.wav>
```

Behavior:

- Reject files larger than `100 MiB` in `ElevenLabsSpeechToTextProvider.transcribe(...)` before calling transport.
- Preserve the existing app-level `SpeechToTextService` oversized-recording behavior unless this plan is explicitly expanded to provider-specific capture limits later.
- Sanitize multipart filename similarly to Groq: use only the basename, replace CR/LF/quotes, default to `recording.wav`, and ensure a `.wav` suffix.
- Use existing cancellation token support by passing `context.cancellationToken()` to `transport.send(...)` for both catalog fetch and transcription.
- Bound transcription JSON response, around `2–5 MiB`.
- Parse response field `text` as the transcript.
- If the transcription response is not valid JSON, throw `SpeechToTextException("Transcription response was invalid.")`.
- If `text` is missing or trims blank, throw `SpeechToTextException("No speech was recorded.")`; do not return that message as transcript text.
- Do not send Groq-only fields such as `model` or `response_format`.
- Do not set optional ElevenLabs query/body fields such as `enable_logging`, `language_code`, `diarize`, `webhook`, or `source_url` in this implementation.
- Do not claim zero-retention behavior; `enable_logging=false` is not sent in this plan.

### 6. Error handling and safety

Map ElevenLabs HTTP failures to user-facing messages:

- `401`/`403`: `ElevenLabs credentials were rejected.`
- `404`: `ElevenLabs speech-to-text endpoint or model was not found.`
- `413`: `Recording is too large to upload.`
- `422`: `ElevenLabs transcription failed: <safe API detail>`
- `429`: `ElevenLabs rate limit reached. Try again later.`
- `5xx`: `ElevenLabs speech-to-text is temporarily unavailable.`
- Other: `ElevenLabs transcription failed: <safe API detail>`

Safe API detail rules:

- Parse common response shapes defensively: string `detail`, object `detail.message`, `detail` arrays from validation errors, string `message`, object `error.message`, or string `error`.
- Strip CR/LF/control characters from displayed details.
- Redact any occurrence of the configured API key if present.
- Abbreviate the final displayed detail to a small limit, around `300` characters.
- Never include request headers, API keys, local file paths, multipart bodies, or audio content in errors/logs.

Cancellation behavior:

- If the shared transport throws `SpeechToTextException("Transcription canceled.")`, do not remap it to an ElevenLabs failure message.
- Preserve cancellation semantics used by `SpeechToTextService.cancel(...)`.

### 7. Settings UI and docs

Settings UI should work through existing generic provider/model controls. No new ElevenLabs-specific UI fields.

Update settings helper/status copy so ElevenLabs has provider-specific privacy text similar to Groq, for example:

```text
Recorded audio is sent to ElevenLabs for transcription. No API key is stored by Chat4J.
```

Update docs:

- `docs/speech-to-text.md`
  - Add ElevenLabs to provider list.
  - Document `ELEVENLABS_API_KEY`.
  - Document that finalized audio is uploaded to ElevenLabs for transcription.
  - Document that Chat4J does not send `enable_logging=false`/zero-retention flags in this implementation; retention/logging is governed by the user's ElevenLabs account/API behavior.
  - Document that model refresh uses ElevenLabs `/v1/models` and preserves cached models on refresh failure.
  - Document bundled fallback model `scribe_v2`.
  - Mention that advanced ElevenLabs options are not exposed yet.
- `docs/README.md` if the STT summary needs provider list wording.
- `THIRD_PARTY_NOTICES.md` only if needed. API use alone likely does not require a new bundled third-party notice.

### 8. Tests

Add tests under:

```text
src/test/java/com/github/drafael/chat4j/stt/provider/elevenlabs
```

Provider/unit coverage:

- metadata/default model/bundled models.
- `available()` requires `ELEVENLABS_API_KEY`.
- `fetchModels()` returns bundled models without credentials for direct calls.
- `fetchModels()` parses root-array and `models`-array responses.
- `fetchModels()` includes explicit STT-capable models.
- `fetchModels()` includes known returned batch-compatible Scribe IDs.
- `fetchModels()` labels or describes deprecated `scribe_v1` as deprecated when included.
- `fetchModels()` excludes realtime-only Scribe IDs such as `scribe_v2_realtime` unless explicitly documented as batch-compatible.
- `fetchModels()` excludes TTS-only or explicit non-STT models.
- `fetchModels()` deduplicates and rejects blank/control-character IDs.
- `fetchModels()` throws on HTTP failure, malformed JSON, or no eligible STT models when credentials are present, so Settings can preserve cache.
- catalog fetch passes the context cancellation token to transport.
- successful transcription parses `text`.
- invalid transcription JSON throws `SpeechToTextException("Transcription response was invalid.")`.
- blank/missing `text` throws `SpeechToTextException("No speech was recorded.")`.
- upload size guard rejects files over `100 MiB` before transport send.
- HTTP error mapping for 401/403/413/422/429/5xx, including `detail` array extraction for validation errors.
- cancellation exceptions from transport are not remapped.

Multipart coverage:

- request uses `xi-api-key`, not `Authorization: Bearer`.
- request uses `POST https://api.elevenlabs.io/v1/speech-to-text`.
- body contains exactly one `model_id` field.
- body contains one `file` part with `Content-Type: audio/wav`.
- filename is sanitized basename only.
- body has a terminating boundary.
- body does not include Groq-only `model` or `response_format` fields.

Integration/settings coverage:

- ElevenLabs appears in provider order: `Off`, `Groq`, `ElevenLabs`, `Vosk`.
- missing-credential status mentions `ELEVENLABS_API_KEY` using `SpeechToTextSettings` with a fake missing `CredentialSource`; do not rely on the developer machine's real environment. If panel label behavior is tested too, add/use a test seam for credentials or guard the test against a real `ELEVENLABS_API_KEY`.
- selecting ElevenLabs with no saved model falls back to `scribe_v2`.
- `SpeechToTextSettings.resolve()` for ElevenLabs returns ElevenLabs base/transcription URIs.
- `SpeechToTextPanel.refreshCatalogs(...)` uses ElevenLabs model endpoint and not the Groq resolver path.
- failed explicit ElevenLabs refresh preserves existing cached catalog, does not update the catalog timestamp, and shows a clear refresh error.
- failed automatic stale ElevenLabs refresh preserves existing cached catalog, does not update the catalog timestamp, and does not overwrite the normal settings helper/status with a noisy error.
- settings helper copy says recorded audio is sent to ElevenLabs and no API key is stored.
- existing Groq/Vosk settings behavior remains unchanged.

### 9. Validation

Run focused validation:

```bash
mvn -q -DskipTests compile
mvn -q -Dtest='*ElevenLabs*Test,SpeechToTextSettingsTest,SpeechToTextPanelTest,GroqSpeechToTextProviderTest,SpeechToTextVoskSettingsTest,Vosk*Test,InputBarValidationTest,ChatPanelTest' test
git diff --check
```

Because endpoint/settings plumbing is shared, run the full suite before considering the implementation complete:

```bash
mvn -q test
```

## Explicitly Deferred Product Decisions

These are not part of this provider/model transcription implementation, but should be considered later if users need them:

- Language-code setting.
- Diarization toggle and speaker count.
- Tag audio events / no-verbatim controls.
- Entity detection/redaction controls.
- Keyterms.
- Webhook/asynchronous transcription mode.
- URL/source-based transcription.
- Configurable ElevenLabs base URL.
- Sharing/refactoring ElevenLabs endpoint constants with TTS.

## Open Follow-Up Ideas

- Add advanced ElevenLabs transcription settings after product requirements are clear, including persistence, UI validation, request mapping, privacy/cost copy, and tests.
- Consider a shared ElevenLabs API helper only if TTS/STT code starts duplicating meaningful logic beyond a base URL constant.
- Add provider-specific billing/cost warnings if advanced billable ElevenLabs features are introduced.
- Revisit provider-specific capture/upload limits if Chat4J raises `MAX_MAX_DURATION_SECONDS` or supports importing arbitrary audio/video files for STT.
