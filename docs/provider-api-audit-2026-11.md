# Provider API compatibility audit — 2026-11-02

This audit compares Chat4J's existing integrations with current provider documentation. It covers the eleven chat providers remaining after the Google AI, Mistral, and Perplexity updates, plus the existing speech providers and local speech engines. It is not a claim of live-account compatibility or complete support for every newly released feature.

## Changes made

- **Anthropic:** recognize `claude-haiku-5-5`, `claude-mythos-5`, and `claude-mythos-5-1` as adaptive-thinking models. Haiku 5.5 uses Medium by default and supports explicit disabling at low effort. Mythos retains the corresponding Fable mandatory-thinking behavior. Ordinary chat and agent turns no longer send legacy thinking budgets for these IDs; signed tool-turn content remains unchanged.
- **OpenAI:** add GPT-6.1 Terra's documented None/Low/Medium/High/Extra High/Max choices, with Medium as the default. Recognize the documented GPT-6 family native web-search capability on the existing Responses search path. Native Agent Mode uses Responses for GPT-6.1 Sol/Terra and GPT-6 Sol/Luna, including local and MCP function tools.
- **Together:** add ten currently published serverless chat IDs to the existing catalog intersection. Existing IDs remain accepted when the API returns them. This is an ordinary-chat availability update: it does not infer reasoning controls, vision, or native tool-continuation support for the new entries from their names or from the API-wide parameter enum. Recognize the documented `us-api.together.ai/v1` endpoint as hosted, preserving already-reviewed model controls when that endpoint is explicitly configured. The default host is unchanged; US-only inference costs 25% more and requests must not fall back to the global host.
- **Groq STT:** remove retired Distil Whisper from bundled choices. Requests selecting `distil-whisper-large-v3-en` at the canonical hosted transcription endpoint use Groq's recommended replacement, `whisper-large-v3-turbo`. Explicit model IDs on custom endpoints are preserved.

No SDK major upgrades, OAuth changes, global configuration changes, or new dependencies were introduced. The new Responses agent adapter reuses the existing cancellable HTTP transport.

## Chat-provider findings

| Provider | Current evidence and result |
| --- | --- |
| Anthropic | Haiku 5.5 rejects `thinking.type=enabled` plus `budget_tokens`; adaptive thinking and `output_config.effort` are required instead. Mythos 5/5.1 are the verified-organization counterparts of Fable 5/5.1. Profiles and native/agent payload tests updated. SDK 2.57.0 and the existing `2023-06-01` API pin remain unchanged; the new `2026-10-21` API version is an opt-in migration, not a reason to silently upgrade SDK and wire contracts together. |
| OpenAI | GPT-6.1 Terra supports None through Max and native Responses web search. Profile and search capability updated. Agent Mode now uses Responses for GPT-6.1 Sol/Terra and GPT-6 Sol/Luna. Ultrafast mode, longer prompt caching, and computer use are optional features not enabled by this audit. |
| OpenAI Codex | Installed CLI `0.166.0` can generate its JSON schemas offline. The existing initialize, thread/start, and turn/start request fields match those schemas. Text input requires `type` and `text`; `text_elements` defaults to an empty array. Effort is a non-empty model-defined string. No schema-driven request change identified. Authenticated turns and account refresh were not exercised. |
| GitHub Copilot | October model retirements and scheduled November retirements are catalog/policy changes. Chat4J already fetches model/endpoint metadata and preserves Responses routing evidence. Model selections were not rewritten and no private account calls were made. Account entitlements, refresh, and the undocumented backend contract remain unverified. |
| OpenRouter | The public model catalog and endpoint records still advertise the existing unified reasoning/tool parameters. The shared Claude update covers Haiku/Mythos normalization. The public records inspected did not supply per-model effort ladders. Apart from the existing shared Claude policy, new gateway-specific ladders were not inferred from native-provider names or the global parameter list. |
| Together | The current serverless table contains ten IDs missing from Chat4J's dated intersection. Those IDs now survive discovery. Previously reviewed capability and continuation snapshots remain intact; new model-specific capability/effort contracts require separate evidence before enabling those controls. |
| Groq | Model listing and Chat Completions remain the relevant contracts. Compound and Compound Mini were retired on September 21, 2026, with no replacement listed; old cached Compound selections are not working hosted search models. Some other chat retirements have enterprise exceptions, so model IDs were not globally deleted or silently rewritten. STT retirement fixed separately. |
| DeepSeek | The October 17 Flash update retains the calling method and pricing. Existing explicit thinking controls and preservation of `reasoning_content` across tool turns match the documented contract. Search/Fetch billing began November 1; Chat4J already documents it and does not add standalone Search/Fetch calls. No additional request change identified. |
| xAI | Grok 4.7's Low/Medium/High/Extra High ladder is already represented. Existing search uses Responses, while the model guide says Chat Completions is unchanged. Custom moderation is optional; audio/image retirements do not affect Chat4J's text/image-input chat path. No mandatory request change identified. |
| LM Studio | OpenAI-compatible Chat Completions remains supported; native API recommendations do not force a transport rewrite. Existing model-specific reasoning metadata parsing remains in use. Only CLI version metadata and public docs were inspected; inference and local-server behavior were not verified. |
| Ollama | Current OpenAI compatibility docs explicitly support `reasoning_effort` and model-defined thinking metadata from `/api/show`, matching the existing implementation. Client `0.40.2` is installed, but the local daemon was unavailable. No server was started and no model was downloaded. |

### Together catalog additions

- `deepseek-ai/DeepSeek-V4.1-Flash`
- `deepseek-ai/DeepSeek-V4.1-Flash-1017`
- `meta-models/Muse-Spark-Open-816B-A42B`
- `MiniMaxAI/MiniMax-M3.1-Flash`
- `nvidia/NVIDIA-Nemotron-3.5-Super-VL`
- `Qwen/Qwen3.8-Flash`
- `reflection/Beam-1.0-501B-A23B`
- `together/Tev1-4B-experimental`
- `zai-org/GLM-5.3`
- `zai-org/GLM-5.3-Flash`

A model appears only when the authenticated API also returns it; this snapshot does not establish account availability.

## Speech-provider findings

| Provider | Current evidence and result |
| --- | --- |
| Groq STT/TTS | Distil Whisper retired on August 23, 2025; migrate the canonical hosted request to Turbo. Current Orpheus TTS defaults already replace retired PlayAI IDs. Multipart transcription and binary WAV synthesis contracts otherwise remain unchanged. |
| Deepgram STT/TTS | Existing batch `/v1/listen`, model catalogs, and `/v1/speak` contracts remain applicable. Recent self-hosted runtime changes and streaming Configure messages do not require changes to these hosted batch/synthesis calls. |
| ElevenLabs STT/TTS | Existing Scribe v2 multipart requests, array/enveloped model catalogs, voice discovery, and synthesis contracts remain applicable. New agents, video, and optional transcription fields are outside the implemented calls. |
| AssemblyAI | Existing upload/create/poll/delete flow uses `speech_models`, with omission selecting the documented Universal-3.5 Pro/Universal-2 defaults. Retired legacy Summarization and Auto Chapters parameters are not sent. No migration to Speech Understanding is required for plain transcription. |
| ListenHub | The current reference still documents `api.marswave.ai/openapi/v1`, Bearer authentication, `POST /audio/speech`, and binary audio for the existing single-voice path. No mandatory request change identified. |
| Speechify | Already uses `api.speechify.ai`, not the legacy host scheduled for January 2027 retirement. Simba 3.0/3.2 remain available; no automatic switch to the differently priced Simba 3.5. The September PCM-rate correction does not affect Chat4J's explicit MP3 path. |
| Whisper.cpp / Vosk / system TTS | These are local native/process integrations, not hosted provider APIs. Pinned runtime dependencies were not upgraded. Existing tests exercise application-owned boundaries; no audio recordings, native model downloads, or end-to-end transcription/playback checks were performed. |

## Responses-native Agent Mode

The current OpenAI model cards require the **Responses API for tool calling** on GPT-6.1 Sol and GPT-6.1 Terra. GPT-6 Sol and Luna allow Chat Completions function calling only at None effort. `AgentProviderAdapterFactory` selects `OpenAiResponsesToolAgentAdapter` for those four exact IDs under the OpenAI provider. It uses the configured OpenAI base URL. Other model/provider routes, including OpenRouter, Copilot, and Codex, retain their existing adapters.

The adapter:

- Sends flattened Responses function definitions with `strict: false`, preserving optional parameters and existing local/MCP input schemas.
- Uses non-streaming tool turns, matching the prior agent adapter, with `store: false` and encrypted-reasoning inclusion. It replays complete output items and tool results within the current agent run rather than relying on stored response IDs.
- Preserves opaque reasoning, function-item IDs, `call_id` values, and assistant `phase`. It removes the output-only `created_by` provenance field before replay.
- Matches each result to its function `call_id`, rejects missing/duplicate/unmatched result batches, and retains pending state after failed continuations.
- Validates completion status and the full output batch before dispatching tools. Incomplete, failed, malformed, or unsupported outputs cannot report successful completion or dispatch a partial batch.
- Uses the existing HTTP cancellation/timeout behavior, suppresses subsequent callbacks after cancellation, and preserves thread interruption. It does not automatically retry or fall back to another endpoint.
- Keeps local filesystem confinement and MCP approval, invocation, failure-result, and session-close behavior in the existing orchestrator.

Continuation state lasts only for the current agent run; it is not persisted between user turns. This change does not add SSE progress, background Responses jobs, provider-hosted MCP tools, native image/file attachment transmission, or new built-in tools.

## Verification boundaries

The full local coverage build passed with **3,585 tests, zero failures/errors, and seven skips**, including isolated shaded WebP, MCP Jackson, and PDF-export checks. This was verified on macOS with OpenJDK 21.0.13.0.3; Linux/Windows CI was not run for this uncommitted worktree.

On this macOS host the inherited `JAVA_HOME` points to an older, removed Homebrew installation, so the build uses the installed Java 21 explicitly without changing global configuration:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -ntp -Pcoverage verify
```

Regression tests reproduce the prior failures in Claude payloads, Together availability, Terra choices/search, Groq STT requests, and native Responses tool dispatch. The Responses adapter has 38 controlled-HTTP test cases covering multi-round replay, malformed/failing output, result matching, local/MCP tools, approval denial, and cancellation/interruption while awaiting headers or body. The full build reports 90.8% line and 77.3% branch coverage for the new adapter. Checks use synthetic data and controlled local HTTP/transport fixtures. Codex schema generation used a disposable `CODEX_HOME`, which was removed afterward; no global Codex login was read or used.

No billable provider inference, OAuth login/refresh, production account mutation, shared-service startup, model download, or native audio playback was performed. Passing local tests does not establish current account entitlements, provider uptime, live inference behavior, or native audio quality.

## Primary references

- [Claude release notes](https://platform.claude.com/docs/en/release-notes/overview), [Haiku 5.5 migration](https://platform.claude.com/docs/en/models/haiku-5-5/migration-guide), [effort controls](https://platform.claude.com/docs/en/build-with-claude/effort), [Mythos 5](https://platform.claude.com/docs/en/models/mythos-5/overview), [Mythos 5.1](https://platform.claude.com/docs/en/models/mythos-5-1/overview), [API versioning](https://platform.claude.com/docs/en/api/versioning)
- [OpenAI changelog](https://developers.openai.com/api/docs/changelog), [GPT-6.1 Terra](https://developers.openai.com/api/docs/models/gpt-6.1-terra), [GPT-6.1 Sol](https://developers.openai.com/api/docs/models/gpt-6.1-sol), [GPT-6 Sol](https://developers.openai.com/api/docs/models/gpt-6-sol), [GPT-6 Luna](https://developers.openai.com/api/docs/models/gpt-6-luna), [function calling](https://developers.openai.com/api/docs/guides/function-calling), [reasoning continuation](https://developers.openai.com/api/docs/guides/reasoning), [SDK replay normalization](https://github.com/openai/openai-node/blob/master/src/lib/responses/ResponseInputItems.ts), [Codex app-server](https://developers.openai.com/codex/app-server)
- [Copilot October retirements](https://github.blog/changelog/2026-10-19-github-copilot-models-deprecated-on-october-19-2026/), [scheduled November retirements](https://github.blog/changelog/2026-10-15-upcoming-deprecation-of-selected-github-copilot-models-in-mid-November/)
- [OpenRouter model catalog](https://openrouter.ai/api/v1/models), [reasoning format](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens)
- [Together serverless catalog](https://docs.together.ai/docs/serverless-models), [reasoning guide](https://docs.together.ai/docs/inference/chat/reasoning), [US-only inference](https://docs.together.ai/docs/serverless/geo-specific-inference)
- [Groq deprecations](https://console.groq.com/docs/deprecations), [DeepSeek updates](https://api-docs.deepseek.com/updates/), [Grok 4.7](https://docs.x.ai/developers/grok-4-7)
- [Ollama compatibility](https://docs.ollama.com/api/openai-compatibility), [LM Studio API changelog](https://lmstudio.ai/docs/developer/api-changelog)
- [Deepgram changelog](https://developers.deepgram.com/changelog), [ElevenLabs transcription](https://elevenlabs.io/docs/api-reference/speech-to-text/convert), [AssemblyAI transcription](https://www.assemblyai.com/docs/api-reference/transcripts/submit)
- [ListenHub TTS](https://listenhub.ai/docs/en/openapi/api-reference/flowspeech), [Speechify changelog](https://docs.speechify.ai/build/changelog)
