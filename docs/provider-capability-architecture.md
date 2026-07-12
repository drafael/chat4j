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
  - `OpenAiCompatibleModule`

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
3. No registry branching or provider-specific switch statements required.

For non-OpenAI protocols:

1. Add capability implementations under `provider/capability/*/impl`.
2. Create a dedicated `ProviderModule`.
3. Register the module in `ProviderCatalog`.

## Testing Notes

- `provider/modules/AnthropicModuleTest` validates base URL normalization.
- `ProviderRegistryTest` covers runtime policy and factory behavior.
- `ProviderCatalogTest` validates dynamic model fetching behavior.
- `CredentialResolverTest` validates saved-token precedence and env-var alias precedence.
