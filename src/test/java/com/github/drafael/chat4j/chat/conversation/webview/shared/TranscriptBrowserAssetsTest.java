package com.github.drafael.chat4j.chat.conversation.webview.shared;

import com.github.drafael.chat4j.chat.conversation.ConversationEntry;
import com.github.drafael.chat4j.chat.conversation.ConversationEntryKind;
import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.provider.api.Role;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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
    @DisplayName("Inline scripts shield parser-sensitive HTML literals")
    void safeScriptContent_whenSourceContainsHtmlLiterals_escapesParserBoundaries() {
        String safe = TranscriptResources.safeScriptContent(
                "var value = '<HTML><head></head><body>x</BODY></HTML><!-->';</SCRIPT>"
        );

        assertThat(safe)
                .contains("\\x3CHTML>")
                .contains("\\x3Chead>")
                .contains("\\x3C/BODY>")
                .contains("\\x3C!-->")
                .contains("<\\/script>")
                .doesNotContain("<HTML>")
                .doesNotContain("<body>");
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
                .contains("compactDrawing: false")
                .contains("drawer.draw(tree, svg, 'chat4j', null)")
                .doesNotContain("drawer.draw(tree, svg, 'chat4j', false)")
                .contains("parseMolV2000")
                .contains("renderMolLikeBlock")
                .contains("SDF_MAX_RECORDS = 12")
                .contains("Showing first ")
                .contains("chat4jRenderMath")
                .contains("chat4jRenderDiagrams")
                .contains("chat4jRenderEnhancements")
                .contains("open-diagram-html")
                .contains("data-chat4j-diagram-source")
                .doesNotContain("new XMLSerializer()")
                .contains("chat4j-mermaid-display")
                .contains("diagram-open-button")
                .contains("window.chat4jDispatchTranscriptAction('open-diagram-html', -1, payload)")
                .contains("pageBackground: colors.background")
                .contains("sourceBackground: colors.secondarySurface")
                .contains("themeVariables: mermaidTheme(colors).themeVariables")
                .contains("window.chat4jOpenMermaidDiagram = openMermaidDiagram")
                .contains("Open diagram")
                .contains("table.insertRow(0)")
                .contains("chat4j-diagram-error-badge")
                .contains("target.parentNode.replaceChild(originalNode, target)")
                .contains("window.mermaid.parse(candidate)")
                .contains("normalizeMermaidEscapedLineBreaks(source)")
                .contains("repairMermaidSource(renderableSource)")
                .contains("renderSource(repaired, '-repaired')")
                .contains("installMermaidOpenButton(target, rendered.source)")
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

    private static String cssRule(String css, String selector) {
        var matcher = Pattern.compile("(?m)^\\s*%s\\s*\\{".formatted(Pattern.quote(selector))).matcher(css);
        if (!matcher.find()) {
            throw new AssertionError("Missing CSS rule: %s".formatted(selector));
        }
        int startIndex = matcher.start();
        int endIndex = css.indexOf('}', startIndex);
        if (endIndex < 0) {
            throw new AssertionError("Unterminated CSS rule: %s".formatted(selector));
        }
        return css.substring(startIndex, endIndex + 1);
    }

    @Test
    @DisplayName("Transcript action bridge exposes dispatch and Mermaid context menu actions")
    void transcriptActionsScript_whenRendered_exposesDiagramActionDispatcher() {
        String script = TranscriptBrowserAssets.transcriptActionsScript();

        assertThat(script)
                .contains("window.chat4jDispatchTranscriptAction = dispatchTranscriptAction")
                .contains("window.chat4jTranscriptAction(JSON.stringify({args:")
                .contains("dispatchMessageActionButton(actionButton, event)")
                .contains("button.type = 'button'")
                .contains("button.setAttribute('aria-label', 'Copy code')")
                .contains("button.getAttribute('data-read-aloud-token')")
                .contains("dispatchTranscriptAction(action, Number(button.getAttribute('data-message-index')), text)")
                .doesNotContain("messageActionText(button)", "function rowText(row)")
                .contains("function associatedMessageRow(row)")
                .contains("matches(next, '.row.assistant[data-message-index]')")
                .contains("player-stop")
                .contains("window.chat4jOpenMermaidDiagram(menu._chat4jDiagram)")
                .contains("data-action=\"open-diagram\"")
                .contains("class=\"icon open-diagram\"")
                .contains("Open Diagram")
                .contains("data-action=\"export-pdf\"")
                .contains("Export to PDF…")
                .contains("window.chat4jPdfExportAvailable === true");
    }

    @Test
    @DisplayName("WebView mouse presses dispatch a payload-free pointer action through the transcript bridge")
    void transcriptActionsScript_whenWebViewReceivesMouseDown_dispatchesPointerAction() {
        try (Context context = Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build()) {
            context.eval("js", """
                    var documentListeners = {};
                    var documentListenerCapture = {};
                    var bridgePayloads = [];
                    var window = {
                        addEventListener: function () {},
                        chat4jTranscriptAction: function (payload) {
                            bridgePayloads.push(payload);
                        }
                    };
                    var document = {
                        addEventListener: function (type, listener, capture) {
                            documentListeners[type] = listener;
                            documentListenerCapture[type] = capture;
                        }
                    };
                    """);
            context.eval("js", TranscriptBrowserAssets.transcriptActionsScript());

            context.eval("js", "window.chat4jDispatchTranscriptAction('copy-text', -1, '');");
            context.eval("js", "documentListeners.mousedown({});");
            String rawPayload = context.eval("js", "bridgePayloads[0]").asString();
            TranscriptCallbackPayloads.TranscriptAction action = TranscriptCallbackPayloads.transcriptAction(rawPayload);

            assertThat(context.eval("js", "documentListenerCapture.mousedown").asBoolean()).isTrue();
            assertThat(context.eval("js", "bridgePayloads.length").asInt()).isOne();
            assertThat(action).isEqualTo(new TranscriptCallbackPayloads.TranscriptAction("webview-pointer-down", -1, ""));
        }
    }

    @Test
    @DisplayName("Unhandled transcript key presses suppress native browser error feedback")
    void transcriptActionsScript_whenPrintableKeyHasNoBrowserTarget_preventsDefaultInteraction() {
        try (Context context = Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build()) {
            context.eval("js", """
                    var documentListeners = {};
                    var documentListenerCapture = {};
                    var window = { addEventListener: function () {} };
                    var document = {
                        addEventListener: function(type, listener, capture) {
                            documentListeners[type] = listener;
                            documentListenerCapture[type] = capture;
                        }
                    };
                    function targetFor(selectorMatch) {
                        return {
                            closest: function(selector) {
                                return selector === selectorMatch ? this : null;
                            }
                        };
                    }
                    function keypress(target, key, metaKey) {
                        return {
                            target: target,
                            key: key,
                            metaKey: metaKey === true,
                            ctrlKey: false,
                            altKey: false,
                            defaultPrevented: false,
                            preventDefault: function() { this.defaultPrevented = true; }
                        };
                    }
                    var transcriptKey = keypress(targetFor(''), 'a', false);
                    var inputKey = keypress(targetFor('input, textarea, select, [contenteditable]'), 'a', false);
                    var buttonActivation = keypress(targetFor('button, a[href]'), 'Enter', false);
                    var copyShortcut = keypress(targetFor(''), 'c', true);
                    """);
            context.eval("js", TranscriptBrowserAssets.transcriptActionsScript());

            context.eval("js", "documentListeners.keypress(transcriptKey);");
            context.eval("js", "documentListeners.keypress(inputKey);");
            context.eval("js", "documentListeners.keypress(buttonActivation);");
            context.eval("js", "documentListeners.keypress(copyShortcut);");

            assertThat(context.eval("js", "documentListenerCapture.keypress").asBoolean()).isTrue();
            assertThat(context.eval("js", "transcriptKey.defaultPrevented").asBoolean()).isTrue();
            assertThat(context.eval("js", "inputKey.defaultPrevented").asBoolean()).isFalse();
            assertThat(context.eval("js", "buttonActivation.defaultPrevented").asBoolean()).isFalse();
            assertThat(context.eval("js", "copyShortcut.defaultPrevented").asBoolean()).isFalse();
        }
    }

    @Test
    @DisplayName("Activity copy dispatches rendered text without toggling the details element")
    void transcriptActionsScript_whenActivityCopyClicked_dispatchesTextAndCancelsDefaultInteraction() {
        try (Context context = Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build()) {
            context.eval("js", """
                    var documentListeners = {};
                    var bridgePayloads = [];
                    var setTimeout = function(callback) { callback(); };
                    var activityContent = { textContent: 'First step\\nSecond step' };
                    var activitySummary = { textContent: 'Thinking' };
                    var activityBox = {
                        querySelector: function(selector) {
                            return selector === '.activity-content' ? activityContent : activitySummary;
                        }
                    };
                    var activityCopyButton = {
                        offsetWidth: 24,
                        classList: { add: function() {}, remove: function() {} },
                        closest: function(selector) {
                            if (selector === 'button[data-action="copy-activity"]') {
                                return activityCopyButton;
                            }
                            return selector === '.activity-box' ? activityBox : null;
                        }
                    };
                    var clickEvent = {
                        target: activityCopyButton,
                        prevented: false,
                        stopped: false,
                        preventDefault: function() { this.prevented = true; },
                        stopPropagation: function() { this.stopped = true; }
                    };
                    var window = {
                        addEventListener: function () {},
                        chat4jTranscriptAction: function(payload) { bridgePayloads.push(payload); }
                    };
                    var document = {
                        getElementById: function() { return null; },
                        addEventListener: function(type, listener) { documentListeners[type] = listener; }
                    };
                    """);
            context.eval("js", TranscriptBrowserAssets.transcriptActionsScript());

            context.eval("js", "documentListeners.click(clickEvent);");
            String rawPayload = context.eval("js", "bridgePayloads[0]").asString();
            TranscriptCallbackPayloads.TranscriptAction action = TranscriptCallbackPayloads.transcriptAction(rawPayload);

            assertThat(action).isEqualTo(new TranscriptCallbackPayloads.TranscriptAction(
                    "copy-text",
                    -1,
                    "First step\nSecond step"
            ));
            assertThat(context.eval("js", "clickEvent.prevented").asBoolean()).isTrue();
            assertThat(context.eval("js", "clickEvent.stopped").asBoolean()).isTrue();
        }
    }

    @Test
    @DisplayName("Activity expansion refreshes custom scroll chrome after layout changes")
    void transcriptActionsScript_whenActivityToggled_recalculatesCustomScrollbar() {
        try (Context context = Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build()) {
            context.eval("js", """
                    var documentListeners = {};
                    var documentListenerCapture = {};
                    var setTimeout = function(callback) { callback(); };
                    var root = { scrollHeight: 200, clientHeight: 100, scrollTop: 0 };
                    var topFade = { classList: { toggle: function() {} } };
                    var bottomFade = { classList: { toggle: function() {} } };
                    var jump = {
                        classList: { toggle: function() {} },
                        getAttribute: function() { return 'false'; },
                        style: {}
                    };
                    var track = {
                        clientHeight: 100,
                        removeCount: 0,
                        classList: {
                            add: function() {},
                            remove: function() { track.removeCount++; }
                        }
                    };
                    var thumb = { clientHeight: 0, style: {} };
                    var elements = {
                        'chat4j-top-fade': topFade,
                        'chat4j-bottom-fade': bottomFade,
                        'chat4j-jump-bottom': jump,
                        'chat4j-scrollbar': track,
                        'chat4j-scrollbar-thumb': thumb
                    };
                    var activity = {
                        closest: function(selector) { return selector === '.activity-box' ? activity : null; }
                    };
                    var window = {
                        innerHeight: 100,
                        addEventListener: function() {}
                    };
                    var document = {
                        scrollingElement: root,
                        documentElement: root,
                        body: { scrollHeight: 200 },
                        getElementById: function(id) { return elements[id] || null; },
                        addEventListener: function(type, listener, capture) {
                            documentListeners[type] = listener;
                            documentListenerCapture[type] = capture;
                        }
                    };
                    """);
            context.eval("js", TranscriptBrowserAssets.transcriptActionsScript());

            context.eval("js", "documentListeners.toggle({ target: activity });");

            assertThat(context.eval("js", "documentListenerCapture.toggle").asBoolean()).isTrue();
            assertThat(context.eval("js", "track.removeCount").asInt()).isOne();
            assertThat(context.eval("js", "thumb.style.height").asString()).isEqualTo("50px");
            assertThat(context.eval("js", "jump.style.display").asString()).isEqualTo("flex");
        }
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
        TranscriptCallbackPayloads.TranscriptAction systemWebViewAction = TranscriptCallbackPayloads.transcriptAction(
                "[\"{\\\"args\\\":[\\\"read-aloud\\\",1,\\\"assistant text\\\"]}\"]"
        );

        assertThat(action.action()).isEqualTo("copy");
        assertThat(action.messageIndex()).isEqualTo(7);
        assertThat(action.text()).isEqualTo("secret text");
        assertThat(action).hasToString("TranscriptAction[action=copy, messageIndex=7, text=<masked>]");
        assertThat(systemWebViewAction.action()).isEqualTo("read-aloud");
        assertThat(systemWebViewAction.messageIndex()).isEqualTo(1);
        assertThat(systemWebViewAction.text()).isEqualTo("assistant text");
    }

    @Test
    @DisplayName("Transcript update scripts centralize incremental WebView updates")
    void transcriptUpdateScripts_whenBuilt_escapeHtmlAndToggleJumpState() {
        String script = TranscriptUpdateScripts.transcriptHtmlUpdate("<p>hello</p>", true, false);
        String jumpScript = TranscriptUpdateScripts.jumpButtonChrome(false);
        String readAloudActiveScript = TranscriptUpdateScripts.readAloudChrome(7, true);
        String readAloudInactiveScript = TranscriptUpdateScripts.readAloudChrome(7, false);
        String scrollScript = TranscriptUpdateScripts.scrollToBottom();

        assertThat(script)
                .contains("document.querySelector('.transcript')")
                .contains("function directTypingRow(root)")
                .contains("template.innerHTML = \"<p>hello</p>\"")
                .contains("currentTyping && nextTyping")
                .contains("data-stream-session-id")
                .contains("transcript.insertBefore(template.content, currentTyping)")
                .contains("transcript.innerHTML = ''")
                .contains("transcript.appendChild(template.content)")
                .contains("} else if (window.chat4jRenderEnhancements) {")
                .contains("jump.classList.toggle('streaming', true)")
                .contains("if (false)");
        assertThat(jumpScript)
                .contains("jump.setAttribute('data-streaming', \"false\")")
                .contains("jump.classList.toggle('streaming', false)");
        assertThat(readAloudActiveScript)
                .contains("data-message-index=\"7\"")
                .contains("button.setAttribute('data-read-aloud-active', \"true\")")
                .contains("button.setAttribute('title', \"Stop\")")
                .contains("icon.classList.toggle('player-stop', true)");
        assertThat(readAloudInactiveScript)
                .contains("button.setAttribute('data-read-aloud-active', \"false\")")
                .contains("button.setAttribute('title', \"Read aloud\")")
                .contains("icon.classList.toggle('read-aloud', true)");
        assertThat(scrollScript)
                .contains("window.scrollTo")
                .contains("document.documentElement.scrollHeight");
        try (Context context = Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build()) {
            context.parse("js", script);
        }
    }

    @Test
    @DisplayName("Activity styles keep the header borderless and the copy action visible")
    void transcriptActivityStyles_whenLoaded_matchReferenceLayoutWithoutChangingCodeCopyBehavior() {
        String activityCss = TranscriptResources.requiredResourceText("/web/chat/transcript-activity.css");
        String codeCss = TranscriptResources.requiredResourceText("/web/chat/transcript-code.css");

        assertThat(cssRule(activityCss, ".activity-box"))
                .contains("width: 100%", "background: transparent")
                .doesNotMatch(".*(?:\\{|;)\\s*border(?:-[a-z]+)?\\s*:.*");
        assertThat(cssRule(activityCss, ".activity-box summary"))
                .contains("display: inline-flex", "align-items: center");
        assertThat(cssRule(activityCss, ".activity-chevron"))
                .contains("border-right:", "border-bottom:", "rotate(-45deg)");
        assertThat(cssRule(activityCss, ".activity-box[open] .activity-chevron"))
                .contains("rotate(45deg)");
        assertThat(cssRule(activityCss, ".activity-content"))
                .contains("width: 100%", "border: 1px solid var(--chat4j-border)", "border-radius: 10px");
        assertThat(cssRule(codeCss, ".activity-copy-button"))
                .contains("opacity: 0.72")
                .doesNotContain("position:", "pointer-events:", "transform:");
        assertThat(cssRule(codeCss, ".code-copy-button"))
                .contains("position: absolute", "opacity: 0", "pointer-events: none");
        assertThat(codeCss)
                .contains(".code-block-shell:hover .code-copy-button, .code-copy-button:focus-visible")
                .doesNotContain(".activity-box:hover .activity-copy-button");

        TranscriptRenderSnapshot snapshot = TranscriptRenderSupport.snapshot(
                List.of(ConversationEntry.activity("Thinking", "Reviewing the request", false)),
                RenderMode.PREVIEW,
                false,
                false
        );
        String document = new TranscriptDocumentRenderer().renderDocument(new TranscriptDocumentRequest(
                false,
                snapshot,
                TranscriptAssetMode.INLINE_ALL,
                "",
                ""
        ));
        assertThat(document)
                .contains(".activity-title {", ".activity-content {")
                .contains("<span class=\"activity-title\">Thinking</span>");
    }

    @Test
    @DisplayName("Typing styles use fixed opacity-only dots with a print fallback")
    void transcriptTypingStyles_whenLoaded_matchReferenceAnimationAndPrintFallback() {
        String bubbleCss = TranscriptResources.requiredResourceText("/web/chat/transcript-message-bubbles.css");
        String actionsCss = TranscriptResources.requiredResourceText("/web/chat/transcript-actions.css");

        assertThat(cssRule(bubbleCss, ".typing-pill"))
                .contains(
                        "width: 62px",
                        "height: 30px",
                        "border-radius: 8px",
                        "background: var(--chat4j-button-bg)",
                        "color: var(--chat4j-icon-color)"
                )
                .doesNotContain("cursor:", "box-shadow:");
        assertThat(cssRule(bubbleCss, ".typing-dot"))
                .contains("width: 6px", "height: 6px", "animation: chat4j-typing-dot")
                .doesNotContain("transform:");
        assertThat(cssRule(bubbleCss, ".typing-dot:nth-child(2)"))
                .contains("animation-delay: 0.16s");
        assertThat(cssRule(bubbleCss, ".typing-dot:nth-child(3)"))
                .contains("animation-delay: 0.32s");
        assertThat(bubbleCss)
                .contains("@keyframes chat4j-typing-dot")
                .doesNotContain("prefers-reduced-motion");
        assertThat(actionsCss).containsPattern(
                "(?s)@media print\\s*\\{.*?\\.row\\.activity,\\s*\\.row\\.typing,.*?\\.chat4j-scrollbar\\s*\\{\\s*display: none !important;\\s*}"
        );

        TranscriptRenderSnapshot snapshot = TranscriptRenderSupport.snapshot(
                List.of(ConversationEntry.typing(42L)),
                RenderMode.PREVIEW,
                false,
                false
        );
        String document = new TranscriptDocumentRenderer().renderDocument(new TranscriptDocumentRequest(
                false,
                snapshot,
                TranscriptAssetMode.INLINE_ALL,
                "",
                ""
        ));

        assertThat(document)
                .contains(".typing-pill {", "@keyframes chat4j-typing-dot")
                .contains("<div class=\"row typing\" data-stream-session-id=\"42\">");
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
        String tableCss = TranscriptResources.resourceText("/web/chat/transcript-tables.css");
        String diagramCss = TranscriptResources.resourceText("/web/chat/transcript-diagrams.css");
        String actionsCss = TranscriptResources.resourceText("/web/chat/transcript-actions.css");
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
        assertThat(tableCss)
                .contains("min-width: 760px")
                .contains("overflow-x: auto");
        assertThat(diagramCss)
                .contains(".chat4j-diagram")
                .contains(".chat4j-mermaid-display")
                .contains("var(--chat4j-mermaid-border)")
                .contains("var(--chat4j-mermaid-canvas-bg)");
        assertThat(actionsCss)
                .contains(".icon.open-diagram")
                .contains("var(--chat4j-open-diagram-icon-mask)")
                .contains("@media print")
                .contains(".chat4j-pdf-export-header")
                .contains(".chat4j-pdf-turn-heading")
                .contains(".row.activity")
                .contains(".chat4j-fade")
                .contains("a.source-citation.citation-ref[href]")
                .contains("a.source-citation.citation-ref[href]::before")
                .contains("content: \"[\"")
                .contains("vertical-align: super !important")
                .contains(".message .table-wrap")
                .contains("min-width: 0 !important")
                .contains("overflow-wrap: break-word !important")
                .contains("width: 22% !important")
                .contains("overflow-wrap: anywhere !important");
        assertThat(sourceCss).contains(".source-preview");
        assertThat(jumpCss).contains(".jump-button");
    }

    @Test
    @DisplayName("Open Diagram payload carries the rendered source candidate without serializing SVG")
    void diagramRenderScript_whenOpenPayloadIsBuilt_containsOnlySourceAndThemeMetadata() {
        try (Context context = Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build()) {
            context.eval("js", """
                    var window = {
                        getComputedStyle: function() {
                            return {
                                getPropertyValue: function() { return '#ffffff'; },
                                backgroundColor: '#ffffff',
                                color: '#111111'
                            };
                        }
                    };
                    var document = { body: {} };
                    var container = {
                        querySelector: function() { throw new Error('SVG must not be queried'); },
                        getAttribute: function(name) {
                            var values = {
                                'data-chat4j-diagram-title': 'Diagram',
                                'data-chat4j-diagram-source': 'flowchart TD\\nA-->B'
                            };
                            return values[name] || '';
                        }
                    };
                    """);
            Value payloadBuilder = exposedDiagramFunction(context, "mermaidPayload", "__chat4jMermaidPayload");

            Value payload = context.eval("js", "JSON.parse(window.__chat4jMermaidPayload(container))");

            assertThat(payloadBuilder.canExecute()).isTrue();
            assertThat(payload.getMember("source").asString()).isEqualTo("flowchart TD\nA-->B");
            assertThat(payload.hasMember("svg")).isFalse();
        }
    }

    @Test
    @DisplayName("Extracted diagram JavaScript normalizes escaped Mermaid label line breaks")
    void diagramRenderScript_whenMermaidLabelsContainEscapedNewlines_normalizesLabelBreaksOnly() {
        try (Context context = Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build()) {
            Value normalizer = mermaidEscapedLineBreakNormalizer(context);
            assertThat(normalizeMermaidEscapedLineBreaks(normalizer, "graph TD\\nA-->B"))
                    .isEqualTo("graph TD\nA-->B");
            assertThat(normalizeMermaidEscapedLineBreaks(normalizer, "graph TD\nA[Vosk\\nBest Offline Java Option]"))
                    .isEqualTo("graph TD\nA[Vosk<br/>Best Offline Java Option]");
            assertThat(normalizeMermaidEscapedLineBreaks(normalizer, "graph TD\nA[\"Vosk\\nBest Offline Java Option\"]"))
                    .isEqualTo("graph TD\nA[\"Vosk<br/>Best Offline Java Option\"]");
            assertThat(normalizeMermaidEscapedLineBreaks(normalizer, "graph TD\nA-->|Yes\\nLow Latency|B"))
                    .isEqualTo("graph TD\nA-->|Yes<br/>Low Latency|B");
            assertThat(normalizeMermaidEscapedLineBreaks(normalizer, "graph TD\nA -- Yes\\nLow Latency --> B"))
                    .isEqualTo("graph TD\nA -- Yes<br/>Low Latency --> B");
            assertThat(normalizeMermaidEscapedLineBreaks(normalizer, "graph TD\nA{Budget\\r\\nScale}"))
                    .isEqualTo("graph TD\nA{Budget<br/>Scale}");
        }
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

    private static Value mermaidEscapedLineBreakNormalizer(Context context) {
        context.eval("js", "var window = {};");
        return exposedDiagramFunction(
                context,
                "normalizeMermaidEscapedLineBreaks",
                "__chat4jNormalizeMermaidEscapedLineBreaks"
        );
    }

    private static Value exposedDiagramFunction(Context context, String functionName, String exposedName) {
        String script = TranscriptBrowserAssets.diagramRenderScript();
        int end = script.lastIndexOf("})();");
        assertThat(end).isGreaterThan(0);
        String testableScript = "%s\nwindow.%s = %s;\n%s".formatted(
                script.substring(0, end),
                exposedName,
                functionName,
                script.substring(end)
        );
        context.eval("js", testableScript);
        return context.getBindings("js").getMember("window").getMember(exposedName);
    }

    private static String normalizeMermaidEscapedLineBreaks(Value normalizer, String source) {
        return normalizer.execute(source).asString();
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
