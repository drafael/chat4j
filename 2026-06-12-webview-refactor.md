# WebView Transcript Refactor Plan

## Goal

Refactor `SystemWebView` and `JcefBrowserView` so shared transcript HTML, CSS, and JavaScript live in reusable components/resources while engine-specific lifecycle and loading behavior stays isolated.

Current state:

- `SystemWebView.java`: about 2,650 lines.
- `JcefBrowserView.java`: about 2,970 lines.
- The files are roughly 92% identical by line similarity.
- Large duplicated areas include document rendering, transcript action JavaScript, math/diagram rendering JavaScript, CSS generation, and entry rendering.

## Direction

Use a hybrid refactor:

- Extract common Java rendering/resource logic into shared Java classes.
- Move reusable JS/CSS/HTML templates to `src/main/resources/web/chat/`.
- Do not add `src/main/js`, `src/main/css`, or `src/main/html` yet. Maven already packages `src/main/resources` cleanly, and the project already stores browser assets under `src/main/resources/web/`.

## Target resource layout

```text
src/main/resources/web/chat/
  transcript-document.html
  transcript.css
  transcript-actions.js
  math-render.js
  diagram-render.js
```

Keep third-party libraries where they are now:

```text
src/main/resources/web/highlight/
src/main/resources/web/katex/
src/main/resources/web/mermaid/
src/main/resources/web/smilesdrawer/
```

## Target Java package

```text
src/main/java/com/github/drafael/chat4j/chat/conversation/webview/shared/
```

Candidate classes:

- `TranscriptDocumentRenderer` — renders the full transcript document.
- `TranscriptDocumentRequest` — immutable render input: entries, mode, theme, fonts, scroll behavior, asset mode.
- `TranscriptDocument` — rendered document plus metadata if needed.
- `TranscriptEntryRenderer` — shared message/activity/attachment/source-preview HTML rendering.
- `TranscriptResources` — classpath resource loading, script escaping, KaTeX font inlining.
- `TranscriptTheme` — generated CSS variables/theme values.
- `TranscriptAssetMode` — controls inline vs internal-URL asset tags.

Potential asset modes:

```java
enum TranscriptAssetMode {
    INLINE_ALL,
    INTERNAL_URL_FOR_LARGE_LIBRARIES
}
```

System WebView can use inline assets. JCEF should keep internal URLs for large Mermaid/SmilesDrawer bundles:

```text
https://chat4j.local/assets/mermaid/mermaid.min.js
https://chat4j.local/assets/smilesdrawer/smiles-drawer.min.js
```

## Engine boundaries

`SystemWebView` and `JcefBrowserView` should keep only engine-specific responsibilities:

- Swing component creation and disposal.
- Full-document loading.
- JavaScript evaluation.
- Java callback/action plumbing.
- Engine-specific URL/resource handling.
- JCEF supplementary Unicode encoding.
- JCEF `chat4j.local` request handling.

Shared renderer/resources should own:

- transcript document template
- structural CSS
- generated CSS variables
- transcript action bridge JS
- math render JS
- diagram render JS
- common entry/message HTML
- attachment/source-preview rendering
- asset tag generation

Avoid an abstract superclass unless duplication remains after shared renderer extraction. Composition should keep engine behavior easier to reason about.

## Template strategy

Use a simple resource template, not a template engine.

Example placeholders:

```html
{{asset-tags}}
{{theme-css}}
{{transcript-css}}
{{entries-html}}
{{chrome-html}}
{{runtime-scripts}}
```

A small Java helper should replace fixed tokens and fail fast if any token remains unresolved.

CSS should move toward variables:

```css
:root {
  --chat4j-bg: #...;
  --chat4j-text: #...;
  --chat4j-border: #...;
}
```

Static CSS in `transcript.css` should use variables instead of many Java `.formatted(...)` placeholders.

## Implementation status

Completed:

- Added `.editorconfig` rules and followed 2-space indentation for JS resources.
- Moved shared transcript JavaScript to `src/main/resources/web/chat/`.
- Added shared resource and asset helpers under `webview/shared`.
- Kept System WebView inline browser assets and JCEF internal URLs for large Mermaid/SmilesDrawer bundles.
- Moved attachment HTML/thumbnail rendering into shared renderer code.
- Introduced `transcript-document.html` and `transcript.css` under `src/main/resources/web/chat/`.
- Added fixed-token template resolution with unresolved-token validation.
- Added `TranscriptDocumentRenderer` for full document assembly and chrome/theme CSS generation.
- Added `TranscriptEntryRenderer` for shared message/activity/source-link rendering and server-side math/code enhancement.
- Added shared `TranscriptChrome` and `TranscriptRenderSnapshot` records.
- Thinned `SystemWebView` and `JcefBrowserView` so they focus on lifecycle, loading/eval, callbacks, and engine-specific URL handling.
- Reworked `transcript.css` to use generated CSS custom properties instead of a large positional `.formatted(...)` argument list.
- Added `TranscriptUpdateScripts` for shared incremental transcript/jump-button JavaScript used by both WebView engines.
- Wired `TranscriptAssetMode` into head asset generation so System WebView and JCEF choose their library loading mode explicitly.
- Added `TranscriptCallbackPayloads` for shared WebView callback argument/action parsing.
- Removed test-only renderer passthrough methods from the engine classes; tests now call shared renderer APIs directly.
- Added required-resource loading for bundled transcript/browser assets so missing resources fail with clear errors.
- Added `TranscriptRenderSupport` for shared snapshot creation and font-scope rendering.
- Inlined shared font-scope calls in both engines, leaving engine classes focused on lifecycle/loading/eval/callback behavior.
- Added `TranscriptDocumentRequest` so full-document rendering receives scroll behavior, snapshot, and asset mode as one immutable request.
- Moved head asset selection into `TranscriptDocumentRenderer` using the request asset mode.
- Added shared scroll-to-bottom JavaScript through `TranscriptUpdateScripts` and wired both engines to use it.
- Moved shared renderer behavior tests out of `SystemWebViewTest` into `TranscriptDocumentRendererTest`.
- Removed remaining test-only asset/bridge passthrough methods from `SystemWebView` and `JcefBrowserView`; shared asset/bridge assertions now live in shared tests.

Remaining follow-up:

- Manual UI validation across System WebView and JCEF.
- Future readability cleanup can split very large CSS sections further if they become hard to maintain.

## Migration steps

### 1. Extract identical JS resources

Move these first because they are currently identical between engines:

- `bridgeScript()` -> `transcript-actions.js`
- `mathRenderScript()` -> `math-render.js`
- `diagramRenderScript()` -> `diagram-render.js`

Keep Java methods with their current names temporarily and have them return resource text. This limits test churn and preserves public/test helper entry points.

### 2. Extract shared resource helper

Create `TranscriptResources` for:

- `resourceText(...)`
- `resourceDataUri(...)`
- `safeScriptContent(...)`
- `inlineStylesheetFonts(...)`
- KaTeX/Mermaid/SmilesDrawer resource constants where appropriate

Update both engines to use it.

### 3. Extract shared entry rendering

Move common HTML rendering to `TranscriptEntryRenderer`:

- standard assistant/user rows
- activity rows
- attachment strips
- image/file attachment cards
- source preview cards
- raw Markdown view
- syntax highlighting hook

Keep tests around escaping, masking, attachment attributes, and raw Markdown rendering.

### 4. Extract CSS and document template

Move stable transcript CSS into `transcript.css`.

Generate theme-specific CSS variables from Java and inject them into `transcript-document.html`.

Then introduce `TranscriptDocumentRenderer` to assemble:

- asset tags
- theme variables
- static CSS
- entries HTML
- transcript menu/jump button/source preview chrome
- runtime scripts

### 5. Thin engine classes

After shared rendering is stable, leave `SystemWebView` and `JcefBrowserView` focused on:

- scheduling
- component readiness
- loading rendered documents
- incremental transcript updates
- callback dispatch
- disposal
- JCEF request handling

## Test plan

Run after each slice:

```bash
mvn -q -Dtest=TranscriptBrowserAssetsTest,TranscriptDocumentRendererTest test
git diff --check
```

Before final commit:

```bash
mvn -q test
git diff --check
mvn -q -DskipTests package
```

Add/update tests for:

- resource-backed JS contains expected bridge functions.
- System WebView still inlines required scripts.
- JCEF still uses internal URLs for large Mermaid/SmilesDrawer assets.
- rendered transcript document includes expected CSS variables and chrome.
- entry rendering is identical or intentionally changed.
- invalid/missing resource failures are clear.

## Manual validation

Check both System WebView and JCEF:

- normal chat transcript rendering
- streaming updates
- conversation switch and clear chat
- copy selected text
- code copy button
- regenerate action
- attachment open/copy behavior
- source preview hover
- jump-to-latest button
- Mermaid rendering and external open
- SMILES/MOL/SDF rendering and error fallback
- math rendering
- light/dark theme changes

## Non-goals

- No Maven polyglot source layout unless future tooling needs it.
- No template engine dependency.
- No change to transcript behavior as part of the extraction.
- No new WebView engine abstraction beyond what is needed to remove duplication.
