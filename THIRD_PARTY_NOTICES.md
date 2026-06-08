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
- Bundled assets:
  - `src/main/resources/icons/settings/java-original.svg`
  - `src/main/resources/icons/settings/apple-original.svg`
  - `src/main/resources/icons/settings/windows11-original.svg`
  - `src/main/resources/icons/settings/linux-original.svg`

The Devicon license text is included at:

```text
src/main/resources/icons/settings/devicon-LICENSE
```

Java, Apple, Windows, and Linux marks are trademarks of their respective owners. The bundled icons identify renderer/platform choices and do not imply endorsement.

## Browser Logos

- Project: https://github.com/alrra/browser-logos
- License: MIT
- Bundled assets:
  - `src/main/resources/icons/settings/chromium-logo.svg`
  - `src/main/resources/icons/settings/microsoft-edge-logo.svg`
  - `src/main/resources/icons/settings/safari-logo.svg`

The Browser Logos license text is included at:

```text
src/main/resources/icons/settings/browser-logos-LICENSE
```

Chromium, Microsoft Edge, and Safari are trademarks of their respective owners. The bundled icons identify the selected rendering engine and do not imply endorsement.

## WebKit Logo

- Project: https://webkit.org/
- Source: https://commons.wikimedia.org/wiki/File:WebKit_logo.svg
- Bundled asset: `src/main/resources/icons/settings/webkit-logo.svg`

WebKit is open source software with portions licensed under LGPL and BSD licenses. The WebKit name and logo may be protected as trademarks. Chat4J uses the icon only to identify the Linux System WebView backend, which is WebKitGTK, and does not imply endorsement by Apple, WebKit, or the WebKitGTK project.

## GraalJS Community / GraalVM Polyglot 24.2.1

- Project: https://www.graalvm.org/javascript/
- Maven coordinates:
  - `org.graalvm.polyglot:polyglot`
  - `org.graalvm.polyglot:js-community`
- Licenses declared by the community JavaScript artifacts: Universal Permissive License (UPL), Version 1.0 and MIT License
- Notable transitive license: `org.graalvm.shadowed:icu4j` declares the Unicode/ICU License

Chat4J uses GraalJS Community to server-render KaTeX and Highlight.js HTML before the transcript is handed to SwingWebView. This avoids depending on native WebView script execution for math and syntax highlighting.
