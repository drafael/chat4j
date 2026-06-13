package com.github.drafael.chat4j.chat.conversation.webview.shared;

import com.github.drafael.chat4j.chat.conversation.ConversationEntry;
import com.github.drafael.chat4j.chat.conversation.ConversationEntryKind;
import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.provider.api.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TranscriptBrowserAssetsTest {

    @Test
    @DisplayName("Render support builds snapshots and applies code font scope")
    void transcriptRenderSupport_whenSnapshotCreated_containsThemeAndFontState() {
        var entries = List.of(new ConversationEntry(
                ConversationEntryKind.MESSAGE,
                Role.ASSISTANT,
                "hello",
                "",
                false,
                1,
                List.of()
        ));

        TranscriptRenderSnapshot snapshot = TranscriptRenderSupport.snapshot(entries, RenderMode.PREVIEW, false, true);
        String value = TranscriptRenderSupport.withSnapshotFonts(snapshot, () -> "rendered");

        assertThat(snapshot.entries()).containsExactlyElementsOf(entries);
        assertThat(snapshot.renderMode()).isEqualTo(RenderMode.PREVIEW);
        assertThat(snapshot.jumpButtonVisible()).isTrue();
        assertThat(snapshot.palette()).isNotNull();
        assertThat(snapshot.chrome()).isNotNull();
        assertThat(snapshot.codeFontSize()).isPositive();
        assertThat(value).isEqualTo("rendered");
    }

    @Test
    @DisplayName("Document renderer request controls scroll behavior and asset mode")
    void renderDocument_whenRequestUsesInternalAssetMode_containsInternalUrlsAndScrollScript() {
        TranscriptRenderSnapshot snapshot = TranscriptRenderSupport.snapshot(
                List.of(ConversationEntry.message(Role.ASSISTANT, "hello", 2)),
                RenderMode.PREVIEW,
                false,
                false
        );
        String html = new TranscriptDocumentRenderer().renderDocument(new TranscriptDocumentRequest(
                true,
                snapshot,
                TranscriptAssetMode.INTERNAL_URL_FOR_LARGE_LIBRARIES,
                "https://chat4j.local/assets/mermaid/mermaid.min.js",
                "https://chat4j.local/assets/smilesdrawer/smiles-drawer.min.js"
        ));

        assertThat(html)
                .contains("src=\"https://chat4j.local/assets/mermaid/mermaid.min.js\"")
                .contains("src=\"https://chat4j.local/assets/smilesdrawer/smiles-drawer.min.js\"")
                .contains("--chat4j-bg")
                .contains("--chat4j-code-header-bg")
                .contains("--chat4j-mermaid-canvas-bg: var(--chat4j-code-bg)")
                .contains("--chat4j-mermaid-primary-bg: var(--chat4j-menu-bg)")
                .contains("--chat4j-mermaid-secondary-bg: var(--chat4j-inline-code-bg)")
                .contains("--chat4j-mermaid-tertiary-bg: var(--chat4j-code-header-bg)")
                .contains("--chat4j-mermaid-edge-label-bg: var(--chat4j-code-bg)")
                .contains("--chat4j-mermaid-border: var(--chat4j-scrollbar-thumb)")
                .contains("--chat4j-mermaid-line: var(--chat4j-muted-text)")
                .contains("--chat4j-mermaid-text: var(--chat4j-text)")
                .contains(".transcript")
                .contains(".message.assistant")
                .contains(".chat4j-diagram")
                .contains(".source-preview")
                .contains(".jump-button")
                .contains("window.scrollTo")
                .contains("hello");
    }

    @Test
    @DisplayName("Document request masks asset URLs in string output")
    void transcriptDocumentRequest_whenToStringCalled_masksAssetUrls() {
        TranscriptRenderSnapshot snapshot = TranscriptRenderSupport.snapshot(List.of(), RenderMode.PREVIEW, false, false);

        var request = new TranscriptDocumentRequest(
                false,
                snapshot,
                TranscriptAssetMode.INTERNAL_URL_FOR_LARGE_LIBRARIES,
                "https://chat4j.local/assets/mermaid/mermaid.min.js",
                "https://chat4j.local/assets/smilesdrawer/smiles-drawer.min.js"
        );

        assertThat(request)
                .hasToString("TranscriptDocumentRequest[scrollToBottom=false, snapshot=%s, assetMode=INTERNAL_URL_FOR_LARGE_LIBRARIES, mermaidScriptUrl=<masked>, smilesDrawerScriptUrl=<masked>]".formatted(snapshot));
    }

    @Test
    @DisplayName("Browser asset mode controls whether large diagram libraries are inline or URL backed")
    void headAssets_whenAssetModeChanges_switchesLargeDiagramLibraryLoading() {
        String inline = TranscriptBrowserAssets.headAssets(TranscriptAssetMode.INLINE_ALL, "", "");
        String internalUrl = TranscriptBrowserAssets.headAssets(
                TranscriptAssetMode.INTERNAL_URL_FOR_LARGE_LIBRARIES,
                "https://chat4j.local/assets/mermaid/mermaid.min.js",
                "https://chat4j.local/assets/smilesdrawer/smiles-drawer.min.js"
        );

        assertThat(inline)
                .contains("chat4j-katex-css")
                .contains("data:font/woff2;base64,")
                .contains("chat4j-mermaid-script")
                .contains("chat4j-smiles-drawer-script")
                .contains("chat4j-diagram-render-script")
                .contains("chat4jRenderEnhancements")
                .contains("mermaid")
                .doesNotContain("cdn.jsdelivr")
                .doesNotContain("unpkg.com")
                .doesNotContain("src=\"https://chat4j.local/assets/mermaid/mermaid.min.js\"");
        assertThat(internalUrl)
                .contains("src=\"https://chat4j.local/assets/mermaid/mermaid.min.js\"")
                .contains("src=\"https://chat4j.local/assets/smilesdrawer/smiles-drawer.min.js\"")
                .contains("chat4j-diagram-render-script")
                .doesNotContain("globalThis[\"mermaid\"]")
                .doesNotContain("window.SmilesDrawer=");
    }

    @Test
    @DisplayName("Bundled math and diagram bridge contains renderers and failure fallbacks")
    void mathBridgeScript_whenRendered_containsBundledRenderersActionsAndFallbacks() {
        String script = normalizeNewlines(TranscriptBrowserAssets.mathBridgeScript());

        assertThat(script)
                .contains("katex")
                .contains("mhchem")
                .contains("mermaid")
                .contains("SmilesDrawer")
                .contains("scale: 1.35")
                .contains("parseMolV2000")
                .contains("renderMolLikeBlock")
                .contains("SDF_MAX_RECORDS = 12")
                .contains("Showing first ")
                .contains("chat4jRenderMath")
                .contains("chat4jRenderDiagrams")
                .contains("chat4jRenderEnhancements")
                .contains("open-diagram-html")
                .contains("XMLSerializer")
                .contains("chat4j-mermaid-display")
                .contains("diagram-open-button")
                .contains("window.chat4jDispatchTranscriptAction('open-diagram-html', -1, payload)")
                .contains("window.chat4jOpenMermaidDiagram = openMermaidDiagram")
                .contains("Open diagram")
                .contains("table.insertRow(0)")
                .contains("chat4j-diagram-error-badge")
                .contains("target.parentNode.replaceChild(originalNode, target)")
                .contains("window.mermaid.parse(candidate)")
                .contains("repairMermaidSource(source)")
                .contains("renderSource(repaired, '-repaired')")
                .contains("mermaidErrorSvg(svg)")
                .contains("friendlyDiagramError")
                .contains("Mermaid syntax error — source shown below")
                .contains("Mermaid renderer unavailable")
                .contains("Mermaid render timed out")
                .contains("SMILES renderer unavailable")
                .contains("SMILES render failed")
                .contains("MOL must be complete V2000 source — source shown below")
                .contains("SDF must be complete V2000 source — source shown below")
                .contains("chat4j-chem-record-summary")
                .contains("text = cssColor('--chat4j-mermaid-text', bodyColor('currentColor'))")
                .contains("diagramBackground = cssColor('--chat4j-mermaid-canvas-bg', cssColor('--chat4j-code-bg', background))")
                .contains("primarySurface = cssColor('--chat4j-mermaid-primary-bg', cssColor('--chat4j-menu-bg', diagramBackground))")
                .contains("secondarySurface = cssColor('--chat4j-mermaid-secondary-bg', cssColor('--chat4j-inline-code-bg', primarySurface))")
                .contains("tertiarySurface = cssColor('--chat4j-mermaid-tertiary-bg', cssColor('--chat4j-code-header-bg', secondarySurface))")
                .contains("border = cssColor('--chat4j-mermaid-border', cssColor('--chat4j-scrollbar-thumb', cssColor('--chat4j-border', text)))")
                .contains("line = cssColor('--chat4j-mermaid-line', cssColor('--chat4j-muted-text', text))")
                .contains("edgeLabelBackground = cssColor('--chat4j-mermaid-edge-label-bg', diagramBackground)")
                .contains("readableColor(line, text, diagramBackground)")
                .contains("sequenceLine = readableColor(diagramBackground, line, text)")
                .contains("sequenceSurface = readableColor(diagramBackground, secondarySurface, tertiarySurface)")
                .contains("sequenceLabelSurface = readableColor(diagramBackground, primarySurface, secondarySurface)")
                .contains("MERMAID_CATEGORY_COLOR_COUNT = 12")
                .contains("mermaidCategoricalThemeVariables(colors)")
                .contains("colors.tertiarySurface\n        ];")
                .contains("variables['cScale' + index] = surface")
                .contains("variables['cScaleLabel' + index] = colors.text")
                .contains("variables['lineColor' + index] = colors.line")
                .contains("variables['git' + index] = colors.line")
                .contains("variables['gitBranchLabel' + index] = colors.branchLabelText")
                .contains("mermaidQuadrantThemeVariables(colors)")
                .contains("quadrant1Fill: colors.secondarySurface")
                .contains("quadrantPointFill: colors.line")
                .contains("quadrantTitleFill: colors.text")
                .contains("primaryColor: primarySurface")
                .contains("secondaryColor: secondarySurface")
                .contains("tertiaryColor: tertiarySurface")
                .contains("lineColor: line")
                .contains("actorBkg: sequenceSurface")
                .contains("actorBorder: sequenceLine")
                .contains("actor0: sequenceLine")
                .contains("actor1: sequenceLine")
                .contains("signalColor: sequenceLine")
                .contains("labelBoxBkgColor: sequenceLabelSurface")
                .contains("activationBorderColor: sequenceLine")
                .contains("edgeLabelBackground: edgeLabelBackground")
                .contains("attributeBackgroundColorOdd: secondarySurface")
                .contains("taskBkgColor: primarySurface")
                .contains("critBkgColor: primarySurface")
                .contains("fillType0: primarySurface")
                .contains("branchLabelColor: branchLabelText")
                .contains("commitLabelColor: text")
                .contains("commitLabelBackground: edgeLabelBackground")
                .contains("tagLabelBackground: secondarySurface")
                .contains("appendMermaidThemeStyle(renderedSvg)")
                .contains("replaceSource(table, target);\n                applyReadableNodeLabelTheme(renderedSvg)")
                .contains("applyMindmapLabelTheme(renderedSvg)")
                .contains("data-chat4j-mermaid-theme")
                .contains("svg path.flowchart-link")
                .contains("svg .edgeLabel, svg .edgeLabel p")
                .contains("svg .edgeLabel text, svg .edgeLabel tspan")
                .contains("svg .mindmap-node-label, svg .mindmap-node-label div, svg .mindmap-node-label span")
                .contains("svg rect.actor, svg .actor-box")
                .contains("svg .actor-line, svg .messageLine0, svg .messageLine1")
                .contains("svg #arrowhead path, svg #crosshead path { fill: ")
                .contains("svg .labelBox, svg .note")
                .contains("svg .activation0, svg .activation1, svg .activation2")
                .contains("svg .mindmap-node .nodeLabel, svg .mindmap-node .nodeLabel span")
                .contains("svg .mindmap-node .text-inner-tspan, svg .mindmap-node .text-outer-tspan")
                .contains("svg .mindmap-node.section-root rect")
                .contains("svg .branchLabel text, svg .branchLabel tspan")
                .contains("svg .commit-merge, svg .commit-reverse, svg .commit-highlight-inner")
                .contains("function applyReadableNodeLabelTheme(svg)")
                .contains("svg.querySelectorAll('g.node')")
                .contains("mermaidNodeFillColor(node)")
                .contains("readableColor(fill, colors.text, colors.diagramBackground)")
                .contains("setImportantColor(label, labelColor)")
                .contains("svg.querySelectorAll('.mindmap-node-label, .mindmap-node-label *, .mindmap-node .nodeLabel")
                .doesNotContain("svg text, svg tspan")
                .doesNotContain("svg .edgeLabel, svg .edgeLabel p, svg .label, svg .nodeLabel")
                .doesNotContain("svg .er.attributeBoxEven")
                .doesNotContain("svg .task, svg .task0")
                .doesNotContain("svg [class*='mindmap'] rect")
                .doesNotContain("svg .git0, svg .git1")
                .contains("code.md-latex-inline:not([data-chat4j-math-rendered])")
                .contains("table.md-latex-block:not([data-chat4j-math-rendered])")
                .contains("throwOnError: false")
                .contains("trust: false")
                .doesNotContain("cdn.jsdelivr")
                .doesNotContain("unpkg.com");
    }

    private static String normalizeNewlines(String value) {
        return value.replace("\r\n", "\n");
    }

    @Test
    @DisplayName("Transcript action bridge exposes dispatch and Mermaid context menu actions")
    void transcriptActionsScript_whenRendered_exposesDiagramActionDispatcher() {
        String script = TranscriptBrowserAssets.transcriptActionsScript();

        assertThat(script)
                .contains("window.chat4jDispatchTranscriptAction = dispatchTranscriptAction")
                .contains("window.chat4jOpenMermaidDiagram(menu._chat4jDiagram)")
                .contains("data-action=\"open-diagram\"")
                .contains("Open Diagram");
    }

    @Test
    @DisplayName("Callback payload parsing supports WebView callback shapes")
    void transcriptCallbackPayloads_whenCallbackShapesDiffer_extractsValues() {
        assertThat(TranscriptCallbackPayloads.callbackArg("{\"args\":[\"https://example.test\"]}"))
                .isEqualTo("https://example.test");
        assertThat(TranscriptCallbackPayloads.callbackArg("[\"plain\"]"))
                .isEqualTo("plain");
        assertThat(TranscriptCallbackPayloads.callbackArg("\"legacy\""))
                .isEqualTo("legacy");

        TranscriptCallbackPayloads.TranscriptAction action = TranscriptCallbackPayloads.transcriptAction(
                "{\"args\":[\"copy\",7,\"secret text\"]}"
        );

        assertThat(action.action()).isEqualTo("copy");
        assertThat(action.messageIndex()).isEqualTo(7);
        assertThat(action.text()).isEqualTo("secret text");
        assertThat(action).hasToString("TranscriptAction[action=copy, messageIndex=7, text=<masked>]");
    }

    @Test
    @DisplayName("Transcript update scripts centralize incremental WebView updates")
    void transcriptUpdateScripts_whenBuilt_escapeHtmlAndToggleJumpState() {
        String script = TranscriptUpdateScripts.transcriptHtmlUpdate("<p>hello</p>", true, false);
        String jumpScript = TranscriptUpdateScripts.jumpButtonChrome(false);
        String scrollScript = TranscriptUpdateScripts.scrollToBottom();

        assertThat(script)
                .contains("document.querySelector('.transcript')")
                .contains("transcript.innerHTML = \"<p>hello</p>\"")
                .contains("window.chat4jRenderEnhancements")
                .contains("jump.classList.toggle('streaming', true)")
                .contains("if (false)");
        assertThat(jumpScript)
                .contains("jump.setAttribute('data-streaming', \"false\")")
                .contains("jump.classList.toggle('streaming', false)");
        assertThat(scrollScript)
                .contains("window.scrollTo")
                .contains("document.documentElement.scrollHeight");
    }

    @Test
    @DisplayName("Required resource loading fails clearly for missing resources")
    void requiredResourceText_whenResourceMissing_failsClearly() {
        assertThatThrownBy(() -> TranscriptResources.requiredResourceText("/web/chat/missing-resource.js"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Required transcript resource is missing")
                .hasMessageContaining("/web/chat/missing-resource.js");
    }

    @Test
    @DisplayName("Template resolution allows braces in injected CSS and HTML")
    void resolveTemplate_whenValuesContainTokenLikeCssContent_keepsInjectedContent() {
        String resolved = TranscriptResources.resolveTemplate(
                "<style>{{css}}</style><main>{{html}}</main>",
                Map.of(
                        "css", "@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }",
                        "html", "literal {{i}} in model output"
                )
        );

        assertThat(resolved)
                .contains("@keyframes spin")
                .contains("literal {{i}} in model output");
    }

    @Test
    @DisplayName("Template resolution fails when the template contains an unknown token")
    void resolveTemplate_whenTemplateHasUnknownToken_failsClearly() {
        assertThatThrownBy(() -> TranscriptResources.resolveTemplate("<main>{{missing}}</main>", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("{{missing}}");
    }

    @Test
    @DisplayName("Transcript document and CSS templates are bundled as chat resources")
    void transcriptTemplates_whenLoadedFromResources_containExpectedTokensAndStyles() {
        String documentTemplate = TranscriptResources.resourceText("/web/chat/transcript-document.html");
        String cssTemplate = TranscriptResources.resourceText("/web/chat/transcript.css");
        String layoutCss = TranscriptResources.resourceText("/web/chat/transcript-layout.css");
        String messageCss = TranscriptResources.resourceText("/web/chat/transcript-message-content.css");
        String diagramCss = TranscriptResources.resourceText("/web/chat/transcript-diagrams.css");
        String sourceCss = TranscriptResources.resourceText("/web/chat/transcript-sources.css");
        String jumpCss = TranscriptResources.resourceText("/web/chat/transcript-jump.css");

        assertThat(documentTemplate)
                .contains("{{asset-tags}}")
                .contains("{{transcript-css}}")
                .contains("{{entries-html}}")
                .contains("{{jump-streaming-class}}")
                .contains("{{jump-streaming}}")
                .contains("{{scroll-script}}");
        assertThat(cssTemplate)
                .contains("{{theme-css}}")
                .contains("{{layout-css}}")
                .contains("{{attachment-css}}")
                .contains("{{syntax-highlight-css}}")
                .contains("{{diagram-css}}")
                .contains("{{jump-css}}")
                .doesNotContain("%s")
                .doesNotContain("%d");
        assertThat(layoutCss)
                .contains(".transcript")
                .contains("var(--chat4j-bg)");
        assertThat(messageCss).contains(".message.assistant");
        assertThat(diagramCss)
                .contains(".chat4j-diagram")
                .contains(".chat4j-mermaid-display")
                .contains("var(--chat4j-mermaid-border)")
                .contains("var(--chat4j-mermaid-canvas-bg)");
        assertThat(sourceCss).contains(".source-preview");
        assertThat(jumpCss).contains(".jump-button");
    }

    @Test
    @DisplayName("Extracted diagram JavaScript keeps runtime regex escaping")
    void diagramRenderScript_whenLoadedFromResource_usesValidRuntimeRegexEscapes() {
        String script = TranscriptBrowserAssets.diagramRenderScript();

        assertThat(script)
                .contains("match(/rgba?\\((\\d+),\\s*(\\d+),\\s*(\\d+)/i)")
                .contains("split(/\\r?\\n/)")
                .doesNotContain("match(/rgba?\\\\((\\\\d+),\\\\s*(\\\\d+),\\\\s*(\\\\d+)/i)")
                .doesNotContain("split(/\\\\r?\\\\n/)");
    }

    @Test
    @DisplayName("Extracted math JavaScript keeps runtime delimiter escaping")
    void mathRenderScript_whenLoadedFromResource_usesValidRuntimeDelimiterEscapes() {
        String script = TranscriptBrowserAssets.mathRenderScript();

        assertThat(script)
                .contains("text.slice(0, 2) === '\\\\['")
                .contains("text.slice(0, 2) === '\\\\('")
                .doesNotContain("text.slice(0, 2) === '\\\\\\\\['")
                .doesNotContain("text.slice(0, 2) === '\\\\\\\\('");
    }
}
