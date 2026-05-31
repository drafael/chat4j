# SwingWebView Findings

Chat4J includes `ca.weblite:webview` for the optional **SwingWebView** chat transcript renderer.

## Summary

SwingWebView is a lighter embedded-browser option than JCEF because it uses platform WebView implementations. Spike results showed that per-message native WebViews break clipping/scrolling in existing chats, so production SwingWebView mode uses a single full-transcript WebView instead.

## Integration Status

- Dependency: `ca.weblite:webview`.
- Setting: `chat4j.chat.webView.engine`.
- Default engine: `jeditor-pane`.
- Optional engine: `swing-webview`.
- Scope: production SwingWebView mode replaces the transcript/scroll area with one WebView while keeping composer/sidebar/model UI in Swing.
- Settings UI: **Chat WebView**.
- Changes require restart.
- Startup fallback: if SwingWebView is unavailable, Chat4J uses `JEditorPane` and warns the user while keeping the configured value for diagnostics.

## API Notes

Useful API points:

- `WebViewComponent.create()` creates the platform-default component.
- `WebViewComponent.resolveDefaultMode()` reports the selected mode.
- `setUrl(...)` navigates to a URL; Chat4J uses a `data:text/html` URL for rendered messages.
- `addOnBeforeLoad(...)` injects JavaScript before documents load.
- `addJavascriptCallback(...)` exposes Java callbacks to JavaScript.
- `eval(...)` and `evalAsync(...)` support best-effort selection/copy behavior.
- `dispose()` must be called when message views are removed.

## Link Handling

The public API does not expose a native request/navigation handler. Chat4J injects click interception JavaScript for links and routes them through `ExternalLinkSupport`, preserving the allowlist:

- `http`
- `https`
- `mailto`

Blocked examples include `javascript:`, `file:`, and `data:`.

## Known Limitations

- Selection/copy is asynchronous and may not behave exactly like `JEditorPane` on every platform.
- Height is reported from JavaScript and then applied to Swing layout; very dynamic content may need additional refreshes.
- Native heavyweight WebViews are a poor fit for compact right-aligned user bubbles inside the scroll pane, so user messages intentionally keep the Swing renderer.
- Native WebView behavior may differ across macOS, Windows, and Linux.
- Per-message native WebViews break clipping/scrolling in existing chat selection; use one transcript WebView instead.
- Automated headless CI tests should avoid constructing native WebView components until stability is proven.

## Packaging Notes

The first supported packaging path is the existing shaded-jar jpackage flow. Validate with:

```bash
mvn -q package
mvn -Pjpackage-mac verify
```

Then launch the packaged app, select SwingWebView, restart, and verify existing chat rendering and scrolling in the full-transcript WebView. If native/resource lookup fails from the shaded jar during future work, preserve runtime dependencies as separate jars in the jpackage input directory instead of relying on shading.
