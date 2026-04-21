# Code Review Remediation Plan (Active Work)

Scope: remaining remediation work for `MainFrame` maintainability, shutdown responsiveness, and high-value integration confidence.

## Goals

1. Reduce residual `MainFrame` orchestration complexity.
2. Remove remaining UI responsiveness risk on shutdown/save paths.
3. Increase regression confidence with high-value integration tests.
4. Keep incremental, reviewable slices with strict verification gates.

---

## Current review findings (Copilot/auth/runtime)

### High
- [x] **Responses input parity for part-based messages**
  - Risk: responses-mode requests could lose rich message intent when content is derived from parts.
  - Fixed in: `src/main/java/com/github/drafael/chat4j/provider/capability/chat/impl/OpenAiChatCompletionClient.java`
  - Verification: `OpenAiChatCompletionClientTest#toResponsesInputLine_whenMessageContainsParts_includesProjectedContent`

### Medium
- [x] **Repeated Copilot token-exchange overhead**
  - Risk: repeated network calls to `/copilot_internal/v2/token` in frequent model refresh flows.
  - Fixed in:
    - `src/main/java/com/github/drafael/chat4j/provider/core/ProviderFacade.java` (runtime uses resolver token directly)
    - `src/main/java/com/github/drafael/chat4j/provider/capability/models/impl/OpenAiModelCatalogClient.java` (exchange cache + failure backoff)
  - Verification: `OpenAiModelCatalogClientTest#fetchModels_whenCopilotUsesGithubOAuthToken_exchangesTokenAndReturnsModernModels`

- [x] **Copilot token file permission hardening**
  - Risk: local token persistence without explicit owner-only permissions.
  - Fixed in: `src/main/java/com/github/drafael/chat4j/provider/support/CopilotAuthResolver.java`
  - Verification: `CopilotAuthResolverTest#login_whenDeviceFlowCompletes_storesTokenAndTriggersPromptActions`

- [x] **Insufficient targeted tests for Copilot routing/diagnostics helpers**
  - Risk: regressions in fallback detection and diagnostics state updates.
  - Fixed in: `src/test/java/com/github/drafael/chat4j/provider/capability/chat/impl/OpenAiChatCompletionClientTest.java`

### Low
- [x] **README Copilot OAuth setup clarity gap**
  - Risk: first-run login failures when OAuth client ID configuration is missing.
  - Fixed in: `README.md` (added “GitHub Copilot OAuth setup” section).

---

## Phase A — Operational Hygiene

### Tasks
- [ ] Create a dedicated branch for remaining remediation work.
- [ ] Add a short changelog section to PR template for user-visible behavior changes.

### Acceptance Criteria
- Work proceeds on a dedicated branch.
- PRs include explicit user-facing change notes.

---

## Phase B — MainFrame Residual Complexity Reduction

### B.1 Composition root extraction
**Files:**
- `src/main/java/com/github/drafael/chat4j/MainFrame.java`
- (new) `src/main/java/com/github/drafael/chat4j/MainFrameDependencies.java` (or similar)

**Plan**
- [ ] Extract coordinator/service wiring from `MainFrame` constructor into a dedicated composition object/factory.
- [ ] Keep constructor behavior unchanged.
- [ ] Add focused unit tests for wiring defaults/fallbacks where practical.

**Acceptance Criteria**
- `MainFrame` constructor size and field-initialization density reduced.
- No behavior change in startup/runtime flow.

### B.2 State-holder extraction
**Files:**
- `src/main/java/com/github/drafael/chat4j/MainFrame.java`
- (new) `src/main/java/com/github/drafael/chat4j/MainFrameUiState.java` (or similar)

**Plan**
- [ ] Move mutable menu/selection/sidebar flags into a dedicated state holder.
- [ ] Keep existing update ordering and side-effects intact.
- [ ] Add tests around critical state transitions.

**Acceptance Criteria**
- `MainFrame` mutable field count reduced.
- State updates are easier to trace and test.

### B.3 Coordinator contract cleanup
**Files:**
- `src/main/java/com/github/drafael/chat4j/provider/support/ModelMenuStructureRebuildCoordinator.java`
- `src/main/java/com/github/drafael/chat4j/provider/support/ProviderMenuStructureRebuilder.java`

**Plan**
- [ ] Remove/repurpose unused return contracts in model-menu rebuild path.
- [ ] Align method signatures with actual usage.
- [ ] Update impacted tests.

**Acceptance Criteria**
- No unused return values in rebuild orchestration contracts.
- API intent is explicit and consistent.

---

## Phase C — UI Responsiveness / Shutdown Safety

### C.1 Non-blocking shutdown save path
**Files:**
- `src/main/java/com/github/drafael/chat4j/MainFrame.java`
- `src/main/java/com/github/drafael/chat4j/storage/CurrentConversationSaveDispatchCoordinator.java`
- (optional new coordinator) shutdown/quit flow helper

**Plan**
- [ ] Move expensive persistence work out of direct EDT quit/window-close handlers.
- [ ] Use bounded flush semantics (best effort + timeout + warning log), not unbounded blocking.
- [ ] Preserve data integrity expectations and current UX behavior.

**Acceptance Criteria**
- Close/quit flow does not freeze UI on slow storage.
- Save semantics remain reliable and observable.

---

## Phase D — Test Strategy Upgrades

### D.1 Integration flow tests (high-value paths)
**Plan**
- [ ] Add integration-style tests (or higher-level orchestration tests) for:
  - [ ] theme/font/model menu selection sync end-to-end
  - [ ] conversation save/load flow around chat switching
  - [ ] quit/window-closing save flow behavior
- [ ] Keep existing unit slices while reducing blind spots between coordinators.

### D.2 Ongoing verification gates
**Required per slice**
- [ ] `mvn -q test`
- [ ] targeted tests for changed behavior
- [ ] `mvn -q -DskipTests package`

---

## Delivery Order (recommended)

1. Phase B.3 (small contract cleanup)
2. Phase B.1 (constructor/composition extraction)
3. Phase B.2 (state-holder extraction)
4. Phase C.1 (shutdown save responsiveness hardening)
5. Phase D.1 (integration flow tests)

---

## Definition of Done

- [ ] `MainFrame` orchestration risk reduced to manageable complexity.
- [ ] No EDT-blocking shutdown hotspots in critical close/quit paths.
- [ ] High-value integration flows covered by tests.
- [ ] Documentation/PR hygiene in place for user-facing changes.
