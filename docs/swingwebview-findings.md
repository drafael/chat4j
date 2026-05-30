# SwingWebView Findings

This note records research and spike implementation findings for [`webliteca/swingwebview`](https://github.com/webliteca/swingwebview) as a possible native message-rendering engine for Chat4J. It is not a production design; it is evidence for the future configurable web-view engine milestone.

Research commit: [`39af1688fdc9acffea264b1155cc1602b7b65453`](https://github.com/webliteca/swingwebview/tree/39af1688fdc9acffea264b1155cc1602b7b65453)

## Executive Summary

SwingWebView is attractive as a lighter alternative to JCEF because it exposes a Swing `JComponent` API, uses system web engines, and ships native libraries in the Maven artifact. Its API covers the essentials Chat4J would need for rendered messages: URL loading, JavaScript callbacks, console capture, `evalAsync`, dialog suppression, context-menu hooks, and explicit disposal.

The biggest production caveat is navigation control. Unlike the JCEF spike, the public Swing API does not expose a native request/navigation handler. The spike therefore injects JavaScript to intercept link clicks and route them through Java. That can work for Chat4J-generated message HTML, but it is not as strong as a browser-level request handler.

Recommended posture: keep SwingWebView as a promising candidate, but do not replace the current Swing `JEditorPane` renderer until the spike is manually exercised on macOS, Windows, and Linux and link/navigation control is judged acceptable.

## Library Shape

The published dependency is simple:

```xml
<dependency>
    <groupId>ca.weblite</groupId>
    <artifactId>webview</artifactId>
    <version>1.0.7</version>
</dependency>
```

The README documents this coordinate and version directly ([`README.md`](https://github.com/webliteca/swingwebview/blob/39af1688fdc9acffea264b1155cc1602b7b65453/README.md#L8-L18)). The source project declares MIT licensing ([`pom.xml`](https://github.com/webliteca/swingwebview/blob/39af1688fdc9acffea264b1155cc1602b7b65453/pom.xml#L17-L22)).

The jar is intended to bundle native libraries for macOS, Linux, and Windows. Windows still depends on the system Microsoft Edge WebView2 Runtime ([`README.md`](https://github.com/webliteca/swingwebview/blob/39af1688fdc9acffea264b1155cc1602b7b65453/README.md#L20-L26)).

## Platform Model

SwingWebView has two component modes:

- `HEAVYWEIGHT`: native WebView embedded as an AWT heavyweight peer.
- `LIGHTWEIGHT`: offscreen WebView rendered into Swing pixels.

The public enum and factory are in `WebViewComponent` ([`WebViewComponent.java`](https://github.com/webliteca/swingwebview/blob/39af1688fdc9acffea264b1155cc1602b7b65453/src/ca/weblite/webview/swing/WebViewComponent.java#L80-L119)). `create()` picks heavyweight on macOS/Windows and lightweight on Linux, unless `ca.weblite.webview.mode` is set ([`WebViewComponent.java`](https://github.com/webliteca/swingwebview/blob/39af1688fdc9acffea264b1155cc1602b7b65453/src/ca/weblite/webview/swing/WebViewComponent.java#L95-L149)).

The README's platform table is important for risk assessment:

- macOS heavyweight: full; lightweight is a stub.
- Linux heavyweight: rendering/mouse/scroll mostly work, but visible text input is unreliable; lightweight is the full Linux path.
- Windows heavyweight: full on Windows 11; lightweight is a stub.

Source: [`README.md`](https://github.com/webliteca/swingwebview/blob/39af1688fdc9acffea264b1155cc1602b7b65453/README.md#L28-L38).

Implication for Chat4J: the default mode differs by OS. Production validation must cover both heavyweight and lightweight behavior rather than assuming one rendering path.

## Swing Mixing and Popups

Heavyweight mode paints directly to screen pixels and can appear above overlapping Swing components. The README calls this out and recommends forcing heavyweight Swing popups so menus/tooltips are not painted behind the WebView ([`README.md`](https://github.com/webliteca/swingwebview/blob/39af1688fdc9acffea264b1155cc1602b7b65453/README.md#L77-L124)).

The spike does this at startup:

```java
JPopupMenu.setDefaultLightWeightPopupEnabled(false);
ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
```

Implication for Chat4J: a per-message native WebView must not sit under overlay controls, inline popups, autocomplete menus, or floating message actions. Keep native browser surfaces in simple containers and keep overlays outside the browser bounds.

## Public API Surface Relevant to Chat4J

`WebViewComponent` exposes the core operations Chat4J would need:

- `setUrl(String)` for loading generated HTML via file/data URLs.
- `setDebug(boolean)` and `openDevTools()` for manual debugging.
- `addOnBeforeLoad(String)` for injecting document-start JavaScript.
- `eval(String)` and `evalAsync(String)`.
- `addJavascriptCallback(String, WebView.JavascriptCallback)` for page-to-Java calls.
- `dispose()` for native cleanup.

Source: [`WebViewComponent.java`](https://github.com/webliteca/swingwebview/blob/39af1688fdc9acffea264b1155cc1602b7b65453/src/ca/weblite/webview/swing/WebViewComponent.java#L157-L229).

Console capture is first-class and EDT-safe for Swing UI updates ([`WebViewComponent.java`](https://github.com/webliteca/swingwebview/blob/39af1688fdc9acffea264b1155cc1602b7b65453/src/ca/weblite/webview/swing/WebViewComponent.java#L276-L329)). Context-menu and DOM mouse hooks exist, but they are primarily DOM event callbacks rather than general navigation control ([`WebViewComponent.java`](https://github.com/webliteca/swingwebview/blob/39af1688fdc9acffea264b1155cc1602b7b65453/src/ca/weblite/webview/swing/WebViewComponent.java#L331-L416)). Dialog handling can be suppressed with `setDialogHandler(null)`, useful for untrusted generated message HTML and headless tests ([`README.md`](https://github.com/webliteca/swingwebview/blob/39af1688fdc9acffea264b1155cc1602b7b65453/README.md#L262-L322)).

## JavaScript Interop

The README documents three JS interop methods: fire-and-forget `eval`, `evalAsync` with JSON-stringified results, and `addJavascriptCallback` for Java callbacks exposed as globals ([`README.md`](https://github.com/webliteca/swingwebview/blob/39af1688fdc9acffea264b1155cc1602b7b65453/README.md#L199-L245)). It also documents that `WebViewComponent` future continuations land on the Swing EDT, so they can update Swing state directly ([`README.md`](https://github.com/webliteca/swingwebview/blob/39af1688fdc9acffea264b1155cc1602b7b65453/README.md#L247-L260)).

The spike includes a header theme switcher for manual checks across FlatLaf core, macOS, Nord, GitHub, and Solarized themes. Changing the theme updates the Swing UI and reloads any loaded spike pages so Chat4J-generated HTML is regenerated with the active theme font defaults and dark/light palette.

The spike uses these features to:

- capture console messages into the Swing log
- route link clicks through `chat4jLinkClick`
- test `evalAsync` from a toolbar button
- test JavaScript-to-Java callbacks from page buttons

Implication for Chat4J: this is enough for link interception, copy buttons, future code-action callbacks, and measuring rendered document size. However, any security-sensitive interception implemented in JavaScript is weaker than a native request handler.

## Lifecycle and Native Cleanup

`dispose()` is part of the public API and is documented as releasing native resources; it is also called when the component's peer is destroyed ([`WebViewComponent.java`](https://github.com/webliteca/swingwebview/blob/39af1688fdc9acffea264b1155cc1602b7b65453/src/ca/weblite/webview/swing/WebViewComponent.java#L223-L229)). Heavyweight disposal tears down dialog dispatching and then disposes the embedded native view ([`WebViewHeavyweightComponent.java`](https://github.com/webliteca/swingwebview/blob/39af1688fdc9acffea264b1155cc1602b7b65453/src/ca/weblite/webview/swing/WebViewHeavyweightComponent.java#L195-L208)). Lightweight disposal stops its repaint timer and disposes the offscreen engine ([`WebViewLightweightComponent.java`](https://github.com/webliteca/swingwebview/blob/39af1688fdc9acffea264b1155cc1602b7b65453/src/ca/weblite/webview/swing/WebViewLightweightComponent.java#L493-L504)).

The spike includes a lifecycle stress tab with two modes:

1. default: reuse one WebView and reload generated pages
2. explicit probe: dispose/recreate each iteration

Implication for Chat4J: production should prefer reuse/update where possible, but `ChatMessageView.dispose()` already gives the right hook for cleanup when messages are removed.

## Focus and Shortcuts

SwingWebView explicitly addresses clipboard/editing shortcuts. The README says Cmd/Ctrl+C/V/X/A are routed to the native editing primitives while sibling Swing widgets keep their normal shortcut behavior ([`README.md`](https://github.com/webliteca/swingwebview/blob/39af1688fdc9acffea264b1155cc1602b7b65453/README.md#L40-L51)).

The heavyweight component installs a global `KeyEventDispatcher`, focus listeners, and mouse listeners during `addNotify()` to coordinate focus and shortcuts ([`WebViewHeavyweightComponent.java`](https://github.com/webliteca/swingwebview/blob/39af1688fdc9acffea264b1155cc1602b7b65453/src/ca/weblite/webview/swing/WebViewHeavyweightComponent.java#L219-L283)). Shortcut dispatch checks whether focus is in the WebView or the native view is first responder before executing native copy/paste/select-all commands ([`WebViewHeavyweightComponent.java`](https://github.com/webliteca/swingwebview/blob/39af1688fdc9acffea264b1155cc1602b7b65453/src/ca/weblite/webview/swing/WebViewHeavyweightComponent.java#L363-L414)).

Implication for Chat4J: manual QA must include focus returning to the composer and copy/select-all behavior in message views. This area is more integrated than a bare native browser, but it still uses global listeners, so production integration should watch for interactions with Chat4J shortcuts.

## Loading Chat4J HTML

The spike reuses `MessageHtmlRenderer` to generate realistic Chat4J HTML and loads it via `setUrl(...)` from generated files under `target/swingwebview-spike-pages`. The static tab can also load the same kind of content through a base64 data URL.

Potential production approaches:

1. **Data URL per message:** simple, no local file access, but awkward for very large messages and asset references.
2. **Generated temp files:** easy and debuggable, but `file://` policy needs careful restriction.
3. **Custom resource scheme:** preferable for security and asset control, but SwingWebView's public API does not currently expose a CEF-style resource/request handler.

Recommendation: if SwingWebView is selected, start with data URLs or tightly-scoped temp files and keep links intercepted. Revisit if upstream adds native navigation/resource hooks.

## Navigation and Link Handling Gap

Chat4J's current Swing renderer uses `ExternalLinkSupport` to allow only `http`, `https`, and `mailto`. JCEF can enforce this in `onBeforeBrowse`. SwingWebView's public API does not expose an equivalent browser-level navigation callback in the researched source. It has DOM mouse/context callbacks and JS callbacks, so the spike intercepts anchor clicks at document start and calls Java.

This is acceptable for a controlled Chat4J-generated document if:

- all rendered HTML is sanitized/owned by Chat4J
- the interceptor is injected before content is loaded
- links are allowed only through Java-side scheme checks
- unsafe inline scripts are not introduced into message HTML

It is weaker than JCEF because malicious or malformed content could attempt non-click navigation (`window.location`, meta refresh, form submit) unless additional page restrictions are injected.

## Comparison with JCEF Direction

Compared with JCEF:

Advantages:

- much smaller Java API and simpler setup
- Maven dependency includes native libraries, no first-run CEF bundle download
- system engines: WKWebView, WebKitGTK, WebView2
- direct Swing component factory
- built-in focus/shortcut cooperation work

Risks:

- platform behavior differs substantially by OS/mode
- no obvious public request/navigation handler
- system engine versions vary by OS/runtime
- Windows requires WebView2 Runtime presence
- Linux requires WebKitGTK runtime compatibility; lightweight mode has IME/context-menu limitations
- native library extraction and jpackage signing/notarization still need validation

## Spike Added in Chat4J

Files added:

- `src/swingwebview-spike/java/com/github/drafael/chat4j/chat/message/SwingWebViewSpikeFrame.java`
- Maven profile `swingwebview-spike`
- header FlatLaf theme switcher for manual renderer compatibility checks
- `2026-05-22-swingwebview-spike-plan.md`
- this findings document

Run:

```bash
mvn -Pswingwebview-spike compile exec:java
```

Compile-only validation:

```bash
mvn -q -Pswingwebview-spike compile
```

## Recommendation

Continue with SwingWebView as an experimental candidate, but keep JCEF research alive. SwingWebView may be the better fit if manual cross-platform testing confirms stability and the JavaScript link-interception model is acceptable for Chat4J-generated message HTML. If strict browser-level navigation/resource control is required, JCEF remains the stronger architecture despite its larger packaging footprint.
