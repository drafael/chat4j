# Configurable WebView Engine Architecture

This page is a short architecture note. The canonical operational doc is [chat-rendering.md](chat-rendering.md).

## Engine boundary

Chat4J keeps chat orchestration Swing-first. `ChatPanel` owns the message model, render mode, streaming state, and conversation state. Browser engines are presentation layers only.

The production engines are:

- Swing HTML Renderer (`jeditor-pane`) — per-message Swing fallback.
- Native OS WebView (`native-webview`) — one full-transcript SwingWebView component.
- Chromium Embedded Framework (`jcef`) — one full-transcript JCEF browser.

Platform fallback chains are:

- macOS/Windows: `native-webview` → `jcef` → `jeditor-pane`
- Linux/other: `jcef` → `jeditor-pane`

## Key classes

- `ChatWebViewEngine` — persisted engine values.
- `ChatWebViewRuntimeStatusResolver` — default and fallback resolution.
- `ChatWebViewRuntimeStatus` — configured engine, active engine, availability flags, and fallback reason.
- `AppearancePanel` — engine selection and diagnostics.
- `ChatMessageView`, `MessageBubble`, `MessageContentView`, `JEditorPaneMessageContentView` — Swing source/fallback message view boundary.
- `SwingWebViewTranscriptView` — full-transcript Native OS WebView.
- `JcefRuntime`, `JcefTranscriptView` — JCEF runtime and full-transcript browser view.
- `ExternalLinkSupport` — central external-link policy.

## Data flow

1. `ChatPanel` creates Swing source message views.
2. Swing HTML Renderer displays those views directly.
3. Native OS WebView and JCEF mirror the same message data into a single transcript document.
4. Browser transcript views own browser-specific DOM, CSS, scrolling, actions, and navigation blocking.
5. Native browser resources are disposed when the chat panel is removed.

## JCEF implementation notes

JCEF support uses native/windowed rendering. `JcefRuntime` initializes CEF once, applies reduced-background-networking flags, and keeps DevTools behind `-Dchat4j.jcef.devtools=true`. `JcefTranscriptView` serves generated HTML from a controlled in-memory `https://chat4j.local/...` resource and blocks in-browser navigation.

Known risks remain native-platform issues, especially macOS packaged shutdown and native component behavior during resize/move. See [jcef-intellij-findings.md](jcef-intellij-findings.md) for the research notes that informed the implementation.

## Verification

Use the checks in [chat-rendering.md](chat-rendering.md#verification). Manual JCEF validation should cover startup, fallback, resize/move, links, copy/select-all, conversation switching, clearing chat, and packaged shutdown.
