# Code Review Remediation Plan

Date: 2026-04-19
Scope: Address high/medium-risk issues found in the code review before broad refactors.

## Goals
1. Prevent assistant/user message persistence gaps (data integrity).
2. Improve local data security posture.
3. Reduce UI stalls from blocking DB calls.
4. Keep changes incremental, test-backed, and easy to review.

---

## Phase 0 — Safety Baseline (small prep)

### Tasks
- [ ] Create a dedicated branch for remediation work.
- [x] Capture baseline green build (`mvn test`, `mvn -DskipTests package`).
- [ ] Add a short changelog section in PR description template for user-facing behavior changes.

### Acceptance Criteria
- Baseline tests and package pass before any functional changes.

---

## Phase 1 — Critical Data Integrity Fixes (highest priority)

### 1.1 Assistant persistence fallback path
**Files:**
- `src/main/java/com/github/drafael/chat4j/chat/ChatPanel.java`
- `src/main/java/com/github/drafael/chat4j/MainFrame.java`

**Plan**
- [x] Update assistant persistence flow so DB listener failures do not silently drop messages.
- [x] If listener persistence fails, fallback to `notifyMessageSubmitted()` path.
- [x] Replace silent catch blocks with minimal logging + user-safe handling.

**Acceptance Criteria**
- Simulated DB failure during assistant save does not lose message state permanently.
- No silent failures in this path.

---

### 1.2 Transactional message + attachment writes
**File:**
- `src/main/java/com/github/drafael/chat4j/storage/ConversationRepo.java`

**Plan**
- [x] Wrap `addMessage(...)` flow in explicit transaction:
  - insert message
  - persist attachment links
  - update conversation timestamp
- [x] Ensure rollback on any step failure.
- [x] Keep existing behavior for successful writes.

**Acceptance Criteria**
- No partial DB writes when any step fails.
- Existing tests remain green; add targeted tests for rollback behavior.

---

## Phase 2 — Security Hardening

### 2.1 H2 local DB configuration hardening
**Files:**
- `src/main/java/com/github/drafael/chat4j/storage/StoragePaths.java`
- `src/main/java/com/github/drafael/chat4j/storage/H2DataSourceFactory.java`
- (possibly) `src/main/java/com/github/drafael/chat4j/bootstrap/ApplicationBootstrap.java`

**Plan**
- [x] Re-evaluate and likely remove `AUTO_SERVER=TRUE` unless strictly required.
- [x] Stop defaulting to `sa` with blank password for local DB.
- [x] Introduce a generated per-install credential (stored in app config) if needed.
- [x] Document migration/compatibility behavior.

**Acceptance Criteria**
- DB still boots/migrates cleanly.
- Local DB security posture improved without breaking startup.

---

### 2.2 Reduce API key exposure in capability probing
**File:**
- `src/main/java/com/github/drafael/chat4j/provider/support/ProviderCapabilityResolver.java`

**Plan**
- [x] Prefer header-based auth for Google model probing.
- [x] Avoid query-string API keys unless endpoint strictly requires it.
- [x] Ensure no auth-bearing URL logging.

**Acceptance Criteria**
- Capability probing still works for supported providers.
- API keys are not included in request URLs where avoidable.

---

## Phase 3 — UX/Performance Improvements

### 3.1 Move blocking sidebar refresh off EDT
**File:**
- `src/main/java/com/github/drafael/chat4j/sidebar/SidebarPanel.java`

**Plan**
- [x] Run `findAllGroupedByDate()` in background worker.
- [x] Apply list model updates on EDT only.
- [x] Keep selection/hover behavior stable.

### 3.2 Reduce blocking save hotspots
**File:**
- `src/main/java/com/github/drafael/chat4j/MainFrame.java`

**Plan**
- [x] Move expensive save/load operations to background where safe.
- [x] Keep current UX behavior (no race-related duplicate writes).

**Acceptance Criteria (Phase 3)**
- No visible UI freeze during common save/refresh actions with larger histories.

---

## Phase 4 — Maintainability Refactor (after stability)

### Focus areas
- `ChatPanel.java`
- `MainFrame.java`
- `InputBar.java`
- `ProvidersPanel.java`
- `ProviderCapabilityResolver.java`

### Plan
- [ ] Split by concern (UI rendering, state, async tasks, persistence orchestration).
- [ ] Preserve external behavior and public contracts.
- [ ] Refactor in small PR slices with tests added alongside extraction.
- [x] Slice 1: extract persisted message-count tracking from `MainFrame` into `PersistedMessageCounter` with dedicated unit tests.
- [x] Slice 2: extract async conversation loading orchestration from `MainFrame` into `ConversationLoadCoordinator` with dedicated unit tests.
- [x] Slice 3: extract conversation history/message persistence orchestration from `MainFrame` into `ConversationPersistenceCoordinator` with dedicated unit tests.
- [x] Slice 4: extract `MainFrame` keyboard shortcut wiring into `MainFrameShortcutBinder` with dedicated unit tests.
- [x] Slice 5: extract provider runtime settings resolution from `MainFrame` into `ProviderRuntimeSettingsResolver` with dedicated unit tests.
- [x] Slice 6: extract settings dialog single-instance lifecycle handling from `MainFrame` into `SettingsDialogCoordinator` with dedicated unit tests.
- [x] Slice 7: extract window state persistence/restore settings logic from `MainFrame` into `WindowStateSettingsCoordinator` with dedicated unit tests.
- [x] Slice 8: extract assistant render-mode settings persistence/resolution from `MainFrame` into `AssistantRenderModeSettingsCoordinator` with dedicated unit tests.
- [x] Slice 9: extract shared model key encode/decode logic from `MainFrame`/`ChatPanel` into `ModelSelectionCodec` with dedicated unit tests.
- [x] Slice 10: extract chat search popup toggle lifecycle from `MainFrame` into `ChatSearchPopupCoordinator` with dedicated unit tests.
- [x] Slice 11: extract conversation title derivation logic from `MainFrame` into `ConversationTitleDeriver` with dedicated unit tests.
- [x] Slice 12: extract local provider availability resolution from `MainFrame` into `ProviderAvailabilityResolver` with dedicated unit tests.
- [x] Slice 13: extract general settings resolution (send key, auto-scroll, default render mode, menu bar) from `MainFrame` into `GeneralSettingsResolver` with dedicated unit tests.
- [x] Slice 14: extract font settings resolution/normalization from `MainFrame` into `FontSettingsResolver` with dedicated unit tests.
- [x] Slice 15: extract theme settings read/persist logic from `MainFrame` into `ThemeSettingsResolver` with dedicated unit tests.
- [x] Slice 16: extract provider menu icon rendering/caching from `MainFrame` into `ProviderMenuIconRenderer` with dedicated unit tests.
- [x] Slice 17: extract app font size step-up/step-down selection logic from `MainFrame` into `AppFontSizeStepResolver` with dedicated unit tests.
- [x] Slice 18: extract title bar icon/button creation helpers from `MainFrame` into `TitleBarUiSupport` with dedicated unit tests.
- [x] Slice 19: extract provider menu icon tint resolution from `MainFrame` into `ProviderMenuIconTintResolver` with dedicated unit tests.
- [x] Slice 20: extract model menu selection synchronization from `MainFrame` into `ModelMenuSelectionSynchronizer` with dedicated unit tests.
- [x] Slice 21: extract theme menu selection synchronization from `MainFrame` into `ThemeMenuSelectionSynchronizer` with dedicated unit tests.
- [x] Slice 22: extract font menu selection synchronization from `MainFrame` into `FontMenuSelectionSynchronizer` with dedicated unit tests.
- [x] Slice 23: extract sidebar visibility toggle state transitions from `MainFrame` into `SidebarVisibilityCoordinator` with dedicated unit tests.
- [x] Slice 24: extract assistant render-mode precedence selection from `MainFrame` into `AssistantRenderModeSelectionResolver` with dedicated unit tests.
- [x] Slice 25: extract font selection normalization (UI/code family fallback + size normalization) from `MainFrame` into `FontSelectionNormalizer` with dedicated unit tests.
- [x] Slice 26: extract assistant render-mode change decision planning from `MainFrame` into `AssistantRenderModeChangePlanner` with dedicated unit tests.
- [x] Slice 27: extract font settings persistence writes from `MainFrame` into `FontSettingsPersister` with dedicated unit tests.
- [x] Slice 28: extract font preview application calls from `MainFrame` into `FontPreviewApplier` with dedicated unit tests.
- [x] Slice 29: extract whole-window UI refresh operation from `MainFrame` into `WindowUiRefreshSupport` with dedicated unit tests.
- [x] Slice 30: extract provider menu availability state application from `MainFrame` into `ProviderMenuAvailabilityApplier` with dedicated unit tests.
- [x] Slice 31: extract provider availability label formatting from `MainFrame` into `ProviderAvailabilityLabelFormatter` and reuse it across menu rendering paths with dedicated unit tests.
- [x] Slice 32: extract provider menu icon resolution (size+tint composition) from `MainFrame` into `ProviderMenuIconResolver` and reuse it across rebuild/refresh paths with dedicated unit tests.
- [x] Slice 33: extract provider header menu item creation/update styling from `MainFrame` into `ProviderHeaderMenuItemFactory` with dedicated unit tests.
- [x] Slice 34: extract provider model list resolution (cache-vs-seed + sanitization) from `MainFrame` into `ProviderModelsResolver` with dedicated unit tests.
- [x] Slice 35: extract provider favorites resolution/filtering from `MainFrame` into `ProviderFavoritesResolver` with dedicated unit tests.
- [x] Slice 36: extract provider selectable map resolution from `MainFrame` into `ProviderSelectableResolver` with dedicated unit tests.
- [x] Slice 37: extract popup-visibility guarded menu refresh trigger from `MainFrame` into `MenuPopupVisibleRunner` and reuse it across model/font refresh call sites with dedicated unit tests.
- [x] Slice 38: extract help-menu visibility policy decision from `MainFrame` into `HelpMenuVisibilityResolver` with dedicated unit tests.
- [x] Slice 39: extract disabled menu section header creation/addition from `MainFrame` into `MenuSectionHeaderFactory` and reuse across model/theme/font menus with dedicated unit tests.
- [x] Slice 40: extract help menu construction/action wiring from `MainFrame` into `HelpMenuFactory` with dedicated unit tests.
- [x] Slice 41: extract provider menu empty-state item creation from `MainFrame` into `ProviderMenuEmptyStateFactory` with dedicated unit tests.
- [x] Slice 42: extract provider model menu item creation/wiring from `MainFrame` into `ProviderModelMenuItemFactory` with dedicated unit tests.
- [x] Slice 43: extract provider menu availability refresh orchestration from `MainFrame` into `ProviderMenuAvailabilityRefreshCoordinator` with dedicated unit tests.
- [x] Slice 44: extract provider menu data composition (models + selectable flags + favorites) from `MainFrame` into `ProviderMenuDataResolver` with dedicated unit tests.
- [x] Slice 45: extract favorites-section menu appending logic from `MainFrame` into `ProviderFavoritesSectionAppender` with dedicated unit tests.
- [x] Slice 46: extract provider catalog-section menu appending loop from `MainFrame` into `ProviderCatalogSectionAppender` with dedicated unit tests.
- [x] Slice 47: extract model-menu rebuild orchestration from `MainFrame` into `ProviderMenuStructureRebuilder` with dedicated unit tests.
- [x] Slice 48: extract themes-menu rebuild orchestration from `MainFrame` into `ThemeMenuStructureRebuilder` with dedicated unit tests.
- [x] Slice 49: extract font-menu rebuild orchestration from `MainFrame` into `FontMenuStructureRebuilder` with dedicated unit tests.
- [x] Slice 50: extract theme-application side-effect orchestration from `MainFrame` into `ThemeMenuApplyCoordinator` with dedicated unit tests.
- [x] Slice 51: extract font-application side-effect orchestration from `MainFrame` into `FontMenuApplyCoordinator` with dedicated unit tests.
- [x] Slice 52: extract font-menu selection refresh orchestration from `MainFrame` into `FontMenuSelectionRefreshCoordinator` with dedicated unit tests.
- [x] Slice 53: extract themes-menu selection refresh orchestration from `MainFrame` into `ThemeMenuSelectionRefreshCoordinator` with dedicated unit tests.
- [x] Slice 54: extract themes-menu readiness orchestration from `MainFrame` into `ThemeMenuReadyCoordinator` with dedicated unit tests.
- [x] Slice 55: extract font-menu readiness orchestration from `MainFrame` into `FontMenuReadyCoordinator` with dedicated unit tests.
- [x] Slice 56: extract model-menu readiness orchestration from `MainFrame` into `ProviderMenuReadyCoordinator` with dedicated unit tests.
- [x] Slice 57: extract menu-bar apply flow orchestration from `MainFrame` into `MenuBarSettingCoordinator` with dedicated unit tests.
- [x] Slice 58: extract provider-settings apply orchestration from `MainFrame` into `ProviderSettingsApplyCoordinator` with dedicated unit tests.
- [x] Slice 59: extract general-settings apply orchestration from `MainFrame` into `GeneralSettingsApplyCoordinator` with dedicated unit tests.
- [x] Slice 60: extract conversation-load result planning/guarding from `MainFrame` into `ConversationLoadResultPlanner` with dedicated unit tests.
- [x] Slice 61: extract assistant-message completion persistence/selection decision from `MainFrame` into `AssistantMessageCompletionCoordinator` with dedicated unit tests.
- [x] Slice 62: extract loaded-conversation apply execution from `MainFrame` into `ConversationLoadApplyCoordinator` with dedicated unit tests.
- [x] Slice 63: extract current-conversation save orchestration from `MainFrame` into `CurrentConversationSaveCoordinator` with dedicated unit tests.
- [x] Slice 64: extract common menu-selection listener binding from `MainFrame` into `MenuSelectionListenerBinder` and reuse across view/theme/font/model menus with dedicated unit tests.
- [x] Slice 65: extract file-menu construction from `MainFrame` into `FileMenuFactory` with dedicated unit tests.
- [x] Slice 66: extract view-menu construction from `MainFrame` into `ViewMenuFactory` with dedicated unit tests.
- [x] Slice 67: extract bound menu-shell creation from `MainFrame` into `BoundMenuFactory` and reuse for theme/font/model menus with dedicated unit tests.
- [x] Slice 68: extract top-level menu-bar assembly from `MainFrame` into `MenuBarAssemblyFactory` with dedicated unit tests.
- [x] Slice 69: extract look-and-feel change menu-refresh orchestration from `MainFrame` into `LookAndFeelMenuRefreshCoordinator` with dedicated unit tests.
- [x] Slice 70: extract assistant render-mode change apply orchestration from `MainFrame` into `AssistantRenderModeChangeCoordinator` with dedicated unit tests.
- [x] Slice 71: extract full menu-bar build composition from `MainFrame` into `MainMenuBarBuilder` with dedicated unit tests.
- [x] Slice 72: extract menu-bar ensure/build-once orchestration from `MainFrame` into `MainMenuBarEnsureCoordinator` with dedicated unit tests.
- [x] Slice 73: extract preview-toggle selection mapping/guard logic from `MainFrame` into `AssistantRenderModeToggleCoordinator` with dedicated unit tests.
- [x] Slice 74: extract model-menu dirty+visible-refresh orchestration from `MainFrame` into `ModelMenuDirtyRefreshCoordinator` with dedicated unit tests.
- [x] Slice 75: extract settings-open EDT dispatch orchestration from `MainFrame` into `SettingsOpenDispatchCoordinator` with dedicated unit tests.
- [x] Slice 76: extract assistant-message completion flow orchestration (persist + sidebar refresh/select) from `MainFrame` into `AssistantMessageCompletionFlowCoordinator` with dedicated unit tests.
- [x] Slice 77: extract window-state restore orchestration from `MainFrame` into `WindowStateRestoreCoordinator` with dedicated unit tests.
- [x] Slice 78: extract conversation-load callback EDT dispatch orchestration from `MainFrame` into `ConversationLoadDispatchCoordinator` with dedicated unit tests.
- [x] Slice 79: extract conversation-load start state+dispatch orchestration from `MainFrame` into `ConversationLoadStartCoordinator` with dedicated unit tests.
- [x] Slice 80: extract general-settings UI apply execution from `MainFrame` into `GeneralSettingsUiApplyCoordinator` with dedicated unit tests.
- [x] Slice 81: extract current-conversation save UI/state apply execution from `MainFrame` into `CurrentConversationSaveUiApplyCoordinator` with dedicated unit tests.
- [x] Slice 82: extract conversation-load failure handling/presentation orchestration from `MainFrame` into `ConversationLoadFailureCoordinator` with dedicated unit tests.
- [x] Slice 83: extract theme-menu apply dispatch/error-presentation orchestration from `MainFrame` into `ThemeMenuApplyDispatchCoordinator` with dedicated unit tests.
- [x] Slice 84: extract font-menu apply dispatch/error-presentation orchestration from `MainFrame` into `FontMenuApplyDispatchCoordinator` with dedicated unit tests.
- [x] Slice 85: extract assistant-render-mode change UI apply execution from `MainFrame` into `AssistantRenderModeChangeUiApplyCoordinator` with dedicated unit tests.
- [x] Slice 86: extract sidebar-toggle UI apply execution from `MainFrame` into `SidebarToggleApplyCoordinator` with dedicated unit tests.
- [x] Slice 87: extract new-chat reset/apply orchestration from `MainFrame` into `NewChatCoordinator` with dedicated unit tests.
- [x] Slice 88: extract created-menu-bar field/flag apply orchestration from `MainFrame` into `MainMenuBarCreatedApplyCoordinator` with dedicated unit tests.
- [x] Slice 89: extract model-menu rebuild state orchestration from `MainFrame` into `ModelMenuStructureRebuildCoordinator` with dedicated unit tests.
- [x] Slice 90: extract theme-menu rebuild state orchestration from `MainFrame` into `ThemeMenuStructureRebuildCoordinator` with dedicated unit tests.
- [x] Slice 91: extract font-menu rebuild state orchestration from `MainFrame` into `FontMenuStructureRebuildCoordinator` with dedicated unit tests.
- [x] Slice 92: extract assistant-message completion dispatch/failure handling orchestration from `MainFrame` into `AssistantMessageCompletionDispatchCoordinator` with dedicated unit tests.
- [x] Slice 93: extract loaded-conversation plan+apply dispatch orchestration from `MainFrame` into `ConversationLoadApplyDispatchCoordinator` with dedicated unit tests.
- [x] Slice 94: extract sidebar toggle transition+UI apply dispatch orchestration from `MainFrame` into `SidebarToggleCoordinator` with dedicated unit tests.
- [x] Slice 95: extract menu-bar ensure/create/apply dispatch orchestration from `MainFrame` into `MainMenuBarEnsureDispatchCoordinator` with dedicated unit tests.
- [x] Slice 96: extract current-conversation save dispatch/error-handling orchestration from `MainFrame` into `CurrentConversationSaveDispatchCoordinator` with dedicated unit tests.
- [x] Slice 97: extract font-menu selection refresh dispatch orchestration from `MainFrame` into `FontMenuSelectionDispatchCoordinator` with dedicated unit tests.
- [x] Slice 98: extract theme-menu selection refresh dispatch orchestration from `MainFrame` into `ThemeMenuSelectionDispatchCoordinator` with dedicated unit tests.
- [x] Slice 99: extract model-menu selection sync dispatch orchestration from `MainFrame` into `ModelMenuSelectionDispatchCoordinator` with dedicated unit tests.
- [x] Slice 100: extract preview-toggle menu selection sync guard/apply orchestration from `MainFrame` into `AssistantRenderModeToggleSelectionSyncCoordinator` with dedicated unit tests.
- [x] Slice 101: extract general-settings apply+UI dispatch orchestration from `MainFrame` into `GeneralSettingsApplyDispatchCoordinator` with dedicated unit tests.
- [x] Slice 102: extract menu-bar ensure-result state apply mapping from `MainFrame` into `MainMenuBarEnsureResultApplyCoordinator` with dedicated unit tests.
- [x] Slice 103: extract menu-bar apply-state field assignment dispatch from `MainFrame` into `MainMenuBarApplyStateCoordinator` with dedicated unit tests.
- [x] Slice 104: extract settings-open flow dispatch/dialog orchestration from `MainFrame` into `SettingsOpenFlowCoordinator` with dedicated unit tests.
- [x] Slice 105: extract assistant-message completion event-to-dispatch orchestration from `MainFrame` into `AssistantMessageCompletionEventDispatchCoordinator` with dedicated unit tests.
- [x] Slice 106: extract menu-bar setting dispatch action assembly from `MainFrame` into `MenuBarSettingDispatchCoordinator` with dedicated unit tests.
- [x] Slice 107: extract menu-bar ensure+current-state resolution orchestration from `MainFrame` into `MainMenuBarEnsureStateResolver` with dedicated unit tests.
- [x] Slice 108: extract provider-availability menu refresh dispatch (provider supply + coordinator call) from `MainFrame` into `ProviderMenuAvailabilityRefreshDispatchCoordinator` with dedicated unit tests.
- [x] Slice 109: extract assistant-render-mode change dispatch (decision apply + UI apply) from `MainFrame` into `AssistantRenderModeChangeDispatchCoordinator` with dedicated unit tests.
- [x] Slice 110: extract model-selected change guard/sync dispatch from `MainFrame` into `ModelMenuSelectionChangeCoordinator` with dedicated unit tests.
- [x] Slice 111: extract model-menu dirty-refresh trigger dispatch from `MainFrame` into `ModelMenuDirtyRefreshTriggerCoordinator` with dedicated unit tests.
- [x] Slice 112: extract app-font size adjust dispatch (resolve + apply) from `MainFrame` into `AppFontSizeAdjustCoordinator` with dedicated unit tests.
- [x] Slice 113: extract menu-bar create dispatch (shortcut mask resolution + builder call) from `MainFrame` into `MainMenuBarCreateDispatchCoordinator` with dedicated unit tests.
- [x] Slice 114: extract models-menu ready dispatch forwarding from `MainFrame` into `ProviderMenuReadyDispatchCoordinator` with dedicated unit tests.
- [x] Slice 115: extract themes-menu ready dispatch forwarding from `MainFrame` into `ThemeMenuReadyDispatchCoordinator` with dedicated unit tests.
- [x] Slice 116: extract font-menu ready dispatch forwarding from `MainFrame` into `FontMenuReadyDispatchCoordinator` with dedicated unit tests.
- [x] Slice 117: extract theme-menu selection state apply dispatch from `MainFrame` into `ThemeMenuSelectionApplyCoordinator` with dedicated unit tests.
- [x] Slice 118: extract font-menu selection state apply dispatch from `MainFrame` into `FontMenuSelectionApplyCoordinator` with dedicated unit tests.
- [x] Slice 119: extract menu-bar ensure resolve+apply flow orchestration from `MainFrame` into `MainMenuBarEnsureApplyFlowCoordinator` with dedicated unit tests.

### Acceptance Criteria
- Reduced class size/complexity.
- No regressions in existing test suite.

---

## Testing Strategy

### Required for each phase
- [ ] `mvn test`
- [ ] targeted new tests for changed behavior
- [ ] `mvn -DskipTests package`

### New tests to add first
- [x] Assistant persistence failure fallback test.
- [x] Transaction rollback test for `ConversationRepo.addMessage`.
- [x] Capability probing auth-header behavior test.
- [x] Sidebar async refresh behavior test (no EDT blocking assumptions).

---

## Delivery Order (recommended PR slices)
1. **PR-1:** Phase 1.1 (assistant persistence fallback + non-silent errors)
2. **PR-2:** Phase 1.2 (transactional message writes + tests)
3. **PR-3:** Phase 2.2 (API key exposure reduction in probing)
4. **PR-4:** Phase 2.1 (H2 hardening + migration notes)
5. **PR-5:** Phase 3 (async UI/DB operations)
6. **PR-6+:** Phase 4 refactor slices

---

## Definition of Done
- All phases implemented or explicitly deferred with rationale.
- No silent data-loss paths in message persistence.
- Security posture improved for local storage + API key handling.
- Tests pass and coverage is improved in touched areas.
- Documentation updated for any behavioral/configuration changes.
