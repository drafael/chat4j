# Code Review Remediation Plan (Remaining Work)

Date: 2026-04-19
Scope: next work after Phase 1–4 hardening and refactor slices completed through Slice 138.

> Completed items were intentionally removed from this file to keep it focused on active work only.

## Goals (remaining)
1. Finish residual maintainability risk in `MainFrame` and related orchestration.
2. Close remaining UI responsiveness risk on shutdown/save paths.
3. Strengthen regression confidence with higher-level integration tests.
4. Keep incremental, reviewable slices with strict verification gates.

---

## Phase A — Operational Hygiene

### Tasks
- [ ] Create a dedicated branch for remaining remediation work (post-slice-127 baseline).
- [ ] Add a short changelog section to PR template for user-visible behavior changes.

### Acceptance Criteria
- Work proceeds on dedicated branch.
- PRs include explicit user-facing change notes.

---

## Phase B — MainFrame Residual Complexity Reduction

### B.1 Composition root extraction
**Files:**
- `src/main/java/com/github/drafael/chat4j/MainFrame.java`
- (new) `src/main/java/com/github/drafael/chat4j/MainFrameDependencies.java` (or similar)

**Plan**
- [ ] Extract coordinator/service wiring from `MainFrame` constructor into a dedicated composition object/factory.
- [x] Slice 129: extract provider-menu coordinator wiring from `MainFrame` constructor into `MainFrameProviderMenuWiringFactory` with dedicated unit tests.
- [x] Slice 130: extract settings/render/font/theme coordinator wiring from `MainFrame` constructor into `MainFrameSettingsWiringFactory` with dedicated unit tests.
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
- [x] Slice 131: extract model-menu dirty/selection mutable state from `MainFrame` into `MainFrameModelMenuState` with dedicated unit tests.
- [x] Slice 132: extract themes-menu built/selection mutable state from `MainFrame` into `MainFrameThemeMenuState` with dedicated unit tests.
- [x] Slice 133: extract font-menu built/selection mutable state from `MainFrame` into `MainFrameFontMenuState` with dedicated unit tests.
- [x] Slice 134: extract sidebar visibility/divider mutable state from `MainFrame` into `MainFrameSidebarState` with dedicated unit tests.
- [x] Slice 135: extract preview-toggle mutable state from `MainFrame` into `MainFramePreviewMenuState` with dedicated unit tests.
- [x] Slice 136: extract bound models/themes/font menu references from `MainFrame` into `MainFrameBoundMenusState` with dedicated unit tests.
- [x] Slice 137: extract top menu-bar/file/view references from `MainFrame` into `MainFrameTopMenusState` with dedicated unit tests.
- [x] Slice 138: extract current-conversation and pending-render mutable state from `MainFrame` into `MainFrameConversationState` with dedicated unit tests.
- [ ] Keep existing update ordering and side-effects intact.
- [ ] Add tests around critical state transitions.

**Acceptance Criteria**
- `MainFrame` mutable field count reduced.
- State updates become easier to trace/test.

### B.3 Coordinator contract cleanup
**Files:**
- `src/main/java/com/github/drafael/chat4j/provider/support/ModelMenuStructureRebuildCoordinator.java`
- `src/main/java/com/github/drafael/chat4j/provider/support/ProviderMenuStructureRebuilder.java`

**Plan**
- [x] Remove/repurpose unused boolean return contract in model-menu rebuild path.
- [x] Align method signatures with actual usage.
- [x] Update impacted tests.

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
- [ ] Add integration-style tests (or higher-level unit orchestration tests) for:
  - [ ] theme/font/model menu selection sync end-to-end
  - [ ] conversation save/load flow around chat switching
  - [ ] quit/window-closing save flow behavior
- [ ] Keep existing unit slices, but reduce blind spots between coordinators.

### D.2 Ongoing verification gates
**Required per slice**
- [ ] `mvn -q test`
- [ ] targeted new tests for changed behavior
- [ ] `mvn -q -DskipTests package`

---

## Delivery Order (recommended next slices)
1. Phase B.3 (small contract cleanup)
2. Phase B.1 (constructor/composition extraction)
3. Phase B.2 (state-holder extraction)
4. Phase C.1 (shutdown save responsiveness hardening)
5. Phase D.1 (integration flow tests)

---

## Definition of Done (remaining)
- [ ] `MainFrame` orchestration risk reduced to manageable complexity.
- [ ] No EDT-blocking shutdown hotspots in critical close/quit paths.
- [ ] High-value integration flows covered by tests.
- [ ] Documentation/PR hygiene in place for user-facing changes.
