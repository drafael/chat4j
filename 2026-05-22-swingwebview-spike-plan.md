# SwingWebView Spike Plan

## Goal

Evaluate [`webliteca/swingwebview`](https://github.com/webliteca/swingwebview) as a lighter native WebView candidate for Chat4J message rendering, alongside the existing JCEF research. The spike must stay opt-in and must not affect normal startup, tests, or packaging.

## Scope

- Add an opt-in Maven profile named `swingwebview-spike`.
- Add a standalone dev runner launched with `mvn -Pswingwebview-spike compile exec:java`.
- Use `ca.weblite:webview:1.0.7` from Maven Central.
- Render static HTML and Chat4J-generated message HTML through SwingWebView.
- Exercise JavaScript callbacks, console capture, `evalAsync`, FlatLaf theme switching, focus/shortcut behavior, and repeated reload/dispose lifecycle paths.
- Document findings and integration risks for future `WebViewEngine` work.

## Non-Goals

- No production `WebViewEngine` setting yet.
- No production `SwingWebViewMessageContentView` implementation yet.
- No jpackage/native bundling integration yet.
- No CI requirement for native WebView execution.

## Design Decisions

### Opt-in profile

Normal builds remain unchanged. The SwingWebView dependency and spike source directory are active only when the profile is selected.

Target command:

```bash
mvn -Pswingwebview-spike compile exec:java
```

### Runner location

The dev runner lives outside normal production sources:

```text
src/swingwebview-spike/java/com/github/drafael/chat4j/chat/message/SwingWebViewSpikeFrame.java
```

It intentionally uses the `chat.message` package so it can reuse package-internal `MessageHtmlRenderer` without making renderer APIs public for this development-only experiment.

### Generated HTML

Generated pages are written under:

```text
target/swingwebview-spike-pages
```

The static smoke tab can load either from a file URL or a base64 data URL. Chat samples default to file URL loading so the generated HTML can be inspected.

### Popup policy

The runner enables heavyweight Swing popups at startup:

```java
JPopupMenu.setDefaultLightWeightPopupEnabled(false);
ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
```

This mirrors SwingWebView's own heavyweight guidance and avoids dropdown/tooltips being painted behind native WebView peers.

### Navigation policy

SwingWebView does not expose a JCEF-style request handler in the public Swing API. The spike injects a document-start JavaScript click interceptor that prevents default anchor navigation and calls a Java callback. The callback applies Chat4J-style allowlist semantics:

- allow `http`, `https`, and `mailto`
- block `javascript`, `file`, `data`, malformed URLs, and everything else
- do not open even safe links unless `CHAT4J_SWINGWEBVIEW_SPIKE_OPEN_LINKS=true`

Production integration should not rely on in-page JavaScript alone without accepting its limitations; see `docs/swingwebview-findings.md`.

## Runner Behavior

### Static Smoke tab

Renders a simple HTML page with:

- text and styles
- links
- code block
- console logging
- JavaScript-to-Java callback
- editable area for focus and shortcut checks

### Chat HTML tab

Uses Chat4J `MessageHtmlRenderer` samples:

- user message badges
- assistant preview Markdown
- raw Markdown mode with escaped source
- dark preview palette
- long wrapping stress

Each sample validates an expected HTML fragment before loading.

### Theme switcher

The header includes a FlatLaf theme switcher for manual compatibility checks. Switching themes updates the Swing UI and reloads already-loaded spike pages so Chat4J-generated HTML is regenerated with the active theme fonts and dark/light palette.

Included themes cover FlatLaf core, macOS, Nord, GitHub, and Solarized variants.

### JS / Focus tab

Exercises:

- Java callback from page script
- console capture
- safe and unsafe link callbacks
- `evalAsync(...)`
- editable text area for typing, copy/select-all, and focus checks
- Swing focus probe next to the browser

### Lifecycle Stress tab

Default mode reuses one WebView and reloads generated HTML pages. Optional recreate mode disposes/recreates the component every iteration as a native cleanup probe.

## Verification

Compile default app and spike source:

```bash
mvn -q test
mvn -q -Pswingwebview-spike compile
```

Run spike manually:

```bash
mvn -Pswingwebview-spike compile exec:java
```

Manual checks:

- Static Smoke renders from file URL.
- Static Smoke renders from data URL.
- Header theme switcher can change FlatLaf themes and loaded Chat HTML refreshes with the active theme.
- Chat HTML samples render and links are logged instead of navigating in-place.
- JS callback and console capture append to the log.
- `evalAsync` returns JSON after the page loads.
- Text input and Cmd/Ctrl+A/C work inside the WebView.
- Focus can move back to the Swing text field.
- Lifecycle Stress completes 10 default iterations.
- Dispose/recreate mode does not crash in local testing.
- Closing the spike window exits cleanly.

## Follow-Up After Spike

If SwingWebView proves stable enough:

1. Compare footprint, startup time, and packaging complexity against JCEF.
2. Decide whether the engine enum should include `SWING`, `JCEF`, and `SWING_WEBVIEW` or treat SwingWebView as the first native option.
3. Prototype `SwingWebViewMessageContentView` behind `MessageContentView`.
4. Keep a safe fallback to `JEditorPaneMessageContentView` if native loading or platform support fails.
5. Revisit link handling. Prefer a native navigation callback if SwingWebView adds one; otherwise document the tradeoff of injected JavaScript interception.
6. Validate jpackage on macOS, Windows, and Linux, including native library extraction and Windows WebView2 runtime presence.
