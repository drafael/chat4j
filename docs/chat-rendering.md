# Chat Rendering

Chat4J has two chat transcript engines behind the same message model.

## Engines

Setting key:

```text
chat4j.chat.webView.engine
```

Values:

- `jeditor-pane` — Swing `JEditorPane`; default outside macOS and fallback everywhere.
- `swing-webview` — one full-transcript SwingWebView; default on macOS.

Engine changes require restart because startup resolves the active renderer once. If SwingWebView is configured/defaulted but unavailable, Chat4J keeps the setting for diagnostics, warns non-fatally, and uses `JEditorPane` for the session.

## Architecture

The production WebView path replaces only the transcript area. Sidebar, composer, model selector, settings, and source Swing message views remain Swing.

Key classes:

- `ChatWebViewEngine` — persisted engine values.
- `ChatWebViewRuntimeStatus` / `ChatWebViewRuntimeStatusResolver` — startup resolution, defaulting, and fallback reason.
- `ChatWebViewPanel` — settings and diagnostics UI.
- `ChatMessageViewFactory`, `MessageBubble`, `MessageContentView` — renderer-neutral message boundary.
- `JEditorPaneMessageContentView` — Swing fallback/default renderer.
- `SwingWebViewTranscriptView` — production full-transcript WebView renderer.
- `SwingWebViewMessageContentView` — retained per-message spike implementation; not used for production transcript rendering.
- `MessageHtmlRenderer` — message-to-HTML conversion.
- `ExternalLinkSupport` — shared safe link opener.

`ChatPanel` maintains Swing message views as the source model. In SwingWebView mode it mirrors those messages into `SwingWebViewTranscriptView`. This avoids native clipping/scroll issues seen with per-message WebViews inside a Swing scroll pane.

## SwingWebView behavior

The transcript WebView owns:

- Markdown, table, code, activity-bubble, and source-preview HTML.
- Theme-aware syntax highlighting for labelled fenced code blocks and raw Markdown/source mode.
- Theme-aware CSS matching FlatLaf colors.
- Safe external-link routing for `http`, `https`, and `mailto`; `javascript:`, `file:`, and `data:` links are rejected.
- Selected-text copy, context-menu actions, code/activity copy buttons, hover actions, custom scrollbar, fades, and jump-to-latest control.
- Session-scoped streaming updates; stale callbacks are ignored when they do not belong to the visible session.

When conversation history loads or a visible stream completes, the transcript is refreshed to avoid stale DOM state. Native WebView resources are disposed from `ChatPanel.removeNotify()`.

## Syntax highlighting

SwingWebView uses bundled Highlight.js through GraalJS Community before the document is handed to the native WebView.

Behavior:

- Preview mode highlights fenced code blocks only when they declare a supported language.
- Unlabelled code fences are not auto-detected and remain plain.
- Unsupported languages remain plain while keeping the language header.
- Inline code is not highlighted.
- Math fallback blocks are excluded from syntax highlighting.
- Markdown/raw mode renders the whole user or assistant message as highlighted `markdown` source.

Chat4J owns the `.hljs-*` CSS colors so highlighting follows the active light/dark FlatLaf theme. Highlight.js assets are bundled at `src/main/resources/web/highlight/` and listed in `THIRD_PARTY_NOTICES.md`.

Initial bundled language coverage includes Markdown, Java, Kotlin, JavaScript, TypeScript, JSON, XML/HTML, CSS, Bash/Shell, YAML, SQL, Python, diff, and plaintext. Common aliases are normalized before rendering, for example `js` → `javascript`, `ts` → `typescript`, `sh`/`shell` → `bash`, `md` → `markdown`, and `html` → `xml`.

Color normalization is intentionally conservative rather than fully semantic:

- keywords, control-flow words, modifiers, and TypeScript/JavaScript built-in primitive types use the keyword color;
- Java/Kotlin primitive types are post-processed with `chat4j-primitive` so `int`, `long`, `boolean`, `void`, etc. use the keyword color while class names remain neutral;
- class names, method names, variables, fields/properties, attributes, operators, params, and punctuation use a neutral code color;
- strings, comments, numbers/literals, tags/selectors, metadata, Markdown sections, and diff additions/deletions keep distinct colors.

## Math and chemistry rendering

SwingWebView progressively enhances explicit math fallback nodes. `JEditorPane` keeps readable fallback markup.

Supported input forms include:

- Inline: `$...$`, `\(...\)`
- Display: `$$...$$`, `\[...\]`
- Common bare display-LaTeX lines emitted by providers, e.g. lines starting with `\text`, `\frac`, `\ce`, `\xrightarrow`, etc. when they also contain math operators.
- Chemistry via KaTeX `mhchem`, e.g. `\ce{CO2 + H2O <=> H2CO3}` and `\pu{5 mol}`.

Implementation:

1. Markdown emits explicit fallback nodes only:
   - inline: `code.md-latex-inline`
   - display: `table.md-code-block.md-latex-block`
2. `KatexMathRenderer` runs bundled KaTeX/mhchem through GraalJS Community before the document is handed to SwingWebView.
3. Successfully rendered formulas replace fallback nodes with KaTeX HTML.
4. Invalid or unsupported formulas leave the original fallback visible.
5. A WebView-side helper remains as best-effort progressive enhancement, but runtime correctness does not depend on native WebView JavaScript execution.

Why explicit nodes only: avoid false positives such as currency, source links, ordinary code, and prose.

Assets and notices:

- KaTeX resources: `src/main/resources/web/katex/`
- Third-party notices: `THIRD_PARTY_NOTICES.md`
- GraalJS dependency: `org.graalvm.polyglot:js-community` to avoid the enterprise/GFTC artifact.

## Packaging

SwingWebView and GraalJS Community are normal Maven runtime dependencies. The shaded jar must merge `META-INF/services` entries; otherwise GraalJS can load `js` but not its dependent `regex` language, causing packaged-app math rendering to fall back to raw LaTeX.

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

If SwingWebView reports missing modules at runtime, test adding likely candidates such as `jdk.jsobject`.

## Verification

Automated:

```bash
mvn -q test
mvn -q package -DskipTests
git diff --check
```

Targeted tests:

- `ChatWebViewEngineTest`
- `ChatWebViewRuntimeStatusResolverTest`
- `ChatPanelTest` streaming/conversation-switching cases
- `MarkdownRendererTest`
- `MessageHtmlRendererTest`
- `KatexMathRendererTest`
- `SwingWebViewTranscriptViewTest`

Manual checks:

1. Confirm platform default (`SwingWebView` on macOS, `JEditorPane` elsewhere).
2. Switch engine in **Settings → Chat WebView**, restart, and confirm diagnostics.
3. Verify existing chats, streaming, conversation switching, markdown/raw mode, tables, code copy, source chips/previews, links, blocked unsafe links, scrolling, shutdown, and math/chem examples.
