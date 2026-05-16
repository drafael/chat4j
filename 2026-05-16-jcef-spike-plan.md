# JCEF Web View Spike Plan

## Goal

Prove that JCEF can be embedded in Chat4J's Swing runtime before wiring it into the production message view engine selection. The spike must stay opt-in and must not affect normal startup, tests, or packaging.

## Scope

- Add an opt-in Maven profile named `jcef-spike`.
- Add a standalone dev runner launched with `mvn -Pjcef-spike compile exec:exec`.
- Use `me.friwi:jcefmaven` with first-run native download/extraction.
- Render static HTML and Chat4J-generated message HTML in JCEF.
- Exercise browser create/dispose lifecycle repeatedly.
- Do not add production settings or make JCEF selectable in Chat4J yet.

## Non-Goals

- No `WebViewEngine` setting yet.
- No `JcefMessageContentView` production implementation yet.
- No jpackage/native bundling integration yet.
- No CI requirement for JCEF.

## Design Decisions

### Opt-in profile

Normal builds remain unchanged. JCEF dependency and native download behavior are only active when the `jcef-spike` Maven profile is selected.

Target command:

```bash
mvn -Pjcef-spike compile exec:exec
```

Use `exec:exec` instead of `exec:java` so the profile can launch a forked JVM with the required JCEF/macOS `--add-exports` and `--add-opens` flags.

### JCEF source

Use `me.friwi:jcefmaven` with runtime native download for the spike. This is lower friction than bundling platform-native artifacts and is appropriate before packaging work.

Native files should be installed under:

```text
target/jcef-spike-bundle
```

Generated HTML pages should be written under:

```text
target/jcef-spike-pages
```

### Runner location

Add the dev runner outside production chat packages:

```text
src/jcef-spike/java/com/github/drafael/chat4j/dev/jcef/JcefSpikeFrame.java
```

The `jcef-spike` profile adds this source directory with `build-helper-maven-plugin`. The class is not compiled by default, is not referenced by production code, and is only launched through the opt-in profile.

### Renderer visibility

Make `MessageHtmlRenderer` public but internal. Only its primary `render(...)` method should become public. Helper methods remain package-private/private.

Document intent in the class Javadoc: public for internal cross-package reuse, not a stable external API.

## Runner Behavior

### Initialization

- Show a Swing frame with status/log output.
- Initialize JCEF once using `CefAppBuilder`.
- Configure:
  - install dir: `target/jcef-spike-bundle`
  - `ConsoleProgressHandler`
  - Chromium flags that reduce background networking/log spam, including `--disable-background-networking`, `--disable-component-update`, `--disable-sync`, `--disable-in-process-stack-traces`, `--use-mock-keychain`, and `--disable-features=AutofillServerCommunication,MediaRouter,OptimizationHints,PushMessaging,SpareRendererForSitePerProcess`
  - `builder.setAppHandler(new MavenCefAppHandlerAdapter() {})`
- Avoid `CefApp.addAppHandler(...)`, especially for macOS.
- Default to native/windowed rendering with a resize workaround because OSR/windowless rendering hung during macOS testing. Allow OSR comparison with `CHAT4J_JCEF_SPIKE_OSR=true`.
- Install a request handler that follows IntelliJ's `onBeforeBrowse(...)` approach: user-gesture navigations are blocked in the embedded browser, safe external schemes are opened outside JCEF, and unsafe schemes are logged/rejected.
- Enable DevTools only when launched with `CHAT4J_JCEF_SPIKE_DEVTOOLS=true`; this sets remote debugging port `9222` and enables the Open DevTools button.
- Install a small `CefMessageRouter` before browser creation to test JavaScript-to-Java callbacks from the Focus/Shortcuts tab.

### Static Smoke tab

Render a simple static HTML page with:

- headings
- paragraphs
- inline styles
- links and navigation-policy probes (`https`, `mailto`, `javascript:`, `file:`, `data:`, relative, and `target=_blank`)
- code blocks
- basic layout

Success criteria:

- JCEF initializes.
- Native bundle downloads/extracts if needed.
- Browser component embeds in Swing.
- HTML paints correctly.
- Closing the frame does not hang.

### Chat HTML tab

Use `MessageHtmlRenderer.render(...)` for realistic samples:

- user message with `[SKILL]` and `[FALLBACK]` badges
- user message escaping for raw HTML/script-like text
- assistant preview markdown with list/table/code/link content
- assistant dark preview palette
- assistant markdown/raw mode with escaped HTML
- rich markdown with ordered lists, tables, code, unicode, and command snippets
- long assistant response for scrolling/wrapping behavior
- link edge cases for future navigation-policy review

Load each sample from a temp file to avoid data-URL length and encoding issues. Include a run-all button that writes, validates expected HTML fragments for, and sequentially loads every sample. Include an explicit navigation-policy sample.

### Direct Resource Smoke

The Static Smoke tab also includes a direct resource button that serves HTML from an in-memory CEF resource handler at `https://chat4j-spike.local/...`. This compares the current temp-file loading path with an IntelliJ-style controlled resource-loading path before production chooses how `JcefMessageContentView` should deliver Chat4J HTML.

### Focus/Shortcuts tab

Add a manual focus/shortcut validation tab with a Swing focus probe and a browser page. Use it to test:

- browser reload does not steal focus unexpectedly
- clicking browser content accepts typing
- Cmd/Ctrl+A and Cmd/Ctrl+C work inside browser content
- Tab traversal between Swing controls and browser content remains acceptable
- the JS bridge button sends a `CefMessageRouter` query and updates the page from the Java response

### Lifecycle Stress tab

Provide a configurable iteration count, default 10.

Default mode reuses one browser and loads a new small HTML file each iteration. This reflects the likely production update path and avoids native churn.

An explicit dispose/recreate checkbox is available as a native cleanup crash probe. On macOS with jcefmaven 143.0.14, rapid dispose/recreate crashed native CEF during early testing, so this mode is intentionally separated from the default pass/fail path.

For each default iteration:

1. Ensure one browser exists.
2. Load small HTML into that browser.
3. Wait for the configured delay.
4. Log progress/failures.

Success criteria:

- No obvious crashes or hangs in default reuse mode.
- Explicit dispose/recreate mode records native cleanup stability separately.
- Browser close follows IntelliJ's safer per-browser sequence: `stopLoad()`, `setCloseAllowed()`, then `close(true)`.
- On macOS with jcefmaven `143.0.14`, explicit `CefApp.dispose()` in a local unsigned spike launch trapped native CEF with exit code 133, so the spike abruptly halts the forked JVM without explicit CEF disposal on macOS for window close. The Maven profile also launches through `src/jcef-spike/bin/run-jcef-spike.sh`, which owns Ctrl+C handling and kills the forked JVM without native CEF shutdown.
- Shutdown path findings are documented clearly enough to plan production integration.

## Error Handling

- If JCEF initialization fails, show the exception in the frame log and console.
- Do not crash silently.
- Keep production Chat4J unaffected because the spike is launched separately.

## Documentation

Update `docs/configurable-web-view-engine.md` with a development spike section:

```bash
mvn -Pjcef-spike compile exec:exec
```

Include notes:

- first run downloads native JCEF files
- generated files are under `target/jcef-spike-*`
- the spike is not production integration
- successful spike should be followed by production `JcefMessageContentView` design

## Verification

Run normal validation to ensure no default-build impact:

```bash
mvn -q test
```

Run spike manually:

```bash
mvn -Pjcef-spike compile exec:exec
```

Manual checks:

- Static Smoke renders.
- Chat HTML renders samples from `MessageHtmlRenderer`.
- Direct Resource Smoke renders from the intercepted in-memory resource handler.
- Chat HTML navigation-policy sample renders and link clicks do not navigate in-place.
- Lifecycle Stress completes default 10 iterations.
- Focus/Shortcuts tab manual checks pass.
- Closing the spike window exits cleanly.

## Follow-Up After Spike

If the spike succeeds:

1. Decide native bundling approach for jpackage.
2. Add `WebViewEngine` enum and persisted setting.
3. Implement production `JcefMessageContentView`.
4. Add factory fallback to Swing if JCEF init fails.
5. Validate resource disposal under conversation clear/reload/regenerate flows.
