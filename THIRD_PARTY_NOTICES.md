# Third-Party Notices

Chat4J bundles the following third-party browser assets for offline chat transcript rendering in System WebView and JCEF, plus selected Java/native runtime dependencies used by desktop features.

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

## Mermaid 11.12.0

- Project: https://mermaid.js.org/
- Source: https://github.com/mermaid-js/mermaid
- License: MIT
- Bundled asset: `src/main/resources/web/mermaid/mermaid.min.js`

The Mermaid license text is included at:

```text
src/main/resources/web/mermaid/LICENSE
```

## SmilesDrawer 2.2.1

- Project: https://github.com/reymond-group/smilesDrawer
- License: MIT
- Bundled asset: `src/main/resources/web/smilesdrawer/smiles-drawer.min.js`

The SmilesDrawer license text is included at:

```text
src/main/resources/web/smilesdrawer/LICENSE
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

Chat4J uses GraalJS Community to server-render KaTeX and Highlight.js HTML before the transcript is handed to a browser-backed conversation view. This keeps math and syntax highlighting reliable across System WebView and JCEF.

## Vosk Java/native runtime 0.3.38

- Project: https://alphacephei.com/vosk/
- Source: https://github.com/alphacep/vosk-api
- Maven coordinates: `com.alphacephei:vosk`
- License: Apache License 2.0
- Bundled runtime resources include platform native Vosk libraries from the Maven artifact for desktop local Speech to Text.

Chat4J bundles the Vosk Java/native runtime only. Chat4J does **not** bundle Vosk speech models. Users download or import models themselves, and model licenses vary by model. See the official model page for model-specific details:

```text
https://alphacephei.com/vosk/models
```

## CMU Sphinx4 runtime 5prealpha

- Project: https://cmusphinx.github.io/
- Source: https://sourceforge.net/projects/cmusphinx/
- Maven coordinates: `net.sf.phat:sphinx4-core`
- License declared by the Maven artifact: BSD-style CMU Sphinx license

Chat4J bundles the Sphinx4 Java runtime only. Chat4J does **not** bundle Sphinx4 acoustic models, pronunciation dictionaries, or language models. Users may download catalog entries or import models themselves, and model licenses vary by package. The bundled Sphinx4 catalog is metadata only, including recipe paths for user-triggered SourceForge downloads that run only when the user starts a download.

## Apache Commons Compress

- Project: https://commons.apache.org/proper/commons-compress/
- Source: https://github.com/apache/commons-compress
- Maven coordinates: `org.apache.commons:commons-compress`
- License: Apache License 2.0

Chat4J uses Commons Compress to safely inspect and extract Sphinx4 ZIP, TAR/GZIP, and TAR/XZ model archives during user-triggered Sphinx4 model downloads.

## XZ for Java

- Project: https://tukaani.org/xz/java.html
- Source: https://github.com/tukaani-project/xz-java
- Maven coordinates: `org.tukaani:xz`
- License: Public domain

Chat4J uses XZ for Java through Commons Compress to extract the German Sphinx4 catalog archive, which is distributed by SourceForge as XZ-compressed TAR data.
