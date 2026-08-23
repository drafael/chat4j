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

## GraalJS Community / GraalVM Polyglot 25.1.3

- Project: https://www.graalvm.org/javascript/
- Maven coordinates:
  - `org.graalvm.polyglot:polyglot`
  - `org.graalvm.polyglot:js-community`
- Licenses declared by the community JavaScript artifacts: Universal Permissive License (UPL), Version 1.0 and MIT License
- Notable transitive license: `org.graalvm.shadowed:icu4j` declares the Unicode/ICU License

Chat4J uses GraalJS Community to server-render KaTeX and Highlight.js HTML before the transcript is handed to a browser-backed conversation view. This keeps math and syntax highlighting reliable across System WebView and JCEF.

## OpenHTMLtoPDF 1.1.73

- Project: https://github.com/openhtmltopdf/openhtmltopdf
- Maven coordinates:
  - `io.github.openhtmltopdf:openhtmltopdf-core`
  - `io.github.openhtmltopdf:openhtmltopdf-pdfbox`
- License: GNU Lesser General Public License 2.1 or later
- Notable transitive component: `de.rototor.pdfbox:graphics2d` 3.0.1 (Apache License 2.0)

Chat4J uses OpenHTMLtoPDF with Apache PDFBox for its offline, built-in conversation PDF exporter. The OpenHTMLtoPDF core and PDFBox adapter artifacts are distributed as separate sibling JARs rather than folded into Chat4J's shaded application JAR, so recipients can inspect or replace them. The LGPL 2.1 license text is included at:

```text
src/main/resources/licenses/OpenHTMLtoPDF-LGPL-2.1.txt
```

## jSVG 2.1.0

- Project: https://github.com/weisJ/jsvg
- Maven coordinates: `com.github.weisj:jsvg`
- License: MIT

Chat4J uses jSVG with external resource loading disabled to rasterize application-generated SmilesDrawer SVG diagrams for offline Standard and Publication PDF exports. The license text is included at:

```text
src/main/resources/licenses/jSVG-MIT-2.1.0.txt
```

## Libertinus fonts

- Project: https://github.com/alerque/libertinus
- Distribution source: https://github.com/google/fonts/tree/main/ofl
- License: SIL Open Font License 1.1
- Bundled families: Libertinus Serif and Libertinus Sans
- Bundled assets: `src/main/resources/web/export/pdf/fonts/Libertinus*.ttf`

The font license texts are included beside the font files as `OFL-libertinus*.txt`.

## JetBrains Mono 2.304

- Project: https://github.com/JetBrains/JetBrainsMono
- Distribution source: https://github.com/JetBrains/JetBrainsMono/tree/v2.304/fonts/ttf
- License: SIL Open Font License 1.1
- Bundled styles: Regular, Bold, Italic, and Bold Italic
- Bundled assets: `src/main/resources/web/export/pdf/fonts/JetBrainsMono-*.ttf`

Chat4J uses JetBrains Mono for inline code and fenced code blocks in Standard and Publication PDF exports. The font license is included beside the font files as `OFL-JetBrainsMono.txt`.

## Noto Sans

- Project: https://fonts.google.com/noto
- Source: https://github.com/google/fonts/tree/main/ofl/notosans
- License: SIL Open Font License 1.1
- Bundled asset: `src/main/resources/web/export/pdf/fonts/NotoSans.ttf`

Noto Sans provides embedded fallback glyphs for Latin, Greek, Cyrillic, Devanagari, and other scripts covered by this font file. It does not provide complete CJK, Arabic, or emoji coverage. The font license is included at:

```text
src/main/resources/web/export/pdf/fonts/OFL-NotoSans.txt
```

## Noto Emoji

- Project: https://fonts.google.com/noto/specimen/Noto+Emoji
- Source: https://github.com/google/fonts/tree/main/ofl/notoemoji
- License: SIL Open Font License 1.1
- Bundled asset: `src/main/resources/web/export/pdf/fonts/NotoEmoji.ttf`

Chat4J uses the monochrome Noto Emoji font to preserve emoji and pictographic symbols in Standard and Publication PDF exports. The font license is included at:

```text
src/main/resources/web/export/pdf/fonts/OFL-NotoEmoji.txt
```

## TwelveMonkeys ImageIO WebP 3.13.0

- Project: https://github.com/haraldk/TwelveMonkeys
- Maven coordinates: `com.twelvemonkeys.imageio:imageio-webp`
- License: BSD 3-Clause
- Copyright: TwelveMonkeys contributors

Chat4J uses the ImageIO service provider to inspect and decode bounded WebP image attachments. The shaded application merges ImageIO service registrations through Maven Shade's `ServicesResourceTransformer`.

The TwelveMonkeys BSD 3-Clause terms are included at:

```text
src/main/resources/licenses/TwelveMonkeys-BSD-3-Clause-3.13.0.txt
```

## whisper-jni / whisper.cpp runtime 0.5.5

- Project: https://github.com/FreshSupaSulley/whisper-jni
- Upstream native engine: https://github.com/ggml-org/whisper.cpp
- Maven coordinates: `io.github.freshsupasulley:whisper-jni`
- Licenses declared by the wrapper/upstream projects: MIT
- Bundled runtime resources include platform native whisper.cpp/ggml libraries from the Maven artifact for desktop local Speech to Text.
- The `whisper-jni` Maven artifact also bundles `ggml-silero-v5.1.2.bin`, a ggml-converted Silero VAD model asset from the `ggml-org/whisper-vad` Hugging Face repository (`license: mit`) derived from Silero VAD (MIT License). Chat4J does not expose this bundled VAD asset as a selectable transcription model.

Chat4J bundles the Whisper.cpp Java/native runtime only. Chat4J does **not** bundle Whisper transcription models. Users download official whisper.cpp ggml model files from Hugging Face through Chat4J's model manager. Model files derive from OpenAI Whisper distribution and Hugging Face-hosted artifacts; model terms may vary by artifact/source repository.

Most model downloads use:

```text
https://huggingface.co/ggerganov/whisper.cpp
```

The `small.en-tdrz` entry follows the upstream whisper.cpp script exception:

```text
https://huggingface.co/akashmjn/tinydiarize-whisper.cpp
```

The bundled `ggml-silero-v5.1.2.bin` VAD asset is treated as a dependency runtime asset and is not exposed as a Chat4J Whisper transcription model in v1.

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
