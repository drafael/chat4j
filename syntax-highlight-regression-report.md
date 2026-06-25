# Syntax highlighting regression investigation

## Summary

Syntax highlighting is not broken by the Java/Markdown rendering changes in the two newest commits (`592ecfc` and `86563d2`) when the app is run from the normal Maven classpath. It breaks in the packaged/shaded application JAR because Graal/Truffle cannot initialize after the GraalJS dependency bump to `25.0.3`.

Both server-side syntax highlighting (`HighlightJsCodeRenderer`) and server-side KaTeX rendering (`KatexMathRenderer`) depend on GraalJS. In the fat JAR, Graal throws:

```text
java.lang.InternalError: Truffle could not be initialized because Multi-Release classes are not configured correctly.
This most likely means Truffle classes have been repackaged incorrectly and the `Multi-Release: true` attribute in META-INF/MANIFEST.MF has been lost.
A common cause of this error is invalid Uber JAR configuration.
```

The renderer catches this error and silently returns `Optional.empty()`, so code blocks remain plain fallback markup instead of receiving `hljs` classes/spans.

## Evidence

### 1. Recent commits reviewed

Recent relevant commits:

- `592ecfc` — `Render math fallback text`
  - Adds `MathFallbackTextRenderer`.
  - Changes `JEditorPaneMessageContentView.setHtml` to replace LaTeX fallback nodes.
  - Changes `TranscriptEntryRenderer.renderMathFallbacks` to render readable text when KaTeX cannot render.
- `86563d2` — `Fix numeric LaTeX rendering`
  - Updates dollar-math heuristics.
  - Adds `MarkdownMathHeuristics`.
- `202ceca` / `bea04c9` — GraalJS bump from `24.2.1` to `25.0.3`.

The code highlight path itself was not changed by the two latest feature commits:

- `TranscriptEntryRenderer.renderCodeHighlights(...)` was introduced earlier and is unchanged in the latest commits.
- It still selects `table.md-code-block:not(.md-latex-block):not(.md-diagram-block)`, reads the `<pre>` text, calls `HighlightJsCodeRenderer`, and adds `hljs language-*` classes when rendering succeeds.

### 2. Existing unit tests pass

Ran:

```bash
mvn -q -Dtest=HighlightJsCodeRendererTest,TranscriptDocumentRendererTest,MarkdownRendererTest test
```

Result: pass.

This explains why CI/tests may not catch the problem: unit tests run against `target/classes` plus dependency JARs, not against the shaded release JAR.

### 3. Exploded Maven classpath works

Using `target/classes` plus Maven dependencies, `HighlightJsCodeRenderer` successfully produces Highlight.js spans:

```html
<pre class="hljs language-java" data-chat4j-highlighted="server">
  <span class="hljs-keyword">class</span> ...
</pre>
```

### 4. Shaded JAR fails

Running the same smoke test from `target/chat4j-26.6.52.jar` leaves code plain:

```html
<pre style="margin: 0;"><font ...>class Demo { String value = "ok"; int n = 1; }</font></pre>
```

A direct Graal smoke test against the shaded JAR fails with the `Multi-Release` error shown above.

### 5. Root packaging issue confirmed

The shaded JAR manifest currently lacks:

```text
Multi-Release: true
```

`truffle-api-25.0.3.jar` does contain:

```text
Multi-Release: true
```

Manually adding `Multi-Release: true` to the shaded JAR manifest makes the same smoke test pass again: Graal initializes, syntax highlighting returns `hljs` spans, and KaTeX server rendering also works.

## Root cause

The fat JAR created by `maven-shade-plugin` repackages Graal/Truffle multi-release classes but does not preserve/set the `Multi-Release: true` manifest attribute.

With GraalJS `25.0.3`, Truffle validates this and fails initialization. Because `HighlightJsCodeRenderer.renderUncached` catches `Throwable` and returns `Optional.empty()`, the user-facing symptom is simply: code blocks render, but syntax colors are gone.

The likely regression-introducing commit is the GraalJS version bump (`bea04c9`, merged as `202ceca`), not the latest math fallback commits. The latest commits made another Graal-dependent path more visible because KaTeX fallback also silently downgrades to text in the packaged JAR.

## Recommended fixes

### Fix 1 — Preserve the multi-release manifest in the shaded JAR

Update the `maven-shade-plugin` `ManifestResourceTransformer` in `pom.xml`:

```xml
<transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
    <mainClass>com.github.drafael.chat4j.App</mainClass>
    <manifestEntries>
        <Multi-Release>true</Multi-Release>
    </manifestEntries>
</transformer>
```

Then rebuild:

```bash
mvn clean package
java -cp target/chat4j-26.6.52.jar ... # run a highlight smoke check
```

This is the smallest and most direct fix.

### Fix 2 — Add a release/package smoke test

Add a post-package verification that runs against `target/${project.build.finalName}.jar`, not only `target/classes`. It should assert:

1. `Context.newBuilder("js").build().eval("js", "1+2")` works.
2. `HighlightJsCodeRenderer.instance().render("class Demo {}", "java")` contains `hljs-keyword`.
3. A rendered transcript code block gets `pre.hljs.language-java`.

This would catch future package-only Graal regressions.

### Fix 3 — Log renderer initialization failures

Both renderers currently hide the real cause:

- `HighlightJsCodeRenderer.renderUncached`
- `KatexMathRenderer.renderUncached`

Add debug/warn logging before marking the renderer unavailable, for example:

```java
log.warn("Highlight.js server-side rendering is unavailable: {}", t.toString());
```

This will make packaged-runtime regressions visible in logs instead of appearing as a CSS/UI issue.

### Temporary workaround only

Starting the app with this JVM option also bypasses the Truffle check:

```text
-Dpolyglotimpl.DisableMultiReleaseCheck=true
```

This should not be the permanent fix; it only masks the incorrect shaded-JAR metadata.

## Suggested priority

1. Add `Multi-Release: true` to the shaded JAR manifest.
2. Rebuild and verify the packaged JAR/app bundle.
3. Add a packaged-JAR smoke test.
4. Add warning logs for Graal-dependent renderer failures.
