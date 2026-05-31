# SwingWebView Integration

Chat4J includes SwingWebView as a configurable alternative to the Swing `JEditorPane` chat renderer. SwingWebView is the default on macOS. `JEditorPane` remains the default on other platforms and the fallback when SwingWebView is unavailable. SwingWebView is available in normal builds and can be changed from **Settings → Chat WebView**.

## Current Behavior

- `SwingWebView` is the default engine on macOS.
- `JEditorPane` is the default engine on non-macOS platforms.
- `SwingWebView` is packaged as a normal runtime dependency.
- Engine changes require restarting Chat4J.
- Startup resolves the active engine once per session.
- If SwingWebView is the default/configured engine but unavailable, Chat4J falls back to `JEditorPane`, keeps the configured/default engine visible in diagnostics, and shows a non-fatal warning.
- Production SwingWebView mode renders the whole transcript in one WebView. It does not embed one native WebView per message.

## Settings

The persisted setting is:

```text
chat4j.chat.webView.engine
```

Supported values:

- `jeditor-pane`
- `swing-webview`

The **Chat WebView** settings section includes:

- engine dropdown
- restart hint
- configured engine
- active engine for this session
- SwingWebView availability
- SwingWebView mode
- fallback reason, if any

## Key Implementation Types

- `ChatWebViewEngine` — supported engine values and display names.
- `ChatWebViewRuntimeStatus` — configured engine, active engine, availability, mode, and fallback reason.
- `ChatWebViewRuntimeStatusResolver` — startup resolution and fallback logic.
- `ChatWebViewPanel` — settings UI and diagnostics.
- `SwingWebViewTranscriptView` — production full-transcript WebView renderer.
- `SwingWebViewMessageContentView` — retained per-message implementation for spike/manual follow-up only.
- `ExternalLinkSupport` — shared safe-link filtering for `http`, `https`, and `mailto`.

## Rendering Architecture

The production SwingWebView path replaces only the chat transcript area. The composer, sidebar, model selector, and the rest of the application remain Swing.

`ChatPanel` still maintains Swing message views as the source model. In SwingWebView mode, it mirrors those views into one `SwingWebViewTranscriptView`. This avoids clipping and scrolling issues caused by per-message native WebView components inside a Swing scroll pane.

The transcript WebView owns:

- markdown/table/code rendering
- theme-aware transcript CSS
- collapsed activity sections
- safe external-link routing
- message hover actions
- context menu actions
- selected-text copy
- code-block copy buttons
- custom vertical scrollbar
- top/bottom fade overlays
- jump-to-bottom control when auto-scroll is disabled

## Streaming and Conversation Switching

SwingWebView transcript updates are session-scoped. Streaming callbacks update the visible transcript only when the callback belongs to the currently visible streaming session.

When conversation history is loaded or a visible stream completes, the transcript is fully refreshed to avoid stale DOM state. Conversation switches clear transient stream references before history is rebuilt.

## Packaging

SwingWebView is included in normal Maven builds:

```bash
mvn -q package
```

Standard jpackage builds should include SwingWebView support:

```bash
mvn -q -Pjpackage-mac -DskipTests verify
mvn -q -Pjpackage-win -DskipTests verify
mvn -q -Pjpackage-linux -DskipTests verify
```

Packaged-app validation should include:

1. Open the packaged app.
2. Go to **Settings → Chat WebView**.
3. Select `SwingWebView`.
4. Restart Chat4J.
5. Confirm diagnostics show active `SwingWebView` or a clear fallback reason.
6. Verify existing chats, streaming, markdown, tables, code blocks, long lines, external links, blocked unsafe links, scrolling, and shutdown.

For the macOS custom runtime image, current modules are:

```text
java.se,jdk.crypto.ec,jdk.unsupported
```

If SwingWebView reports missing runtime modules, test adding candidates such as `jdk.jsobject`.

## Verification

Run:

```bash
mvn -q test
mvn -q package -DskipTests
git diff --check
```

Targeted coverage includes:

- `ChatWebViewEngineTest`
- `ChatWebViewRuntimeStatusResolverTest`
- `ChatPanelTest` streaming/conversation-switching regressions

## Related Docs

- [configurable-web-view-engine.md](configurable-web-view-engine.md)
- [swingwebview-findings.md](swingwebview-findings.md)
