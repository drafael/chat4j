# Native Web Search and Citations

Chat4J's Web Search control applies only to the selected provider, model, and effective endpoint. It never sends a prompt to a different provider as a search fallback.

## Runtime behavior

- **Optional search** shows a directly toggleable Web Search button.
- **Required search** shows the button selected and locked because the selected transport has no non-searching mode. This includes standalone Perplexity Sonar, Groq Compound, OpenRouter `:online`/namespaced Sonar, and OpenAI search-preview models on their documented endpoints.
- **Unsupported or unresolved search** hides the button.
- Agent Mode and effective Web Search are mutually exclusive.
- A native-search failure is reported normally. Chat4J does not retry the request without search.
- Custom endpoints do not inherit native routes from provider or model names, except standalone Perplexity Sonar because its selected transport is inherently searching.

Perplexity remains a standalone chat provider. It is not used to add search to another provider.

## Supported native transports

| Provider/runtime | Search behavior | Source metadata |
| --- | --- | --- |
| Anthropic Claude | Optional native web-search tool | Claim-linked citation deltas |
| DeepSeek V4 on the official endpoint | Optional Anthropic-compatible native search | Structured **Sources consulted** |
| Connector-capable Mistral models on the official endpoint | Optional Conversations `web_search` | Claim-linked `tool_reference` citations |
| OpenAI Codex CLI models | Optional CLI/app-server search with explicit `live` or `disabled` mode | Opened pages under **Sources consulted** when supplied |
| GitHub Copilot `gpt-5.4-mini` on the official endpoint with `/responses` metadata | Optional Responses `web_search` | URL citation annotations |
| OpenAI tool-capable models | Optional Responses `web_search` | URL citation annotations |
| OpenAI search-preview models | Required Chat Completions route | Provider response citations |
| xAI Grok | Optional Responses web search | URL citation annotations |
| Google Gemini | Optional native `google_search` grounding | Grounding citations |
| Groq Compound | Required model-semantic search | Search-result citation metadata when supplied |
| OpenRouter `:online` / namespaced Sonar | Required model-semantic search | URL annotations when supplied |
| Perplexity Sonar | Required standalone-provider search | Citations and search results |

Mistral search-enabled requests use the stateless Conversations API with `store:false` and the standard `web_search` tool. Ordinary Mistral chat continues through Chat Completions. Chat4J does not request `web_search_premium`, and custom Mistral endpoints do not inherit hosted Conversations search. Only the connector-capable Mistral Small, Medium, and Large families advertise Web Search. Specialized families that the hosted API rejects for built-in connectors—including Codestral, Devstral, Ministral, Leanstral, and Mistral Code—remain available for ordinary chat but do not advertise Web Search. Conversations accepts only `user` and `assistant` inputs, so leading system messages are sent through its top-level `instructions` field; a later system message is rejected rather than reordered. Mistral does not expose the search query text, so Chat4J does not invent a **Searched** entry from the user prompt. The current API accepts reasoning effort `none` or `high`; Chat4J maps disabled reasoning to `none` and enabled levels to `high` on this route.

Codex subprocesses always receive an explicit search mode. Enabled turns use `web_search="live"`; disabled turns use `web_search="disabled"`, overriding cached or user-level Codex defaults. App-server-to-exec fallback preserves that mode, selected reasoning effort, and the admitted Chat4J account through an isolated temporary `CODEX_HOME`. The Web Search activity records only query actions actually emitted by Codex; enabling the tool alone is not displayed as a completed search. Codex may search without exposing an opened page URL, so **Sources consulted** can remain empty after an observed search. Chat4J never promotes final Markdown links into citations.

GitHub Copilot search is restricted to the official endpoint, the live-proven `gpt-5.4-mini` model, and cached model metadata that explicitly includes `/responses`. Search turns force Responses and fail terminally rather than falling back to Chat Completions or retrying without the tool. This route is live-verified but is not a published stable Copilot Web Search API contract.

Google AI `gemini-*-latest` aliases do not advertise Web Search because those catalog aliases do not expose native Google Search grounding. Eligible versioned and preview Gemini model IDs continue to use `google_search` on the official endpoint.

Ollama and LM Studio remain unsupported. Ollama's hosted search endpoint is a separate service, while LM Studio search requires Bionic, custom functions, plugins, or MCP orchestration; those paths are outside Chat4J's native-only definition.

## Citations versus consulted sources

`CitationRef` represents provider metadata that associates evidence with an answer claim. Chat4J numbers, persists, renders, and exports those citations.

`WebSearchSource` represents a page the provider reports consulting without a documented claim association. DeepSeek and Codex opened-page events use this form. Chat4J renders it under **Sources consulted** and never fabricates claim-level citation markers from source order.

Native search activity, citations, consulted sources, attachments, cancellation, persistence, transcript rendering, and exports remain on the selected provider request path. Chat4J displays query text only when the provider reports the query; it never substitutes the user's prompt or mere tool enablement as a completed search.

## Provider references

- OpenAI web search: https://developers.openai.com/api/docs/guides/tools-web-search
- Anthropic web search: https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-search-tool
- Mistral web search: https://docs.mistral.ai/studio/agents/agent-tools/websearch
- Mistral Conversations API: https://docs.mistral.ai/api/endpoint/beta/conversations
- OpenAI Codex configuration: https://developers.openai.com/codex/config-reference
- OpenAI Codex app-server: https://developers.openai.com/codex/app-server
- Google grounding with Search: https://ai.google.dev/gemini-api/docs/interactions/google-search
- xAI web search: https://docs.x.ai/developers/tools/web-search
- Perplexity Sonar API: https://docs.perplexity.ai/api-reference/sonar-post.md
- Groq built-in web search: https://console.groq.com/docs/tool-use/built-in-tools/web-search
- OpenRouter web search: https://openrouter.ai/docs/guides/features/plugins/web-search
- Ollama hosted web search: https://docs.ollama.com/capabilities/web-search
- LM Studio tools and MCP: https://lmstudio.ai/docs/developer/core/mcp
