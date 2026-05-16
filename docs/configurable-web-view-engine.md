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

## JCEF Development Spike

A dev-only JCEF spike is available behind an opt-in Maven profile:

```bash
mvn -Pjcef-spike compile exec:exec
```

The first run may download and extract native JCEF files into:

```text
target/jcef-spike-bundle
```

The runner writes temporary HTML pages to:

```text
target/jcef-spike-pages
```

This spike is intentionally not production integration. It exists to validate JCEF startup, Swing embedding, Chat4J-rendered HTML, navigation policy, focus/shortcut behavior, direct resource loading, JavaScript-to-Java message routing, and browser lifecycle/disposal behavior before adding a configurable production engine. It defaults to native/windowed rendering with a resize workaround because OSR/windowless rendering hung during macOS testing. To compare OSR rendering, launch with `CHAT4J_JCEF_SPIKE_OSR=true`. To enable DevTools and remote debugging on port `9222`, launch with `CHAT4J_JCEF_SPIKE_DEVTOOLS=true`. The spike keeps logs in a separate window (`Show log`) because native/windowed JCEF is a heavyweight component and can overpaint Swing components below it during resize/move on macOS. The Static Smoke tab can load a temp-file page or an intercepted in-memory resource at `https://chat4j-spike.local/...`. The Chat HTML tab includes individual samples plus a run-all pass for user badges, escaping, assistant preview/dark/raw modes, rich markdown, long responses, link edge cases, and explicit navigation-policy coverage. User-gesture navigations are blocked in-place; safe external schemes are opened outside JCEF and unsafe schemes are logged/rejected. The Focus/Shortcuts tab supports manual focus, Tab traversal, Cmd/Ctrl copy/select checks, and a `CefMessageRouter` bridge query. The lifecycle tab reloads one browser by default; its explicit dispose/recreate mode is a native cleanup crash probe and may be unstable on macOS. Per-browser close follows IntelliJ's `stopLoad()`, `setCloseAllowed()`, `close(true)` sequence.

On macOS/JDK 16+, JCEF needs access to internal AWT packages when attaching browser components. The `exec:exec` spike command launches a forked JVM with the required `--add-exports`/`--add-opens` flags. Avoid running this spike with `exec:java`, because that runs inside Maven's JVM and may fail with `IllegalAccessError` unless equivalent flags are supplied through `MAVEN_OPTS`.

Some Chromium/CEF stderr output is expected during the spike, especially in unsigned local development launches. The spike passes flags to reduce background-networking noise, but native Chromium messages should be evaluated by whether rendering and lifecycle stress actually succeed.

On macOS with jcefmaven `143.0.14`, explicit `CefApp.dispose()` during local unsigned spike shutdown has trapped native CEF with exit code 133. The spike therefore skips explicit CEF disposal on macOS and abruptly halts the forked spike JVM with exit code 0 when the window closes. The Maven profile launches the spike through `src/jcef-spike/bin/run-jcef-spike.sh`, which owns Ctrl+C handling and kills the forked JVM without native CEF shutdown. Production integration must revisit shutdown semantics for signed/jpackaged builds.

## Future JCEF Follow-up

See [IntelliJ JCEF/JBCEF Findings](jcef-intellij-findings.md) for source-backed notes on IntelliJ's JCEF architecture, native/windowed rendering behavior, resize/move handling, link interception, JS bridging, macOS packaging details, and shutdown risks.

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
