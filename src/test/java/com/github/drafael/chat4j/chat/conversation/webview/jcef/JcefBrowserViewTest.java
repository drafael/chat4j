package com.github.drafael.chat4j.chat.conversation.webview.jcef;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JcefBrowserViewTest {

    @Test
    @DisplayName("JCEF diagram bridge can open rendered Mermaid diagrams externally")
    void mathBridgeScript_whenRendered_containsOpenMermaidDiagramAction() {
        String script = JcefBrowserView.mathBridgeScript();

        assertThat(script)
                .contains("open-diagram-html")
                .contains("XMLSerializer")
                .contains("chat4j-mermaid-display")
                .contains("diagram-open-button")
                .contains("scale: 1.35")
                .contains("parseMolV2000")
                .contains("renderMolLikeBlock")
                .contains("SDF_MAX_RECORDS = 12")
                .contains("Showing first ")
                .contains("window.chat4jDispatchTranscriptAction('open-diagram-html', -1, payload)")
                .contains("window.chat4jOpenMermaidDiagram = openMermaidDiagram");
    }

    @Test
    @DisplayName("JCEF transcript bridge exposes the shared action dispatcher for rendered diagrams")
    void bridgeScript_whenRendered_exposesDiagramActionDispatcher() {
        String script = JcefBrowserView.bridgeScript();

        assertThat(script)
                .contains("window.chat4jDispatchTranscriptAction = dispatchTranscriptAction")
                .contains("window.chat4jOpenMermaidDiagram(menu._chat4jDiagram)")
                .contains("data-action=\"open-diagram\"")
                .contains("Open Diagram");
    }

    @Test
    @DisplayName("JCEF loads large diagram libraries through internal resource URLs")
    void mathHeadAssets_whenRendered_referencesDiagramLibrariesAsScripts() {
        String assets = JcefBrowserView.mathHeadAssets();

        assertThat(assets)
                .contains("src=\"https://chat4j.local/assets/mermaid/mermaid.min.js\"")
                .contains("src=\"https://chat4j.local/assets/smilesdrawer/smiles-drawer.min.js\"")
                .contains("chat4j-diagram-render-script")
                .doesNotContain("globalThis[\"mermaid\"]")
                .doesNotContain("window.SmilesDrawer=");
    }
}
