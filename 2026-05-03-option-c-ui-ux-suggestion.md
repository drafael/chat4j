# Option C: IntelliJ-style AI Workspace for Chat4J

## Summary

Option C positions Chat4J as a native desktop AI workbench for developers rather than a generic chat clone.

Target feel:

> Claude/ChatGPT conversation comfort + IntelliJ project/tool-window ergonomics.

The chat remains central, while the UI emphasizes project context, agent mode, model/provider state, file-aware actions, and structured results.

---

## Current status

Large parts of the first implementation pass are complete. The remaining work is mostly deeper project modeling, richer structured agent metadata, and final visual QA.

---

## Done

### Phase 1: Quick visual wins

- [x] Added conversation workspace header in `ChatPanel`.
- [x] Header has title and subtitle/context line.
- [x] Header title updates from the first user message.
- [x] Long title/subtitle values are abbreviated with full-value tooltips.
- [x] Header subtitle updates for:
  - selected provider/model
  - Agent Mode/project
  - Web Search
  - reasoning level
  - generation state
- [x] Centered and max-width constrained chat message column.
- [x] Centered and max-width constrained composer.
- [x] Larger rounded composer.
- [x] Improved composer placeholder.
- [x] Compact model selector.
- [x] Useful empty state with greeting, explanatory subtitle, and suggestion chips.
- [x] Suggestion chips wrap on narrow windows.
- [x] Smart suggestion chip actions:
  - `Search the web` enables web search
  - `Review codebase` requests Agent Mode/project selection
  - `Draft commit message` requests Agent Mode/project selection
  - `Explain selected file` opens attachment picker
- [x] Active toolbar toggle styling for:
  - Web Search
  - reasoning
  - Agent Mode
- [x] Activity bubbles use accent color while streaming.
- [x] Tool/activity bubbles use status colors for success, failure, skipped, and running states.

### Phase 2: Workspace header

- [x] Moved model selector to the titlebar action area.
- [x] Added subtle centered `Chat4J` title in the native titlebar.
- [x] Conversation header is now the primary task context.
- [x] Added header overflow menu.
- [x] Header overflow contains:
  - Preview
  - Markdown
  - Search chats
  - Settings
  - Copy conversation
  - Copy recent response
  - Clear chat
- [x] Added compact `Project` pill to conversation header.
- [x] Project pill requests Agent Mode/project selection.
- [x] Project pill reflects active project state:
  - inactive: `Project`
  - active: project folder name
  - tooltip: full project path

### Phase 3: Project-aware sidebar

- [x] Added workspace actions to the sidebar:
  - New chat
  - Search
  - Projects
  - Settings
- [x] Added icons to sidebar actions.
- [x] Added `Filter conversations` field with search icon.
- [x] Local inline filtering by:
  - title
  - provider
  - model
  - project root
- [x] More spacious conversation rows.
- [x] Rounded selection styling.
- [x] Smoother sidebar scroll increment.
- [x] Dedicated Favorites section.
- [x] Fixed duplicate Favorites section bug.
- [x] Added project grouping:
  - conversations with `agentProjectRoot` render under `Project · <folder>`
  - project conversations are excluded from date groups
  - favorites take priority over project grouping

### Phase 4: Agent/code-review cards

- [x] Added `FindingCardPanel`.
- [x] Added `FindingCardParser`.
- [x] Finding cards support:
  - severity chip
  - title
  - body
  - file reference
  - Dismiss
  - Open file
  - Ask follow-up
  - Apply fix
- [x] Parser supports:
  - `P1`, `P2`, `P3`
  - `HIGH`, `MEDIUM`, `LOW`
  - bracketed forms like `[HIGH]`
  - numbered lists like `1. P1 ...`
  - file references with line ranges
- [x] Parser requires a `Findings` marker to avoid false positives.
- [x] Assistant finding output renders structured cards.
- [x] Finding-only responses render cards without duplicate raw markdown.
- [x] Findings group header includes count badge.
- [x] Finding actions:
  - Dismiss removes the card
  - Open file resolves relative paths against active Agent project root
  - Ask follow-up fills composer with title/file/context
  - Apply fix fills composer with title/file/context

### Bugs fixed during this pass

- [x] `Search the web` suggestion did not enable the web search toggle.
- [x] Duplicate `Favorites` sidebar section.
- [x] NPE when saving an empty new conversation: `conversationId is marked non-null but is null`.
- [x] Reasoning toggle selected background did not clear when unavailable.
- [x] Loaded conversation header subtitle did not refresh after runtime settings were applied.

---

## Todo

### Validation and visual QA

- [ ] Run full regression with `mvn test`.
- [ ] Manual QA in light and dark themes.
- [ ] Manual QA at narrow and wide window sizes.
- [ ] Verify empty state, sidebar filter, favorites/project grouping, finding cards, and active toggles.

### Workspace header polish

- [ ] Refine right-side titlebar action cluster.
- [ ] Improve narrow-width behavior for titlebar/header actions.
- [ ] Add optional branch/status pill for Agent Mode projects.
- [ ] Add export/share actions to overflow or titlebar.

### Sidebar/project model

- [ ] Add persistent project list independent of existing chats.
- [ ] Show project empty states.
- [ ] Add per-project “new chat” action.
- [ ] Add project context menu actions.
- [ ] Add compact/collapsed sidebar mode.
- [ ] Further distinguish project groups from date groups visually.

### Finding cards and agent cards

- [ ] Replace markdown parsing with structured metadata when provider/agent pipeline supports it.
- [ ] Open files at exact line number where possible.
- [ ] Persist dismissed finding state.
- [ ] Add collapsible findings group.
- [ ] Tune finding-card colors in dark mode.
- [ ] Implement dedicated tool-call cards separate from generic `ActivityBubble`.
- [ ] Add reusable file reference chips.
- [ ] Implement real apply-fix flow rather than only filling the composer.

---

## Recommended next task

Run full tests and do a manual visual QA pass before starting the deeper project model or structured metadata pipeline.
