# Native Web Search and Citations

This document captures current provider support for native web search / grounding and outlines the implementation plan for bringing those features into Chat4J consistently.

## Goals

- Prefer provider-native search when a selected model supports it.
- Preserve external Perplexity search as a fallback option for providers without native search.
- Normalize provider citation metadata into `CitationRef` so the transcript renderer can show consistent clickable source markers.
- Avoid enabling web search for APIs that cannot return source metadata.
- Keep provider-specific request logic isolated in `provider/capability/chat/impl`.

## Current Chat4J state

- Anthropic already supports native web search through `WebSearchTool20250305` and maps citation deltas through `AnthropicCitationMapper`.
- OpenAI native search is partially implemented: `OpenAiChatCompletionClient` switches to Responses API with `WebSearchTool` for OpenAI when web search is enabled, but it currently streams text only and does not emit structured `CitationRef` metadata from URL annotations.
- Perplexity search is supported through `PerplexityChatCompletionClient`; Phase 1 emits structured `CitationRef` metadata from the same canonical `citations` / `search_results` source list while preserving the rendered Markdown links and Sources section.
- Google AI normal chat currently routes through the OpenAI-compatible fallback. Native Gemini `google_search` grounding is not implemented for normal text chat.
- xAI, Groq Compound, Mistral websearch, and OpenRouter web plugins are not implemented as first-class native search paths.
- `WebSearchAvailabilityResolver` already models two user-visible choices: native provider search and external Perplexity search.

## Provider findings

| Provider | Native support | Citation shape | Implementation note |
| --- | --- | --- | --- |
| Anthropic | Claude web search tool | Streaming citation deltas, including web-search result locations | Already implemented; keep as reference design. |
| Perplexity | Sonar models search by default; API exposes search controls | Top-level `citations` URL list and `search_results` objects | Text rendering is preserved; Phase 1 adds structured `CitationRef` emission. |
| OpenAI | Responses API `web_search`; Chat Completions has specialized search models | `message.content[].annotations[]` with `url_citation` URL/title/offsets; `web_search_call` can expose searched sources | Parse annotations and emit `CitationRef`; later expose context-size/domain controls. |
| Google Gemini | `google_search` grounding for supported Gemini 2.x/3.x models | `model_output.content[].annotations[]` with `url_citation`, plus `google_search_call` / `google_search_result` steps | Needs native Gemini text-generation path when web search is enabled. |
| xAI / Grok | Responses-compatible `web_search`; xAI SDK also supports web and X search | Response citations plus inline `url_citation` annotations | Likely implement through OpenAI-compatible Responses path for xAI models. |
| Groq | Built-in search for `groq/compound` and `groq/compound-mini` | `message.executed_tools[].search_results` with title/url/content/score | Enable only for Compound systems, not arbitrary Groq models. |
| Mistral | `web_search` / `web_search_premium` in Agents/Conversations API | `message.output.content[]` interleaves `text` and `tool_reference` chunks | Do not enable on current Chat Completions path; requires separate Conversations/Agents client. |
| OpenRouter | `:online` model variant or `plugins: [{"id":"web"}]` | Standardized OpenAI-style `message.annotations[].url_citation` | Support by adding plugin request body / `:online` handling and annotation parsing. |

## Source links

- OpenAI web search: https://developers.openai.com/api/docs/guides/tools-web-search
- Anthropic web search: https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-search-tool
- Google grounding with Search: https://ai.google.dev/gemini-api/docs/interactions/google-search
- xAI web search: https://docs.x.ai/developers/tools/web-search
- xAI citations: https://docs.x.ai/developers/tools/citations
- Perplexity Sonar API: https://docs.perplexity.ai/api-reference/sonar-post.md
- Groq built-in web search: https://console.groq.com/docs/tool-use/built-in-tools/web-search
- Mistral websearch: https://docs.mistral.ai/studio-api/agents/agent-tools/websearch
- OpenRouter web plugin: https://openrouter.ai/docs/guides/features/plugins/web-search

## Implementation principles

- Provider search should only be shown as “Native” when Chat4J can both call the provider-native API and surface citations or source metadata.
- Search availability should be conservative. False negatives are better than a visible Native option that silently falls back to uncited answers.
- `CitationRef` emission should not replace streamed text; it should enrich existing transcript rendering.
- Keep citation numbering deterministic per response with `CitationAccumulator`.
- Do not parse provider-generated Markdown citations as the only source of truth when structured metadata is available.
- Do not route Mistral Chat Completions through websearch; its docs explicitly require Conversations/Agents API for references.

## Phase 1: citation metadata for existing search paths

This first implementation phase is landing with the current Perplexity structured-citation work. It intentionally avoids new provider APIs and focuses on paths Chat4J already calls.

### Scope

1. Emit structured citations from Perplexity responses.
2. Add reusable URL-citation mapping helpers for providers that return URL/title/snippet metadata.
3. Add tests around citation numbering, de-duplication, Markdown/Sources compatibility, and transcript metadata.
4. Investigate and document whether OpenAI Responses streaming events expose citation annotations without losing streaming responsiveness. OpenAI citation extraction is a follow-up implementation when annotations are reachable; otherwise document the blocker for Phase 2.

### Concrete steps

1. Add a small mapper under `provider/capability/chat/impl`, for example `UrlCitationMapper`, that builds `CitationRef` from URL, title, and cited text/snippet. Offset handling is deferred until a provider needs it.
2. Update `PerplexityChatCompletionClient` to override the full `ChatCompletionClient.streamCompletion(...)` overload that includes `Consumer<CitationRef> onCitation`.
3. In `PerplexityChatCompletionClient.formatResponse(...)`, keep existing answer/Sources text behavior, but also return or emit the normalized source list as `CitationRef` values.
4. Use `CitationAccumulator` so duplicate URLs keep the first assigned citation number.
5. Add tests proving:
   - `search_results` become `CitationRef` records with title, URL, and snippet/cited text.
   - top-level `citations` URLs are de-duplicated with `search_results`.
   - existing formatted answer output remains stable.
   - `CapabilityProviderService` passes citations through to its caller.
6. Inspect OpenAI Responses stream event types for `url_citation` annotations and document the result. If annotations are reachable from existing streaming events, leave streaming behavior unchanged in this phase and implement OpenAI citation extraction in a follow-up. If not, record the exact SDK/API gap in this document.

### OpenAI Responses SDK streaming investigation

Inspected the resolved `com.openai:openai-java:4.30.0` SDK sources in `openai-java-core-4.30.0-sources.jar`. `ResponseStreamEvent` includes typed `outputTextAnnotationAdded()` / `asOutputTextAnnotationAdded()` accessors for `response.output_text.annotation.added` streaming events, and `ResponseOutputTextAnnotationAddedEvent` exposes the raw annotation as `JsonValue` via `_annotation()`. Final text content is also typed through `ResponseOutputText.annotations()`, whose `Annotation.UrlCitation` variant exposes `url()`, `title()`, `startIndex()`, and `endIndex()`. Because annotations are reachable without switching away from streaming, OpenAI URL-citation extraction can be implemented as a follow-up on the current Responses streaming path.

### Phase 1 acceptance criteria

- Perplexity-generated answers still render exactly as before or with only intentional citation-marker improvements.
- Perplexity `CitationRef` metadata reaches `ChatPanel.handleAssistantCitation(...)` through the existing provider service path.
- No new native-search option is exposed for a provider unless citations can be surfaced.
- Focused provider tests and full `mvn -q test` pass.

## Phase 2: OpenAI and xAI Responses-native web search

- Generalize the OpenAI-compatible Responses path so providers with Responses-compatible web search can use it, not only provider name `OpenAI`.
- Add capability hints for xAI/Grok web-capable models.
- Parse OpenAI/xAI `url_citation` annotations into `CitationRef`.
- Add tests for provider gating so xAI can use native search while unrelated OpenAI-compatible providers cannot accidentally receive Responses web-search requests.
- Consider exposing search context size (`low`, `medium`, `high`) after the base citation path is stable.

## Phase 3: Google Gemini grounding

- Add a native Gemini text-generation path for web-search-enabled Google AI requests.
- Send `tools: [{"type":"google_search"}]` for supported Gemini models.
- Parse `model_output.content[].annotations[]` URL citations.
- Preserve the existing image-output native Gemini path.
- Update capability detection so Google native search is visible only for supported Gemini models and only when the native path is implemented.

## Phase 4: Groq Compound

- Mark only `groq/compound` and `groq/compound-mini` as native-web-search capable.
- Parse `executed_tools[].search_results` into `CitationRef` and/or web-search activity metadata.
- Add tests confirming ordinary Groq models do not show the Native option.

## Phase 5: OpenRouter web plugin

- Support `:online` model variants and/or explicit `plugins: [{"id":"web"}]` when native search is selected.
- Parse OpenRouter `message.annotations[].url_citation` into `CitationRef`.
- Decide whether Chat4J should expose engine selection (`native`, `exa`, `perplexity`, etc.) or keep a single Native option initially.

## Phase 6: Mistral Conversations/Agents API

- Treat this as a separate provider-protocol project.
- Implement only if we want Mistral websearch badly enough to add Conversations/Agents runtime support.
- Parse `tool_reference` chunks as citations.
- Do not advertise Mistral Native web search before this path exists.

## Suggested implementation order

1. Phase 1 Perplexity structured citations and shared URL citation mapper.
2. OpenAI citation extraction if streaming annotations are accessible.
3. xAI Responses-native search and citation parsing.
4. Google Gemini grounding.
5. Groq Compound.
6. OpenRouter plugin support.
7. Mistral Conversations/Agents support.
