# Configurable Web View Engine Architecture

Chat4J prepares chat message rendering for multiple web-view engines through a message-view boundary. The current implementation still uses Swing `JEditorPane`, but `ChatPanel` and `ActivityBubble` now depend on engine-neutral interfaces rather than Swing text APIs.

## Goals

- Keep current chat rendering behavior unchanged.
- Isolate Swing `JEditorPane` behind a replaceable content-view implementation.
- Make a future JCEF renderer additive instead of invasive.
- Avoid exposing `JEditorPane`, Swing documents, or Swing copy/select actions to chat orchestration code.
- Ensure message views have lifecycle hooks so native/browser resources can be released later.

## Package Layout

The message rendering boundary lives under:

```text
com.github.drafael.chat4j.chat.message
```

Key types:

- `ChatMessageView` — engine-neutral message component contract used by chat UI code.
- `ChatMessageViewFactory` — creates message views. It currently always creates the Swing-backed implementation.
- `MessageBubble` — default `ChatMessageView` implementation that owns bubble chrome, role styling, text buffering, and render-mode state.
- `MessageContentView` — engine-specific rendering surface.
- `JEditorPaneMessageContentView` — current Swing implementation.
- `MessageHtmlRenderer` — converts message state to HTML.
- `ExternalLinkSupport` — shared external-link allowlist and opener.

## Responsibilities

### `ChatMessageView`

`ChatMessageView` is the component-level API that `ChatPanel` and `ActivityBubble` use. It exposes:

- the Swing component to add to layouts
- role and full source text
- append/set text operations
- assistant render-mode updates
- max content width updates
- context menu and key binding installation
- content selection/copy/focus actions
- rendered HTML/text snapshots for validation
- disposal state and cleanup

Chat orchestration code should use this interface instead of concrete Swing rendering details.

### `MessageBubble`

`MessageBubble` keeps message-level behavior:

- rounded user-bubble painting
- user/assistant borders
- source text buffering
- assistant render-mode state
- theme/font re-rendering on `updateUI()`
- max-width tracking for user messages

It delegates actual HTML display to `MessageContentView`.

### `MessageContentView`

`MessageContentView` is the engine seam. A content view owns the actual renderer component and implements operations such as:

- `setHtml(...)`
- `setContextMenu(...)`
- `installKeyBinding(...)`
- `selectAll()`
- `copySelection()`
- `requestContentFocus()`
- `htmlSnapshot()` / `textSnapshot()`
- `dispose()` / `isDisposed()`

The current implementation is `JEditorPaneMessageContentView`. A future `JcefMessageContentView` should implement the same contract.

## Data Flow

1. `ChatPanel` asks `ChatMessageViewFactory` for a view.
2. `ChatPanel` stores and manipulates the result as `ChatMessageView`.
3. `MessageBubble` receives text/render-mode changes.
4. `MessageHtmlRenderer` converts state into HTML.
5. `MessageContentView` displays the HTML using the selected engine.

For now, the selected engine is hardcoded to Swing `JEditorPane` in `ChatMessageViewFactory`.

## Lifecycle

`ChatMessageView.dispose()` is called when message views are removed or when chat history is cleared/reloaded. The Swing implementation currently marks itself disposed and detaches its popup menu. This hook is intentionally in place for future JCEF support, where native browser resources will require explicit cleanup.

## External Links

External link handling is centralized in `ExternalLinkSupport`. Allowed schemes are:

- `http`
- `https`
- `mailto`

Unsafe schemes such as `javascript:`, `file:`, and `data:` are rejected before opening links with the desktop browser.

## Future JCEF Follow-up

JCEF support should be added as a separate milestone:

1. Add a `WebViewEngine` enum, initially with `SWING` and `JCEF`.
2. Add a persisted setting such as `chat4j.chat.webView.engine`.
3. Add settings UI for engine selection.
4. Implement `JcefMessageContentView`.
5. Update `ChatMessageViewFactory` to select the configured engine.
6. Fall back to Swing if JCEF initialization fails.
7. Handle native binaries, jpackage integration, platform support, app shutdown, and disposal semantics.

## Verification

The prepare-only refactor was verified with:

```bash
mvn -q test
rg "getEditorPane\(|MessageBubble\.isAllowedExternalLink|createBubble\(" src/main/java src/test/java -S
```

The search should return no stale API usage.
