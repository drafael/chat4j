# Provider API compatibility audit — 2026-11-27

This follow-up covers all 14 registered chat providers, six speech-to-text providers, and six text-to-speech providers. Findings are based on current official documentation, public catalogs, source inspection, and controlled local tests. Account entitlements and billable inference were not exercised. The [November 2 audit](provider-api-audit-2026-11.md) records the earlier changes.

## Applied changes

- **Anthropic discovery:** fetch every model page and stop when `has_more` is false. The pinned Java SDK 2.57.0 auto-pager ignores that flag when `last_id` is present, so both model IDs and model metadata use a small shared page-fetch method instead. The SDK and API version pin are unchanged.
- **Claude:** recognize `claude-fable-5-2` and `claude-mythos-5-2` as mandatory adaptive-thinking models. Ordinary chat and tool turns no longer send unsupported manual thinking budgets for them. Their choices are Low through Max, without Off or Ultra; the recommendation is High. Older model controls remain intact.
- **OpenAI:** recognize GPT-6.2 Astra/Sol's Low/Medium/High/Extra High/Max choices, with Medium as Chat4J's recommendation. Route their Agent Mode tool calls, and GPT-6 Astra's, through the existing Responses adapter. The model guide requires Responses for their tool calling. Ordinary non-search chat retains Chat Completions; no new sampling or caching parameters are sent.
- **Google AI:** add Gemini 4 Carbon's Low/Medium/High choices and Gemini 4 Hydrogen/Neon's Low/Medium/High/Extra High choices. Native Hydrogen/Neon recommend High. Encode Extra High as `xhigh`, not the application's persisted `extra_high` spelling.
- **OpenRouter:** add separately verified gateway profiles for GPT-6.2 Astra/Sol and their Pro variants, Gemini 4 Carbon/Hydrogen/Neon, and Qwen4 Max. The public gateway catalog recommends Medium for Hydrogen/Neon, unlike the native Google recommendation. Qwen4 Max offers Off/Low/High/Extra High, without Medium. Claude Fable 5.2 uses the shared Claude policy.
- **Codex:** refresh the bundled fallback with `gpt-6.2-astra`, `gpt-6.2-sol`, and `gpt-6.1-terra`. The official Codex catalog advertises Low through Ultra and a Low default for all three. These are CLI-specific choices, not the native OpenAI API ladder. The GPT-6.2 entries require Codex 0.166.0 or later. Authoritative local account metadata still overrides fallback visibility and efforts.
- **Together:** admit seven additional published serverless chat IDs through the existing API/catalog intersection. New entries do not acquire unreviewed reasoning, vision, or tool-continuation controls merely from their names.
- **Mistral:** recognize `zai-glm-5-3-flash` as Low/High/Max, instead of offering unsupported generic choices. Its native Web Search availability is unchanged.
- **Perplexity:** expose the documented `xhigh` and `ultra` managed research configurations alongside Fast/Low/Medium/High. They use the existing background submission, polling, cancellation, and 15-minute deadline. They are top-level research efforts, not model IDs or `reasoning.effort` values.
- **ElevenLabs:** bundle recorded-audio `scribe_v3` while retaining the `scribe_v2` default. Exclude live-only `scribe_v3_live` from batch discovery, even when a catalog marks it speech-to-text capable. TTS voice discovery moves to paginated `/v2/voices`; the public OpenAPI description says `/v1/voices` stops working above 500 workspace voices.
- **AssemblyAI:** add explicit `assemblyai-stt-3-7`, retain Universal-2 and deprecated-but-available Universal-3.5 Pro, and describe Automatic as the account's provider default. Omitted `speech_models` now selects STT-3.7 for new accounts, but earlier accounts can differ. No saved selection is silently migrated.
- **Speechify:** add explicit opt-in Simba 3.5 without changing the Simba 3.0 default. Follow voice-catalog cursors, including empty intermediate pages. Speechify and ElevenLabs reject missing/repeated continuation tokens or failed later pages instead of returning a partial catalog for caching.

### Together catalog additions

- `deepseek-ai/DeepSeek-V4.1-Flash-1017-fast`
- `elorian-labs/glimpse-1.0`
- `moonshotai/Kimi-K3.1-Pro`
- `Qwen/Qwen4-Max`
- `ricursive-ai/Ripple-65B-Preview`
- `tencent/Hy4`
- `thinkingmachines/Inkling-2-Small`

The authenticated API must also return an ID before discovery exposes it. Existing accepted IDs remain supported when returned by the API.

## Chat-provider review

| Provider | Finding and action |
| --- | --- |
| Anthropic | November 9 Fable/Mythos 5.2 require adaptive thinking; legacy budget mode returns HTTP 400. Profiles and pagination updated. Message threads and newer API-version migrations remain optional, separate features. [Model guide](https://platform.claude.com/docs/en/models/fable-5-2/migration-guide), [Models API](https://platform.claude.com/docs/en/api/models/list). |
| Google AI | November's Carbon, Hydrogen, and Neon releases add model-specific thinking choices. Profiles and native `xhigh` encoding updated. Live discovery supplies model IDs; image/video/Decisions API releases do not automatically become new Chat4J features. [Release notes](https://aistudio.google.com/docs/changelog), [thinking controls](https://ai.google.dev/gemini-api/docs/generate-content/thinking). |
| OpenAI Codex | Public Codex catalog and model guide list the new GPT-6.2 models. Fallback updated. Installed CLI 0.172.0 generated schemas offline; initialize, thread/start, and turn/start still accept the existing request fields. No login, account refresh, or inference was performed. [Catalog source](https://github.com/openai/codex/blob/main/codex-rs/models-manager/models.json), [Codex models](https://developers.openai.com/codex/models). |
| GitHub Copilot | November 16 retirements include account exceptions: Opus 4.8 remains available to some annual subscribers. Keep live model/endpoint discovery rather than globally deleting or rewriting cached selections. The private backend and account entitlements remain unverified. [Retirement notice](https://github.blog/changelog/2026-11-16-github-copilot-models-deprecated-on-november-16-2026/). |
| OpenAI | November 17 GPT-6.2 releases require Responses for tool calling and reject `none` effort. Profiles/routing updated. November 30 Prompts/Evals/Agent Builder shutdowns do not affect the implemented chat calls. [Model index](https://developers.openai.com/api/docs/models), [migration guide](https://developers.openai.com/api/docs/guides/latest-model), [deprecations](https://developers.openai.com/api/docs/deprecations). |
| Perplexity | Managed effort names have expanded to six. Add XHigh/Ultra without freezing their changing underlying model configurations. Context/Monitor/Orchestrator additions are separate APIs. [Effort](https://docs.perplexity.ai/docs/agent-api/effort.md), [changelog](https://docs.perplexity.ai/changelog/changelog). |
| OpenRouter | Public catalog now supplies model-specific `reasoning.supported_efforts`, `mandatory`, and `default_effort`. Reviewed new profiles follow gateway evidence, including different native/gateway defaults. Existing live discovery still supplies IDs, limits, and prices. [Public catalog](https://openrouter.ai/api/v1/models), [reasoning format](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens). |
| Together | Current serverless table contains seven additional chat IDs missing from the prior snapshot. Intersection updated; endpoint selection and earlier capability snapshots are preserved. US-only inference remains explicitly configured and differently priced. [Serverless models](https://docs.together.ai/docs/serverless-models). |
| Groq | Compound remains retired without a listed replacement. Current Whisper Turbo and Orpheus speech IDs already replace retired Distil Whisper and PlayAI models. No additional mandatory request change identified; chat IDs continue to come from the API. [Deprecations](https://console.groq.com/docs/deprecations). |
| DeepSeek | Flash remains V4.1-Flash-1017 behind the documented alias. November's standalone audio/file tools are not implemented integrations. Model-triggered Search/Fetch stopped carrying a separate per-call fee on November 18; returned context still incurs token charges. Correct the search documentation, without adding new calls. [Updates](https://api-docs.deepseek.com/updates/), [pricing](https://api-docs.deepseek.com/quick_start/pricing/). |
| Mistral | GLM 5.3 Flash exposes Low/High/Max, not None. Profile updated. Existing Chat Completions and stateless Conversations search remain appropriate; managed indexes and voice agents do not require changing those calls. [Reasoning](https://docs.mistral.ai/inference/conversations/reasoning), [changelog](https://docs.mistral.ai/resources/changelogs). |
| xAI | `grok-build-0.1` retired November 19; discovery remains live rather than adding a replacement alias. Current Grok 4.7 controls and Responses search remain applicable. Image-generation and voice-model migrations are outside the implemented chat path. [Release notes](https://docs.x.ai/developers/release-notes). |
| LM Studio | Current OpenAI-compatible Chat Completions/Responses endpoints remain supported. Bionic's newer decision-model endpoints do not force a chat transport migration. Existing local reasoning metadata discovery remains in use. No local server was started. [Compatibility](https://lmstudio.ai/docs/developer/openai-compat), [changelog](https://lmstudio.ai/changelog). |
| Ollama | Current compatibility docs still support model-defined `reasoning_effort` and `/api/show` discovery. Keep the existing implementation and installed-model listing; do not ship a fixed hosted model list. Local inference was not exercised. [Compatibility](https://docs.ollama.com/api/openai-compatibility). |

## Speech-provider review

| Speech-to-text provider | Finding and action |
| --- | --- |
| Groq | Current multipart transcription and Whisper Turbo replacement already match the documented contract. No further change. [Deprecations](https://console.groq.com/docs/deprecations). |
| ElevenLabs | November 9 adds recorded `scribe_v3` and live `scribe_v3_live`; v2 remains available. Update batch choices/filtering without changing the default or enabling live transcription. [Release](https://elevenlabs.io/docs/changelog/2026/11/9), [batch endpoint](https://elevenlabs.io/docs/api-reference/speech-to-text/convert). |
| Deepgram | November Nova-3 language/quality updates retain the batch API and `nova-3` ID. Live model discovery and existing automatic language detection remain in use; new explicit language selectors were not added. [November 24 update](https://developers.deepgram.com/changelog/2026/11/24). |
| AssemblyAI | STT-3.7 is the current recommended prerecorded model. Defaults are account-dependent, and Universal-3.5 Pro is deprecated without an announced retirement date. Update bundled choices and labels, preserving explicit selections. [Model selection](https://www.assemblyai.com/docs/pre-recorded-audio/select-the-speech-model), [submission schema](https://www.assemblyai.com/docs/api-reference/transcripts/submit). |
| Whisper.cpp | Upstream v1.9.6 is available, but Chat4J uses a separately packaged JNI binding. Existing application-owned model/install/transcription tests remain applicable; no native upgrade or model download was performed; compatibility of a newer native runtime remains unverified. [Release](https://github.com/ggml-org/whisper.cpp/releases/tag/v1.9.6), [models](https://github.com/ggml-org/whisper.cpp/blob/master/models/README.md). |
| Vosk | All 131 bundled fallback catalog records matched the public JSON catalog by their parsed fields. No resource rewrite or model download was needed. [Public catalog](https://alphacephei.com/vosk/models/model-list.json). |

| Text-to-speech provider | Finding and action |
| --- | --- |
| System | No hosted model catalog applies. Retain installed-voice discovery through the platform adapters. No OS voice enumeration, native playback, or platform runtime upgrade was performed for this audit. |
| Deepgram | Hosted `/v1/models` and `/v1/speak` remain applicable. New Aura-2 marketplace payment options are separate from the existing API-key path. [Changelog](https://developers.deepgram.com/changelog/2026/11/20). |
| Groq | Orpheus defaults already replace retired PlayAI IDs; current model and bundled voice choices remain applicable. [TTS guide](https://console.groq.com/docs/text-to-speech/), [deprecations](https://console.groq.com/docs/deprecations). |
| ElevenLabs | Migrate voice discovery from V1's documented 500-voice workspace ceiling to cursor-paginated V2. Preserve models, synthesis, selected voice IDs, and defaults. [Current voice endpoint](https://elevenlabs.io/docs/api-reference/voices/search), [public OpenAPI](https://api.elevenlabs.io/openapi.json). |
| ListenHub | Existing Bearer-authenticated `api.marswave.ai/openapi/v1/audio/speech` binary synthesis remains documented. Long-form and multi-speaker APIs are separate optional features. No mandatory request change identified. [Reference](https://listenhub.ai/docs/en/openapi/api-reference/flowspeech). |
| Speechify | Explicit Simba 3.0/3.2 remain available; add Simba 3.5 as opt-in. Repair paginated voice discovery. Legacy Simba 1.6 IDs can now route/bill as 3.5 or be rejected under newer workspace/API-version rules, so review legacy saved selections rather than silently changing language coverage or cost. [Voices](https://docs.speechify.ai/build/api-reference/v1/voices/get), [migration](https://docs.speechify.ai/build/migrating-off-simba-1-6), [November correction](https://docs.speechify.ai/build/changelog/2026/11/10). |

## Catalog and cache behavior

- Chat catalogs retain the existing 12-hour refresh policy, five-minute empty-catalog retries, explicit refresh controls, and endpoint-scoped cache handling. No user cache files were inspected, deleted, or rewritten by this audit.
- Perplexity's menu uses the static managed-effort list directly, so stale Sonar cache entries do not hide the newly added choices.
- Codex's bundled fallback is used only where local account metadata is not authoritative. Account-specific visibility, defaults, and supported efforts continue to win.
- Speech-to-text retains its 24-hour staleness policy and existing cached/bundled model merge. TTS retains its settings-panel refresh and explicit **Refresh catalogs** action.
- Speech voice discovery publishes only a completed fetch. An invalid cursor, repeated cursor, or later-page failure throws before the settings panel saves the catalog, preserving the prior snapshot. There is no arbitrary total-model or total-voice quota.
- Vosk's matching fallback catalog is left intact. No shared model-cache schema, credential storage, or persisted selection format changed.

## Verification boundaries

Local full-suite verification passed: **3,659 tests, zero failures/errors, 32 skipped**. JaCoCo coverage gates and `scripts/check-http-json-boundaries.sh` passed. Earlier focused verification passed 764 cases before the final Mistral and ElevenLabs additions.

Tests exercise current catalog pagination, encoded continuation tokens, incomplete/failing pages, model selection, route-specific effort payloads, native Responses tool routing, and preservation of account-specific Codex metadata. HTTP tests use synthetic credentials and controlled local servers/transports.

The installed Codex CLI 0.172.0 generated its JSON schemas under a disposable `CODEX_HOME` and `HOME`. Required initialize/thread/start/turn/start fields match the current implementation; reasoning effort remains a non-empty model-defined string. This is offline protocol evidence, not an authenticated app-server turn.

The full verification command is:

```bash
mvn -B -ntp -Djava.awt.headless=true -Pcoverage verify
```

No billable inference, OAuth login/refresh, production-account mutation, user credential lookup, local service startup, model download, or native audio playback was performed. Tests cannot establish current account entitlements, vendor uptime, live inference/audio quality, or every newly released provider feature. Native/provider-account smoke checks remain separate from controlled HTTP and JVM verification.
