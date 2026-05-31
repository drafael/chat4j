# Configurable Web View Engine Architecture

Chat4J supports configurable chat message rendering engines behind shared rendering boundaries. The default remains Swing `JEditorPane`. SwingWebView mode uses one full-transcript WebView for the chat scroll area rather than embedding one native WebView per message.

## Goals

- Keep `JEditorPane` as the safe default.
- Keep chat orchestration independent from concrete rendering components.
- Preserve shared message behavior: roles, render mode, context menu, selection/copy hooks, external-link policy, and disposal.
- Fall back to `JEditorPane` if `SwingWebView` is configured but unavailable.
- Avoid per-message native WebViews because they break clipping/scrolling inside existing chat history.

## User Setting

The persisted setting is:

```text
chat4j.chat.webView.engine
```

Supported values:

- `jeditor-pane` — default, Swing `JEditorPane` renderer.
- `swing-webview` — renders the whole transcript in one embedded native WebView while the composer/sidebar/model UI remain Swing.

Changes require restarting Chat4J. The active engine is resolved once during startup.

## Settings UI

The settings window has a separate **Chat WebView** section. It includes:

- Engine dropdown.
- Restart-required hint.
- Diagnostics:
  - configured engine
  - active engine for this session
  - SwingWebView availability
  - SwingWebView default mode
  - fallback reason, if any

## Runtime Resolution and Fallback

Startup uses `ChatWebViewRuntimeStatusResolver` to read the configured engine and check SwingWebView availability. If `swing-webview` is configured but unavailable, Chat4J uses `JEditorPane` for the current session, keeps the persisted setting unchanged, and shows a non-fatal warning.

`ChatMessageViewFactory` still creates `JEditorPaneMessageContentView` for individual Swing message views. SwingWebView mode does not use per-message WebViews; it mirrors those message views into a single transcript WebView.

## Package Layout

The message rendering boundary lives under:

```text
com.github.drafael.chat4j.chat.message
```

Key types:

- `ChatWebViewEngine` — setting/display model for supported engines.
- `ChatWebViewRuntimeStatus` — configured/active engine and diagnostics for this session.
- `ChatWebViewRuntimeStatusResolver` — startup resolver and fallback planner.
- `ChatMessageView` — engine-neutral message component contract used by chat UI code.
- `ChatMessageViewFactory` — creates message views using the active engine.
- `MessageBubble` — owns bubble chrome, role styling, text buffering, and render-mode state.
- `MessageContentView` — engine-specific rendering surface.
- `JEditorPaneMessageContentView` — default Swing implementation.
- `SwingWebViewMessageContentView` — retained per-message implementation for spike/manual follow-up.
- `SwingWebViewTranscriptView` — production SwingWebView mode implementation for the full transcript.
- `MessageHtmlRenderer` — converts message state to HTML.
- `ExternalLinkSupport` — shared external-link allowlist and opener.

## Data Flow

1. `MainFrame` resolves `ChatWebViewRuntimeStatus` at startup.
2. `MainFrame` creates `ChatPanel` with a `ChatMessageViewFactory` configured for the active engine.
3. `ChatPanel` asks the factory for message views.
4. `MessageBubble` receives text/render-mode/theme changes.
5. `MessageHtmlRenderer` converts state into HTML.
6. In `JEditorPane` mode, the selected `MessageContentView` displays each message in Swing.
7. In `SwingWebView` mode, ChatPanel keeps Swing message views as the source model and mirrors them into one `SwingWebViewTranscriptView`.

## SwingWebView Notes

`SwingWebViewMessageContentView` is retained for spike/manual follow-up. It loads assistant message HTML through a data URL and injects JavaScript for:

- external-link interception
- selection state tracking
- document-height reporting for Swing layout
- select-all/copy support

Selection and copy are best-effort because the WebView API is asynchronous. Core rendering and safe link handling are the priority.

## External Links

External link handling is centralized in `ExternalLinkSupport`. Allowed schemes are:

- `http`
- `https`
- `mailto`

Unsafe schemes such as `javascript:`, `file:`, and `data:` are rejected before opening links with the desktop browser. SwingWebView uses injected JavaScript to prevent in-WebView navigation and route link clicks through the same policy.

## jpackage

SwingWebView is a normal runtime dependency, so standard jpackage builds should include it through the shaded application jar:

```bash
mvn -q package
mvn -Pjpackage-mac verify
mvn -Pjpackage-win verify
mvn -Pjpackage-linux verify
```

Validation should include launching the packaged app, selecting `SwingWebView`, restarting, and confirming diagnostics show a fallback to `JEditorPane` without breaking existing chat rendering.

If SwingWebView native/resource loading fails from the shaded jar, switch the jpackage input layout to preserve dependency jars separately under `target/jpackage-input/lib` and configure the launcher classpath accordingly.

For the macOS custom runtime image, current modules are:

```text
java.se,jdk.crypto.ec,jdk.unsupported
```

If SwingWebView reports missing modules at runtime, test adding likely candidates such as `jdk.jsobject`.

## Verification

Run:

```bash
mvn -q test
mvn -q package
git diff --check
```

Manual checks:

1. Start with default `JEditorPane` and confirm normal chat rendering.
2. Open **Settings → Chat WebView** and select `SwingWebView`.
3. Restart Chat4J.
4. Confirm diagnostics show active `SwingWebView`.
5. Verify markdown preview, raw markdown mode, code blocks, long lines, external links, blocked unsafe links, streaming, existing chat loading, transcript scrolling, theme changes, and app shutdown.
