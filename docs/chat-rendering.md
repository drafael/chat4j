# Chat Rendering and WebView Engines

Chat4J has one message model and three conversation engines. The browser-backed engines replace only the conversation area; the sidebar, composer, model selector, settings, and Swing source message views stay Swing.

## Engine setting

Setting key:

```text
chat4j.chat.webView.engine
```

Values:

- `jeditor-pane` — Swing HTML Renderer; final fallback everywhere.
- `system` — System WebView backed by SwingWebView.
- `jcef` — Chromium Embedded Framework (JCEF).

Engine changes require restart. Startup resolves one configured engine and one active engine. Chat4J keeps the requested setting for diagnostics even if the active engine falls back.

Fallback chains:

- macOS/Windows: `system` → `jcef` → `jeditor-pane`
- Linux/other: `jcef` → `jeditor-pane`

With no saved setting, the first engine in the platform chain is treated as the configured/default engine for that session. Unknown or obsolete saved values are treated the same way.

## Architecture

Key classes:

- `WebViewEngine` — persisted engine values and display labels.
- `WebViewRuntimeStatus` / `WebViewRuntimeStatusResolver` — startup resolution, availability checks, active-engine selection, and fallback reason.
- `AppearancePanel` — engine selector and diagnostics UI.
- `ChatMessageViewFactory`, `MessageBubble`, `MessageContentView`, `JEditorPaneMessageContentView` — Swing source message model and final fallback renderer.
- `SystemWebView` — full-conversation System WebView renderer.
- `JcefRuntime` / `JcefBrowserView` — JCEF startup and full-conversation Chromium renderer.
- `TranscriptDocumentRenderer`, `TranscriptEntryRenderer`, `TranscriptBrowserAssets`, `TranscriptResources`, and related `webview/shared` classes — shared browser transcript document, entry, asset, template, and update rendering.
- `MessageHtmlRenderer` — message-to-HTML conversion.
- `ExternalLinkSupport` — shared external-link allowlist and opener.

Data flow:

1. `ChatPanel` creates and maintains Swing `ChatMessageView` instances as the source model.
2. In `jeditor-pane` mode, those views are displayed directly.
3. In `system` or `jcef` mode, `ChatPanel` mirrors the source views into one full-conversation browser component.
4. Full-conversation browser views are disposed from `ChatPanel.removeNotify()`.

The full-conversation design avoids per-message native browser churn and the clipping/scrolling problems seen with native components inside Swing scroll panes.

## Shared browser transcript implementation

System WebView and JCEF use the same transcript renderer and packaged web resources. Engine classes keep lifecycle, loading, JavaScript evaluation, callback plumbing, disposal, and engine-specific URL handling. Shared classes own document assembly, entry rendering, attachments, source previews, template resolution, CSS variables, runtime scripts, and incremental update snippets.

Shared Java package:

```text
src/main/java/com/github/drafael/chat4j/chat/conversation/webview/shared/
```

Important shared classes:

- `TranscriptDocumentRequest` — immutable full-document render input: snapshot, scroll behavior, asset mode, and optional internal asset URLs.
- `TranscriptRenderSnapshot` / `TranscriptRenderSupport` — stable render input and font-scope handling.
- `TranscriptDocumentRenderer` — full transcript document assembly and theme CSS generation.
- `TranscriptEntryRenderer` / `TranscriptAttachmentRenderer` — message, activity, attachment, source-link, code, and math fallback HTML.
- `TranscriptBrowserAssets` / `TranscriptAssetMode` — browser asset tag generation. System WebView uses inline assets; JCEF serves large Mermaid and SmilesDrawer bundles from internal `chat4j.local` URLs.
- `TranscriptResources` — required classpath resource loading, data URI generation, script escaping, font inlining, and fixed-token template resolution with unresolved-token validation.
- `TranscriptUpdateScripts` — shared incremental transcript, jump-button, and scroll-to-bottom JavaScript snippets.
- `TranscriptCallbackPayloads` — shared WebView callback and transcript action parsing.

Reusable browser resources live under:

```text
src/main/resources/web/chat/
  transcript-document.html
  transcript.css
  transcript-*.css
  transcript-actions.js
  math-render.js
  diagram-render.js
```

`transcript.css` is a small fixed-token composition template. Focused partials (`transcript-layout.css`, `transcript-code.css`, `transcript-diagrams.css`, and so on) keep the generated browser CSS maintainable while `TranscriptDocumentRenderer` injects theme variables, attachment CSS, syntax highlighting CSS, and partial content. No template engine is used; `TranscriptResources.resolveTemplate(...)` replaces known tokens and fails fast for tokens left in the original template.

## Browser conversation behavior

Both browser engines own the rendered conversation chrome:

- markdown, tables, code blocks, activity bubbles, attachments, and source previews
- light/dark theme CSS derived from FlatLaf colors
- custom scrollbar, fades, and jump-to-latest control
- selected-text copy, context-menu actions, code/activity copy buttons, and regenerate actions
- safe external-link routing for `http`, `https`, and `mailto`
- rejection of `javascript:`, `file:`, and `data:` links
- stale-update protection during streaming and conversation switches

When conversation history loads or a visible stream completes, the browser conversation view is refreshed to avoid stale DOM state.

## Syntax highlighting

Browser conversation views use bundled Highlight.js through GraalJS Community before HTML is loaded into the browser.

Rules:

- labelled fenced code blocks are highlighted when the language is supported
- unlabelled fences are not auto-detected
- unsupported languages remain plain but keep the language header
- inline code is not highlighted
- math fallback blocks are excluded from highlighting
- raw Markdown mode renders the whole message as highlighted `markdown` source

Bundled language coverage includes Markdown, Java, Kotlin, JavaScript, TypeScript, JSON, XML/HTML, CSS, Bash/Shell, YAML, SQL, Python, diff, and plaintext. Common aliases are normalized (`js` → `javascript`, `ts` → `typescript`, `sh`/`shell` → `bash`, `md` → `markdown`, `html` → `xml`).

## Math, diagrams, and chemistry

Browser conversation views progressively enhance explicit math fallback nodes and explicit diagram/chemistry fences. Swing HTML Renderer keeps the readable fallback markup/source blocks.

Supported math input forms:

- inline: `$...$`, `\(...\)`
- display: `$$...$$`, `\[...\]`
- common bare display-LaTeX lines, such as `\frac`, `\ce`, and `\xrightarrow`, when they contain math operators
- KaTeX `mhchem`, such as `\ce{CO2 + H2O <=> H2CO3}` and `\pu{5 mol}`

Supported diagram and chemistry fences:

- `mermaid`: renders flowcharts, sequence diagrams, state diagrams, class diagrams, ER diagrams, and process diagrams with bundled Mermaid.
- `smiles`: renders generated chemical structures with bundled SmilesDrawer.
- `mol`: renders complete V2000 MOL records with the built-in browser-side SVG renderer.
- `sdf`: renders one or more complete V2000 MOL records separated by `$$$$`; browser views show the first 12 records and display a truncation note when more are present.

Rendering rules:

- Only explicit fenced blocks are rendered; prose is not auto-detected.
- Successful diagrams replace the source block.
- Failed diagrams keep the source visible and show a friendly error label.
- Mermaid/SMILES assets are bundled locally; no CDN or runtime network access is required.
- MOL/SDF rendering is limited to complete V2000 source records.

Normal chat requests include a lightweight system hint that advertises renderable fence formats to the selected model. The hint distinguishes general diagrams from chemistry: models are told to use `mermaid` only for flow/process-style diagrams and to use `smiles` for generated molecule drawings. MOL/SDF rendering remains available for exact user-supplied V2000 source blocks, but normal model guidance avoids advertising MOL/SDF as generated output because models frequently fabricate invalid coordinate files from names, formulas, or descriptions.

Rendered Mermaid diagrams can be opened outside Chat4J from the hover button or the `Open Diagram` context-menu item. Chat4J serializes the rendered SVG, validates it in `DiagramHtmlExporter`, writes a temporary standalone HTML wrapper with a strict CSP, and opens it through Desktop integration. The exporter rejects scripts, event-handler attributes, oversized payloads, and external `href`/`src` references; internal fragment references such as `#id` are allowed.

Implementation:

1. Markdown emits explicit fallback nodes only:
   - inline math: `code.md-latex-inline`
   - display math: `table.md-code-block.md-latex-block`
   - diagrams: `table.md-code-block.md-diagram-block` plus format-specific marker classes
2. `KatexMathRenderer` runs bundled KaTeX/mhchem through GraalJS before browser load.
3. Valid formulas are replaced with KaTeX HTML.
4. Invalid or unsupported formulas keep the fallback visible.
5. Browser helpers render Mermaid, SMILES, MOL, and SDF blocks after document load and transcript refresh.

Assets:

- Browser transcript templates/scripts/styles: `src/main/resources/web/chat/`
- Highlight.js: `src/main/resources/web/highlight/`
- KaTeX/mhchem: `src/main/resources/web/katex/`
- Mermaid: `src/main/resources/web/mermaid/`
- SmilesDrawer: `src/main/resources/web/smilesdrawer/`
- Notices: `THIRD_PARTY_NOTICES.md`

## JCEF notes

JCEF is production-selectable but still has native-platform risk. It is intentionally isolated behind `JcefRuntime` and `JcefBrowserView`.

Runtime behavior:

- `JcefRuntime` initializes CEF once per process.
- Native/windowed rendering is the supported path; OSR/windowless remains experimental.
- Conversation HTML is served through an in-memory `https://chat4j.local/...` resource handler.
- The response declares UTF-8 and supplementary Unicode code points are encoded as numeric entities before loading.
- In-browser navigation is blocked; safe links open externally through `ExternalLinkSupport`.
- DevTools/remote debugging is opt-in with `-Dchat4j.jcef.devtools=true`.
- Native runtime files are extracted through `me.friwi:jcefmaven` into `~/.chat4j/jcef-bundle` by default, or `-Dchat4j.jcef.installDir=...`.

Known risks from the spike and local testing:

- native/windowed JCEF can overpaint adjacent Swing components during resize/move
- OSR/windowless hung during macOS spike testing
- rapid browser dispose/recreate crashed native CEF during spike testing
- explicit `CefApp.dispose()` trapped in local unsigned macOS runs
- packaged macOS shutdown should remain in release validation for signed/notarized app layouts

See [jcef-intellij-findings.md](jcef-intellij-findings.md) for the source-backed IntelliJ/JCEF research notes.

## Packaging

SwingWebView, JCEF, and GraalJS Community are normal Maven runtime dependencies. The shaded jar must merge `META-INF/services`; otherwise GraalJS can load `js` but not its dependent `regex` language, which makes packaged math rendering fall back to raw LaTeX.

On macOS, JCEF requires AWT internal package exports/opens when attaching native browser components. Local Maven runs get these from `.mvn/jvm.config`; jpackage macOS builds pass equivalent `--java-options`.

Package checks:

```bash
mvn -q package
mvn -q -Pjpackage-mac -DskipTests verify
mvn -q -Pjpackage-win -DskipTests verify
mvn -q -Pjpackage-linux -DskipTests verify
```

For the macOS custom runtime image, current modules are:

```text
java.se,jdk.crypto.ec,jdk.unsupported
```

If System WebView reports missing modules at runtime, test adding likely candidates such as `jdk.jsobject`.

## Verification

Automated:

```bash
mvn -q test
mvn -q package -DskipTests
git diff --check
```

Targeted tests:

- `WebViewEngineTest`
- `WebViewRuntimeStatusResolverTest`
- `ChatPanelTest` streaming/conversation-switching cases
- `MarkdownRendererTest`
- `MessageHtmlRendererTest`
- `KatexMathRendererTest`
- `TranscriptBrowserAssetsTest`
- `TranscriptDocumentRendererTest`
- `JcefInitializationProgressTest`

Manual checks:

1. Confirm fallback chains: System WebView → JCEF → Swing HTML Renderer on macOS/Windows; JCEF → Swing HTML Renderer on Linux/other.
2. Switch engine in **Settings → Appearance**, restart, and confirm diagnostics for all three engines.
3. Verify existing chats, streaming, conversation switching, clearing chat, markdown/raw mode, tables, code copy, source chips/previews, safe links, blocked unsafe links, scrolling, shutdown, and math/chem examples.
4. For JCEF, resize/move the window repeatedly and validate packaged shutdown on macOS, Windows, and Linux.
