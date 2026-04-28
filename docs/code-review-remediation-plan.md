# Code Quality Remediation Status

This document tracks the **current remediation status** for code-review findings around `MainFrame`, provider runtime behavior, and regression confidence.

## Completed remediations

### High priority
- ✅ Responses-input parity for part-based messages
  - `OpenAiChatCompletionClient` now preserves projected text from message parts in responses-mode requests.
  - Covered by `OpenAiChatCompletionClientTest#toResponsesInputLine_whenMessageContainsParts_includesProjectedContent`.

### Medium priority
- ✅ Copilot token-exchange overhead reduction
  - Runtime/model-refresh flows avoid repeated unnecessary exchange calls.
  - Implemented across `ProviderFacade` and `OpenAiModelCatalogClient` with cache/backoff behavior.

- ✅ Copilot token file permission hardening
  - Token persistence now applies stricter local file protection behavior.
  - Covered by `CopilotAuthResolverTest` login persistence scenario.

- ✅ Copilot routing/diagnostics test coverage improvements
  - Added targeted coverage in `OpenAiChatCompletionClientTest` for endpoint/routing diagnostics behavior.

### Low priority
- ✅ README Copilot OAuth setup clarification
  - Added explicit setup section to reduce first-run configuration failures.

## Status

- Provider/auth/runtime hardening work for the reviewed Copilot/OpenAI-compatible paths is in place.
- Logging and diagnostics behavior is consolidated and test-covered for key failure and fallback paths.
- Remaining work is mostly structural/maintainability and broader integration confidence, not a blocker for core runtime behavior.

## Open hardening backlog (current)

- `MainFrame` maintainability refactors
  - Extract constructor wiring/composition concerns.
  - Consolidate mutable UI state into clearer holders.
  - Simplify coordinator contracts where APIs carry unused return semantics.

- Shutdown responsiveness hardening
  - Ensure close/quit save paths avoid EDT blocking under slow storage conditions.

- Broader integration-level confidence
  - Expand high-value orchestration/integration tests for menu sync, save/load switching flow, and close/quit behavior.

## Verification baseline

When touching any of the areas above, run:

- `mvn -q test`
- targeted tests for changed behavior
- `mvn -q -DskipTests package`
