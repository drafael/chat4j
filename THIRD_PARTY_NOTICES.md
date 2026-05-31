# Third-Party Notices

Chat4J bundles the following third-party browser assets for offline SwingWebView rendering.

## KaTeX 0.17.0

- Project: https://katex.org/
- Source: https://github.com/KaTeX/KaTeX
- License: MIT
- Bundled assets:
  - `src/main/resources/web/katex/katex.min.css`
  - `src/main/resources/web/katex/katex.min.js`
  - `src/main/resources/web/katex/fonts/*`

The KaTeX license text is included at:

```text
src/main/resources/web/katex/LICENSE
```

## KaTeX mhchem extension 0.17.0

- Source: https://github.com/KaTeX/KaTeX/tree/main/contrib/mhchem
- Bundled asset: `src/main/resources/web/katex/contrib/mhchem.min.js`

The KaTeX mhchem extension is distributed with KaTeX. The extension notes that it adapts MathJax/mhchem code. The upstream MathJax/mhchem Apache-2.0 notice is included at:

```text
src/main/resources/web/katex/contrib/mhchem-NOTICE.txt
```

## Highlight.js 11.11.1

- Project: https://highlightjs.org/
- Source: https://github.com/highlightjs/highlight.js
- License: BSD 3-Clause
- Bundled asset: `src/main/resources/web/highlight/highlight.min.js`

The Highlight.js license text is included at:

```text
src/main/resources/web/highlight/LICENSE
```

## Devicon 2.16.0

- Project: https://devicon.dev/
- Source: https://github.com/devicons/devicon
- License: MIT
- Bundled asset: `src/main/resources/icons/settings/java-original.svg`

The Devicon license text is included at:

```text
src/main/resources/icons/settings/devicon-LICENSE
```

Java is a trademark of Oracle and/or its affiliates. The bundled icon is used only to identify the Java/Swing fallback renderer and does not imply endorsement.

## GraalJS Community / GraalVM Polyglot 24.2.1

- Project: https://www.graalvm.org/javascript/
- Maven coordinates:
  - `org.graalvm.polyglot:polyglot`
  - `org.graalvm.polyglot:js-community`
- Licenses declared by the community JavaScript artifacts: Universal Permissive License (UPL), Version 1.0 and MIT License
- Notable transitive license: `org.graalvm.shadowed:icu4j` declares the Unicode/ICU License

Chat4J uses GraalJS Community to server-render KaTeX and Highlight.js HTML before the transcript is handed to SwingWebView. This avoids depending on native WebView script execution for math and syntax highlighting.
