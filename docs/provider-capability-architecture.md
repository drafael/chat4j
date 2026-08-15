# Provider Capability Architecture

This document describes the provider architecture under `src/main/java/com/github/drafael/chat4j/provider/**` after the capability-based refactor.

## Goals

- Keep provider integration extensible and testable.
- Remove provider-specific branching from registry wiring.
- Centralize shared behaviors: credential resolution, model ordering, streaming lifecycle.

## Package Structure

- `provider/api`
  - `ProviderDescriptor`: provider metadata and base URL normalization strategy.
  - `ProviderCapabilities`: declared provider feature flags.
  - `ProviderService`: runtime service contract used by chat UI; streams text, thinking, rich parts, and optional citation metadata.

- `provider/core`
  - `ProviderRuntime`: resolved runtime context (descriptor, key, base URL, model).
  - `ProviderFacade`: runtime resolver for credentials + base URL.
  - `ProviderModule`: module contract for chat/model capability wiring.
  - `CapabilityProviderService`: adapter from capability clients to `ProviderService`.
  - `core/error/*`: domain-level provider exception types.

- `provider/capability`
  - `auth`: credential strategy abstraction and env-var implementation.
  - `chat`: streaming completion contract + protocol implementations, including provider citation normalization when supported.
  - `models`: model catalog contract + protocol implementations.

- `provider/modules`
  - `AnthropicModule`
  - `OpenAiCompatibleModule`, which owns narrow protocol-client selection when an otherwise compatible provider has a different catalog or chat contract.

- `provider/registry`
  - `ProviderCatalog`: provider module inventory and factory/fetcher creation.
  - `ProviderRegistry`: runtime policy application + exposed provider definitions.

## Runtime Flow

1. `ProviderRegistry` reads enabled providers from `ProviderCatalog`.
2. `ProviderCatalog` resolves a `ProviderRuntime` through `ProviderFacade`.
3. `ProviderModule` builds:
   - a `ProviderService` for chat streaming;
   - a `ModelFetcher` for model discovery.
4. `CapabilityProviderService` delegates to chat/model capability clients.

## Credential Resolution

- Credential aliases are supported using `A|B|C` env-var expressions.
- Runtime API-key resolution checks saved UI token overrides first, then process environment, shell-loaded environment, and finally provider fallback keys.
- Raw environment helpers such as `CredentialResolver.getenv(...)` and `mergedEnvironment()` remain environment-only and do not expose saved vault tokens.
- Google AI uses:
  - `GEMINI_API_KEY`
  - fallback `GOOGLEAI_API_KEY`

## Model Listing and Ordering

- Model catalogs are loaded dynamically from provider APIs.
- Model IDs are sanitized and sorted through shared `ModelOrdering` rules.
- Cached model lists use the same sanitize/order logic for consistency.
- Together's authenticated `GET /models` response is a top-level array, so `OpenAiCompatibleModule` selects a dedicated `TogetherModelCatalogClient` instead of the generic OpenAI catalog client.
- Together publication intersects structurally valid returned chat IDs with the dated exact hosted-serverless snapshot in `TogetherModelSupport`. `ModelOrdering` applies that intersection to fresh, seeded, overlaid, and disk-cached lists. An empty refresh can still expose the existing snapshot-bounded last-known cache under the shared cache contract.
- The Together ID intersection also applies to custom base URLs for this initial integration. Matching an ID there is not proof that the custom endpoint is hosted serverless or has Together's deployment semantics.

## Capability Specialization

Provider registries remain provider-neutral. Provider-specific capability evidence belongs at the resolver or protocol-client boundary. Hosted Together uses exact, case-sensitive dated model sets before generic probes and hints. Custom Together base URLs remain text-only and do not inherit hosted capability, reasoning, response-schema, or HTTP-status semantics.

Ordinary OpenAI-compatible chat streams through `OpenAiChatCompletionClient`. Agent Mode tool turns use the separate non-streaming `OpenAiToolAgentAdapter`; support proven on one transport does not establish support on the other.

Together reasoning levels are intentionally lossy because provider models expose different controls. Binary hybrid models map every non-Off level to enabled; GPT-OSS maps High and Extra High to `high`; Nemotron maps Low and Medium to its medium-effort flag and higher levels to the provider default; and the reviewed DeepSeek/GLM models map enabled levels to `high`, except Extra High maps to `max`. Together documents `high`/`max` for those models but does not define a stable semantic equivalence to Chat4J's High and Extra High labels, so the mapping is a product policy rather than a claim of identical reasoning depth.

## Error Handling

`core/error` provides domain-oriented exception classes:

- `ConfigurationException`
- `AuthenticationException`
- `RateLimitException`
- `InvalidRequestException`
- `ProviderUnavailableException`
- `StreamingInterruptedException`

`ProviderExceptionMapper` maps protocol/client exceptions into these types.

## How to Add a New Provider

For OpenAI-compatible endpoints:

1. Add a descriptor entry in `ProviderCatalog` using `OpenAiCompatibleModule`.
2. Set provider name, env var expression, fallback key (if any), and base URL.
3. Keep the registry provider-neutral. Most providers need no additional branching; when a documented protocol difference exists, `OpenAiCompatibleModule` may select a narrow provider-specific client, as it does for Together's top-level-array model catalog.

For non-OpenAI protocols:

1. Add capability implementations under `provider/capability/*/impl`.
2. Create a dedicated `ProviderModule`.
3. Register the module in `ProviderCatalog`.

## Testing Notes

- `provider/modules/AnthropicModuleTest` validates base URL normalization.
- `ProviderRegistryTest` covers runtime policy and factory behavior.
- `ProviderCatalogTest` validates dynamic model fetching behavior.
- `CredentialResolverTest` validates saved-token precedence and env-var alias precedence.
