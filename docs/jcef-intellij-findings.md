# IntelliJ JCEF/JBCEF Findings

This note records the IntelliJ/JBCEF research used to guide Chat4J's future configurable web-view engine work. It is not a production design by itself; it captures evidence, observed JCEF behavior, and implications for the future `JcefMessageContentView` milestone.

## Executive Summary

IntelliJ does not generally expose raw JCEF directly to UI code. It wraps JCEF behind `JBCefApp`, `JBCefBrowser`, `JBCefBrowserBase`, `JBCefClient`, and `JBCefJSQuery`. Chat4J should not depend on those IntelliJ classes because they require IntelliJ Platform runtime services, but the same shape is useful:

- centralize JCEF initialization and support checks
- keep JCEF behind `MessageContentView`
- fall back to Swing if JCEF is unavailable or fails to initialize
- prefer browser reuse over rapid dispose/recreate churn
- handle links through request/navigation handlers
- isolate heavyweight browser behavior from normal Swing overlays
- keep resize/move and shutdown workarounds inside the JCEF implementation

The current in-repo JCEF spike remains the right next step before production integration.

## Official Plugin API Model

JetBrains' plugin documentation describes JCEF as the replacement for JavaFX web rendering in IntelliJ Platform plugins and recommends using JCEF only when normal Swing UI is not enough or when rendering HTML documents/previews is required.

Source: [JetBrains Embedded Browser/JCEF docs](https://plugins.jetbrains.com/docs/intellij/embedded-browser-jcef.html)

The public plugin pattern is:

1. Check `JBCefApp.isSupported()`.
2. Create a `JBCefBrowser` directly or through `JBCefBrowserBuilder`.
3. Add `browser.getComponent()` to Swing UI.
4. Use `loadURL(...)`, `loadHTML(...)`, handlers, and optional JS bridge APIs.
5. Dispose `JBCefBrowser`, `JBCefClient`, and `JBCefJSQuery` according to IntelliJ `Disposable` rules.

For Chat4J, the equivalent should be an app-owned JCEF adapter, not direct raw CEF calls from `ChatPanel`.

## IntelliJ Wrapper Layers

IntelliJ wraps raw JCEF with these main classes:

- `JBCefApp`: initializes CEF, owns support checks, settings, handlers, custom schemes, and global lifecycle.
- `JBCefBrowser`: Swing-facing wrapper around `CefBrowser`; exposes `getComponent()` and focus/resize behavior.
- `JBCefBrowserBase`: owns load operations, link/navigation helpers, common handlers, devtools, and disposal.
- `JBCefBrowserBuilder`: configures client, URL, OSR/windowed rendering, immediate creation, DevTools wrapping, and mouse-wheel behavior.
- `JBCefClient`: wraps raw `CefClient` and allows multiple handlers per browser.
- `JBCefJSQuery`: wraps CEF message routers for JavaScript-to-JVM callbacks.

Evidence:

- `JBCefBrowser` documents itself as a `CefBrowser` wrapper and says to use `getComponent()`, `loadURL(...)`, and `loadHTML(...)`: [`JBCefBrowser.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefBrowser.java#L36-L48)
- Browser construction creates a component and registers focus handling: [`JBCefBrowser.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefBrowser.java#L49-L108)
- `JBCefApp` wraps `CefApp` and exposes `createClient()`: [`JBCefApp.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefApp.java#L71-L78), [`JBCefApp.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefApp.java#L558-L563)

## Support Checks and Fallback

IntelliJ gates JCEF with `JBCefApp.isSupported()`. The check covers runtime availability, compatible JCEF version, Linux libc support, registry flags, and headless mode policy.

Evidence: [`JBCefApp.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefApp.java#L440-L489)

Implication for Chat4J:

- Production JCEF must be optional.
- `ChatMessageViewFactory` should select JCEF only if the setting requests it and initialization succeeds.
- Any failure should log the reason and fall back to `JEditorPaneMessageContentView`.
- Default builds/tests should remain Swing-only unless an opt-in profile or setting enables JCEF.

## Browser Creation and Ownership

`JBCefBrowserBuilder` supports custom clients, initial URL, wrapping an existing browser such as DevTools, immediate native creation, OSR rendering, and mouse-wheel behavior.

Evidence: [`JBCefBrowserBuilder.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefBrowserBuilder.java#L16-L147)

Ownership behavior is explicit:

- If no `JBCefClient` is supplied, IntelliJ creates a default client and disposes it with the browser.
- If a custom/shared client is supplied, caller owns its lifecycle.

Evidence:

- default/custom client selection: [`JBCefBrowserBase.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefBrowserBase.java#L194-L207)
- builder docs for custom client disposal: [`JBCefBrowserBuilder.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefBrowserBuilder.java#L53-L58)

Implication for Chat4J:

- Decide browser/client ownership before production integration.
- Given local spike crashes during rapid dispose/recreate, prefer reusing browser instances where possible.
- If a shared client is introduced, it should be owned by a small JCEF engine service and disposed once at app shutdown.

## Handler Aggregation

Raw `CefClient` generally has one handler slot per handler type. IntelliJ's `JBCefClient` wraps it with per-browser handler lists and aggregated dispatch. This lets multiple features register handlers without overwriting each other.

Evidence:

- request-handler aggregation dispatches to all or first matching registered handlers: [`JBCefClient.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefClient.java#L605-L681)
- `removeAllHandlers(browser)` clears all handler groups for a browser: [`JBCefClient.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefClient.java#L684-L696)
- handler support stores lists per `CefBrowser`: [`JBCefClient.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefClient.java#L698-L783)

Implication for Chat4J:

- Keep JCEF handlers centralized in the engine/view implementation.
- Avoid scattered raw `CefClient.add*Handler(...)` calls from unrelated UI classes.
- If multiple features need handlers later, create a small aggregator rather than allowing overwrites.

## Rendering Modes: Native/Windowed vs OSR

IntelliJ can create native/windowed browsers or off-screen rendered browsers. The builder says OSR uses buffered rendering onto a lightweight Swing component; when disabled, windowed mode is used.

Evidence: [`JBCefBrowserBuilder.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefBrowserBuilder.java#L29-L38)

OSR is not assumed always available. IntelliJ gates OSR through settings and throws if OSR is requested while disabled.

Evidence: [`JBCefApp.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefApp.java#L573-L598)

Chat4J spike result:

- Native/windowed rendering works better for the current macOS spike.
- OSR/windowless mode hung after initialization in local testing.

Implication for Chat4J:

- Native/windowed should be the first production target.
- OSR should remain experimental until validated on macOS, Linux, and Windows.
- The engine abstraction should allow future OSR selection, but the default should not depend on it.

## Heavyweight Component Behavior

Raw JCEF windowed rendering uses native/heavyweight embedding. In `CefBrowserWr`, JCEF creates a Swing `JPanel`; on Windows and Linux it inserts an AWT `Canvas` because it needs a native heavyweight handle. The comments call this out directly.

Evidence:

- component and canvas management: [`CefBrowserWr.java`](https://github.com/JetBrains/jcef/blob/5b93e5b916068316f1c8e7f8a59bf958d5ffd6e1/java/org/cef/browser/CefBrowserWr.java#L190-L344)
- Canvas is required on Windows/Linux as a heavyweight component: [`CefBrowserWr.java`](https://github.com/JetBrains/jcef/blob/5b93e5b916068316f1c8e7f8a59bf958d5ffd6e1/java/org/cef/browser/CefBrowserWr.java#L378-L386)

JCEF also globally disables lightweight Swing popups and tooltips so popups are not displayed behind browser content:

```java
JPopupMenu.setDefaultLightWeightPopupEnabled(false);
ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
```

Evidence: [`CefBrowserWr.java`](https://github.com/JetBrains/jcef/blob/5b93e5b916068316f1c8e7f8a59bf958d5ffd6e1/java/org/cef/browser/CefBrowserWr.java#L190-L195)

Chat4J spike result:

- Swing controls below/near the native browser can be overpainted during resize/move on macOS.
- Moving logs to a separate window avoids placing Swing content under the browser.
- Replacing the sample `JComboBox` popup with radio buttons avoided heavyweight popup issues.

Implication for Chat4J:

- Do not layer normal Swing controls over a native JCEF browser.
- Avoid lightweight popups inside the same visual area as the browser.
- Keep per-message browser components in simple containers.
- Prefer adjacent or separate Swing controls over overlays.

## Resize, Move, and Repaint Workarounds

IntelliJ exposes internal browser move/resize callbacks. It fires them on component resize, move, show, and hide.

Evidence:

- component listener fires callbacks: [`JBCefBrowser.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefBrowser.java#L223-L239)
- callback registration API: [`JBCefBrowser.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefBrowser.java#L296-L304)

Raw JCEF has macOS-specific update logic. During paint, it calls `doUpdate()` and restarts a delayed update timer because the final resize information on macOS may not resize the native UI accurately.

Evidence: [`CefBrowserWr.java`](https://github.com/JetBrains/jcef/blob/5b93e5b916068316f1c8e7f8a59bf958d5ffd6e1/java/org/cef/browser/CefBrowserWr.java#L286-L305)

The native implementation computes visible rectangles and updates native UI placement differently on macOS versus other platforms.

Evidence: [`CefBrowserWr.java`](https://github.com/JetBrains/jcef/blob/5b93e5b916068316f1c8e7f8a59bf958d5ffd6e1/java/org/cef/browser/CefBrowserWr.java#L443-L491)

Chat4J spike result:

- A browser panel resize/move repair workaround was added.
- Validation should continue to include window resize/move checks, not just initial rendering.

Implication for Chat4J:

- Keep resize/move repair inside the future `JcefMessageContentView` or JCEF engine helper.
- Do not leak native resize repair calls into `ChatPanel`.
- Include resize/move tests in manual release validation for JCEF builds.

## Loading HTML and Resources

IntelliJ's `loadHTML(html, url)` is not just a data URL. It registers the HTML as a virtual resource and loads a URL.

Evidence: [`JBCefBrowserBase.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefBrowserBase.java#L407-L430)

The backing `JBCefFileSchemeHandlerFactory` explains the reason: CEF allows loading local resources through `file` only from inside another `file` request, so IntelliJ creates a fake `file:///jbcefbrowser/...` request for `loadHTML(...)`.

Evidence:

- rationale: [`JBCefFileSchemeHandlerFactory.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefFileSchemeHandlerFactory.java#L20-L27)
- registration and fake URL generation: [`JBCefFileSchemeHandlerFactory.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefFileSchemeHandlerFactory.java#L68-L91)

Implication for Chat4J:

- Simple static message HTML can be loaded directly during early integration.
- If messages later reference local assets, generated CSS, images, or scripts, use a controlled custom scheme/resource handler.
- Do not allow arbitrary `file://` access from chat message HTML.

## Navigation and External Links

IntelliJ implements helpers to open user-clicked links externally and to disable browser navigation initiated by user gestures. Both rely on `CefRequestHandler.onBeforeBrowse(...)` and the `user_gesture` flag.

Evidence: [`JBCefBrowserBase.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefBrowserBase.java#L500-L564)

Implication for Chat4J:

- Message links should open in the system browser.
- Internal browser navigation should be blocked unless explicitly required.
- Keep the current allowlist semantics from `ExternalLinkSupport`: `http`, `https`, and `mailto`; reject `javascript:`, `file:`, and `data:`.

## JavaScript Bridge

IntelliJ uses `JBCefJSQuery` for JavaScript-to-JVM callbacks. The bridge injects a `window.<function>` call with request data and success/failure callbacks, backed by CEF message routers.

Evidence:

- injected JS call: [`JBCefJSQuery.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefJSQuery.java#L88-L108)
- handler dispatch through `CefMessageRouterHandlerAdapter`: [`JBCefJSQuery.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefJSQuery.java#L112-L146)

Undocumented gotcha: creating `JBCefJSQuery` after native browser creation has started requires a JS query pool; otherwise IntelliJ warns that the generated function may not exist in JavaScript.

Evidence: [`JBCefJSQuery.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefJSQuery.java#L58-L73)

Implication for Chat4J:

- Avoid JS bridge for first production JCEF integration unless needed.
- Link handling can be done through request handlers.
- If future features add copy buttons, code actions, or inline controls, create any JS bridge before browser creation or introduce a small query-pool equivalent.

## Focus, Shortcuts, and Preferred Size Tweaks

IntelliJ applies platform-specific focus handling:

- suppresses focus on navigation unless configured
- manually handles macOS shortcuts
- requests browser focus differently on Linux vs other platforms
- sets focus traversal policy so traversal enters the browser component

Evidence:

- focus handling: [`JBCefBrowser.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefBrowser.java#L57-L108)
- macOS shortcut registration: [`JBCefBrowser.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefBrowser.java#L196-L204)

It also forces a non-zero preferred size because zero preferred size can prevent content loading from being triggered.

Evidence: [`JBCefBrowser.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefBrowser.java#L386-L394)

Implication for Chat4J:

- Future `JcefMessageContentView` should define a sane preferred/minimum size.
- Keyboard shortcuts should remain tested on macOS, especially copy/select-all behavior inside the browser.
- Avoid browser stealing focus during message updates unless the user explicitly focuses it.

## Scrollbars and CSS Integration

IntelliJ provides `JBCefScrollbarsHelper` to make Chromium scrollbars match the IDE look. It can generate WebKit scrollbar CSS and has an OverlayScrollbars option for more advanced cases.

Evidence: [`JBCefScrollbarsHelper.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefScrollbarsHelper.java#L37-L71)

Implication for Chat4J:

- First JCEF integration can rely on CSS from `MessageHtmlRenderer`.
- If browser-native scrollbars look wrong, add engine-specific scrollbar CSS in `JcefMessageContentView`, not in message orchestration code.

## DevTools and Debugging

JetBrains docs state that Chrome DevTools are active by default and can attach through a remote debugging port. Programmatic DevTools access wraps `browser.getCefBrowser().getDevTools()` in another `JBCefBrowser` with the same client.

Source: [JetBrains Embedded Browser/JCEF debugging docs](https://plugins.jetbrains.com/docs/intellij/embedded-browser-jcef.html#debugging)

Implication for Chat4J:

- Keep DevTools/debug port opt-in for development only.
- Do not expose DevTools in normal user builds unless a debug/developer mode is enabled.

## Disposal and Shutdown

IntelliJ browser disposal removes handlers, stops loading, allows close, closes the browser, and disposes the default client if it owns it.

Evidence: [`JBCefBrowserBase.java`](https://github.com/JetBrains/intellij-community/blob/b420493b3332ec92a70333620428df92f1b85385/platform/ui.jcef/jcef/JBCefBrowserBase.java#L620-L644)

Raw JCEF says `CefApp.dispose()` should close clients and browsers, terminate the message loop, and shut down CEF.

Evidence: [`CefApp.java`](https://github.com/JetBrains/jcef/blob/5b93e5b916068316f1c8e7f8a59bf958d5ffd6e1/java/org/cef/CefApp.java#L475-L506)

Raw browser close has a close-allowed path and dispatches a parent window closing event during `doClose()`.

Evidence:

- `setCloseAllowed()` / `doClose()`: [`CefBrowser_N.java`](https://github.com/JetBrains/jcef/blob/5b93e5b916068316f1c8e7f8a59bf958d5ffd6e1/java/org/cef/browser/CefBrowser_N.java#L139-L160)
- `close(boolean force)`: [`CefBrowser_N.java`](https://github.com/JetBrains/jcef/blob/5b93e5b916068316f1c8e7f8a59bf958d5ffd6e1/java/org/cef/browser/CefBrowser_N.java#L613-L644)

Chat4J spike result:

- Rapid dispose/recreate caused native `SIGSEGV` on macOS.
- Explicit `CefApp.dispose()` during local unsigned macOS launch exited with code `133`.
- The spike therefore skips explicit CEF disposal on macOS and halts/kills the forked spike JVM on close/Ctrl+C.

Implication for Chat4J:

- Do not assume dev-shell shutdown behavior is representative of packaged app behavior.
- Production shutdown must be revisited under signed/jpackaged macOS builds.
- Prefer browser reuse while the app is running.
- Dispose individual message views carefully, but avoid lifecycle stress patterns that rapidly create and destroy native browsers.

## macOS Startup and Packaging Details

JetBrains configures macOS with explicit CEF framework, helper app, browser subprocess, and main-bundle paths. It also passes Chromium flags including:

- `--framework-dir-path=...`
- `--main-bundle-path=...`
- `--browser-subprocess-path=...`
- `--disable-in-process-stack-traces`
- `--use-mock-keychain`
- `--disable-features=SpareRendererForSitePerProcess`
- `--disable-notifications`

Evidence:

- native bundle config: [`JCefAppConfig.java`](https://github.com/JetBrains/jcef/blob/5b93e5b916068316f1c8e7f8a59bf958d5ffd6e1/java/com/jetbrains/cef/JCefAppConfig.java#L61-L86)
- bundled JBR config: [`JCefAppConfig.java`](https://github.com/JetBrains/jcef/blob/5b93e5b916068316f1c8e7f8a59bf958d5ffd6e1/java/com/jetbrains/cef/JCefAppConfig.java#L130-L147)

`CefApp.startup(...)` performs platform-specific startup. On Linux it initializes Xlib multithreading; on macOS it dynamically loads the CEF framework.

Evidence: [`CefApp.java`](https://github.com/JetBrains/jcef/blob/5b93e5b916068316f1c8e7f8a59bf958d5ffd6e1/java/org/cef/CefApp.java#L725-L749)

Chat4J spike result:

- `exec:java` failed on macOS with `IllegalAccessError` for internal AWT access.
- `exec:exec` works because it forks a JVM with required `--add-exports` / `--add-opens` flags.

Implication for Chat4J:

- Production packaging must include native CEF assets correctly.
- macOS signing/notarization and helper-app layout need explicit validation.
- Continue using an opt-in Maven profile for the spike so normal `mvn test` remains unaffected.

## Current Chat4J Spike Findings

The dev-only spike is launched with:

```bash
mvn -Pjcef-spike compile exec:exec
```

Optional OSR comparison:

```bash
CHAT4J_JCEF_SPIKE_OSR=true mvn -Pjcef-spike compile exec:exec
```

Validated areas so far:

- JCEF startup through `me.friwi:jcefmaven`
- native runtime download/extraction into the build tree
- basic static HTML rendering
- Chat4J `MessageHtmlRenderer` HTML rendering
- user/assistant samples, escaping, rich markdown, long responses, link edge cases
- lifecycle reload stress using one browser by default
- native/windowed resize/move behavior with repair workaround
- separate log window to avoid native browser overpaint
- forked JVM launch with macOS AWT exports/opens
- Ctrl+C handling through `src/jcef-spike/bin/run-jcef-spike.sh`

Known risks from the spike:

- OSR/windowless hung on macOS.
- Rapid browser dispose/recreate crashed native CEF.
- Explicit `CefApp.dispose()` exited with code `133` in local unsigned macOS runs.
- Native/windowed JCEF can overpaint adjacent Swing components during resize/move.
- Chromium stderr noise is expected and should be judged by functional behavior, not log cleanliness alone.

## Production Integration Recommendations

Future JCEF integration should be a separate milestone with these pieces:

1. Add a `WebViewEngine` enum, initially `SWING` and `JCEF`.
2. Add a persisted setting such as `chat4j.chat.webView.engine`.
3. Add settings UI for engine selection.
4. Implement `JcefMessageContentView` behind the existing `MessageContentView` contract.
5. Keep `ChatPanel`, `ActivityBubble`, and message orchestration engine-neutral.
6. Add a JCEF availability/init service with clear fallback to Swing.
7. Open user links externally and block in-browser navigation by default.
8. Keep resize/move repair and native lifecycle logic inside the JCEF implementation.
9. Keep DevTools/debug flags development-only.
10. Validate macOS signed/jpackaged shutdown before enabling JCEF by default.

## Validation Checklist for Future JCEF Work

Manual validation should include:

- app starts with Swing engine when JCEF is disabled/unavailable
- app starts with JCEF engine when enabled
- static HTML renders
- all Chat4J message samples render
- links open externally and unsafe schemes are blocked
- copy/select-all keyboard shortcuts still work
- message updates do not steal focus
- long assistant responses scroll/render correctly
- resize and move behavior is acceptable
- clearing chat disposes message views without crashing
- app shutdown succeeds on macOS, Linux, and Windows
- packaged macOS app shutdown succeeds with signing/notarization layout
- default `mvn test` remains unaffected by JCEF dependencies

## Related Documents

- [Configurable Web View Engine Architecture](configurable-web-view-engine.md)
- JCEF spike plan: `../2026-05-16-jcef-spike-plan.md`
