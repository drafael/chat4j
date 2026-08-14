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
| OpenAI tool-capable models | Optional Responses `web_search` | URL citation annotations |
| OpenAI search-preview models | Required Chat Completions route | Provider response citations |
| xAI Grok | Optional Responses web search | URL citation annotations |
| Google Gemini | Optional native `google_search` grounding | Grounding citations |
| Groq Compound | Required model-semantic search | Search-result citation metadata when supplied |
| OpenRouter `:online` / namespaced Sonar | Required model-semantic search | URL annotations when supplied |
| Perplexity Sonar | Required standalone-provider search | Citations and search results |

Mistral web search is not exposed because the current Chat Completions transport does not implement its Conversations/Agents search API.

## Citations versus consulted sources

`CitationRef` represents provider metadata that associates evidence with an answer claim. Chat4J numbers, persists, renders, and exports those citations.

`WebSearchSource` represents a page the provider reports consulting without a documented claim association. DeepSeek uses this form. Chat4J renders it under **Sources consulted** and never fabricates claim-level citation markers from source order.

Native search activity, citations, consulted sources, attachments, cancellation, persistence, transcript rendering, and exports remain on the selected provider request path.

## Provider references

- OpenAI web search: https://developers.openai.com/api/docs/guides/tools-web-search
- Anthropic web search: https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-search-tool
- Google grounding with Search: https://ai.google.dev/gemini-api/docs/interactions/google-search
- xAI web search: https://docs.x.ai/developers/tools/web-search
- Perplexity Sonar API: https://docs.perplexity.ai/api-reference/sonar-post.md
- Groq built-in web search: https://console.groq.com/docs/tool-use/built-in-tools/web-search
- OpenRouter web search: https://openrouter.ai/docs/guides/features/plugins/web-search
