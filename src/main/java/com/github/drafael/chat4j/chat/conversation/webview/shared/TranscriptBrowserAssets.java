package com.github.drafael.chat4j.chat.conversation.webview.shared;

import org.apache.commons.lang3.StringUtils;

import static com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptResources.inlineStylesheetFonts;
import static com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptResources.requiredResourceText;
import static com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptResources.safeScriptContent;

public final class TranscriptBrowserAssets {

    private static final String KATEX_CSS = inlineStylesheetFonts(requiredResourceText("/web/katex/katex.min.css"));
    private static final String KATEX_SCRIPT = requiredResourceText("/web/katex/katex.min.js");
    private static final String MHCHEM_SCRIPT = requiredResourceText("/web/katex/contrib/mhchem.min.js");
    private static final String MERMAID_SCRIPT = requiredResourceText("/web/mermaid/mermaid.min.js");
    private static final String SMILES_DRAWER_SCRIPT = requiredResourceText("/web/smilesdrawer/smiles-drawer.min.js");
    private static final String TRANSCRIPT_ACTIONS_SCRIPT = requiredResourceText("/web/chat/transcript-actions.js");
    private static final String MATH_RENDER_SCRIPT = requiredResourceText("/web/chat/math-render.js");
    private static final String DIAGRAM_RENDER_SCRIPT = requiredResourceText("/web/chat/diagram-render.js");

    private TranscriptBrowserAssets() {
    }

    public static String headAssets(TranscriptAssetMode assetMode, String mermaidScriptUrl, String smilesDrawerScriptUrl) {
        if (isBlank()) {
            return "";
        }

        TranscriptAssetMode safeAssetMode = assetMode == null ? TranscriptAssetMode.INLINE_ALL : assetMode;
        return switch (safeAssetMode) {
            case INLINE_ALL -> inlineHeadAssets();
            case INTERNAL_URL_FOR_LARGE_LIBRARIES -> internalUrlHeadAssets(mermaidScriptUrl, smilesDrawerScriptUrl);
        };
    }

    public static String inlineHeadAssets() {
        if (isBlank()) {
            return "";
        }

        return """
                <style id="chat4j-katex-css">%s</style>
                <script id="chat4j-katex-script">%s</script>
                <script id="chat4j-mhchem-script">%s</script>
                <script id="chat4j-math-render-script">%s</script>
                <script id="chat4j-mermaid-script">%s</script>
                <script id="chat4j-smiles-drawer-script">%s</script>
                <script id="chat4j-diagram-render-script">%s</script>
                """.formatted(
                KATEX_CSS,
                safeScriptContent(KATEX_SCRIPT),
                safeScriptContent(MHCHEM_SCRIPT),
                safeScriptContent(MATH_RENDER_SCRIPT),
                safeScriptContent(MERMAID_SCRIPT),
                safeScriptContent(SMILES_DRAWER_SCRIPT),
                safeScriptContent(DIAGRAM_RENDER_SCRIPT)
        );
    }

    public static String internalUrlHeadAssets(String mermaidScriptUrl, String smilesDrawerScriptUrl) {
        if (isBlank()) {
            return "";
        }

        return """
                <style id="chat4j-katex-css">%s</style>
                <script id="chat4j-katex-script">%s</script>
                <script id="chat4j-mhchem-script">%s</script>
                <script id="chat4j-math-render-script">%s</script>
                <script id="chat4j-mermaid-script" src="%s"></script>
                <script id="chat4j-smiles-drawer-script" src="%s"></script>
                <script id="chat4j-diagram-render-script">%s</script>
                """.formatted(
                KATEX_CSS,
                safeScriptContent(KATEX_SCRIPT),
                safeScriptContent(MHCHEM_SCRIPT),
                safeScriptContent(MATH_RENDER_SCRIPT),
                mermaidScriptUrl,
                smilesDrawerScriptUrl,
                safeScriptContent(DIAGRAM_RENDER_SCRIPT)
        );
    }

    public static String katexScript() {
        return KATEX_SCRIPT;
    }

    public static String mhchemScript() {
        return MHCHEM_SCRIPT;
    }

    public static String mermaidScript() {
        return MERMAID_SCRIPT;
    }

    public static String smilesDrawerScript() {
        return SMILES_DRAWER_SCRIPT;
    }

    public static String transcriptActionsScript() {
        return TRANSCRIPT_ACTIONS_SCRIPT;
    }

    public static String mathRenderScript() {
        return MATH_RENDER_SCRIPT;
    }

    public static String diagramRenderScript() {
        return DIAGRAM_RENDER_SCRIPT;
    }

    public static String mathBridgeScript() {
        return "%s\n%s\n%s\n%s\n%s\n%s".formatted(
                KATEX_SCRIPT,
                MHCHEM_SCRIPT,
                MATH_RENDER_SCRIPT,
                MERMAID_SCRIPT,
                SMILES_DRAWER_SCRIPT,
                DIAGRAM_RENDER_SCRIPT
        );
    }

    private static boolean isBlank() {
        return StringUtils.isBlank(KATEX_CSS)
                && StringUtils.isBlank(KATEX_SCRIPT)
                && StringUtils.isBlank(MERMAID_SCRIPT)
                && StringUtils.isBlank(SMILES_DRAWER_SCRIPT);
    }
}
