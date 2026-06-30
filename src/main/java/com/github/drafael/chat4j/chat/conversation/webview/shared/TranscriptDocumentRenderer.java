package com.github.drafael.chat4j.chat.conversation.webview.shared;

import com.github.drafael.chat4j.chat.conversation.ConversationAttachment;
import com.github.drafael.chat4j.chat.render.Palette;
import com.github.drafael.chat4j.util.Fonts;
import org.jsoup.nodes.Document;

import javax.swing.UIManager;
import java.awt.Color;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static java.util.Map.entry;

public final class TranscriptDocumentRenderer {

    private static final String COPY_ICON = TranscriptResources.iconDataUri("/icons/input/copy.svg");
    private static final String REGENERATE_ICON = TranscriptResources.iconDataUri("/icons/chat/refresh-cw.svg");
    private static final String ARROW_DOWN_ICON = TranscriptResources.iconDataUri("/icons/chat/arrow-down.svg");
    private static final String TRANSCRIPT_CSS_TEMPLATE = TranscriptResources.requiredResourceText("/web/chat/transcript.css");
    private static final String TRANSCRIPT_LAYOUT_CSS = TranscriptResources.requiredResourceText("/web/chat/transcript-layout.css");
    private static final String TRANSCRIPT_MESSAGE_BUBBLE_CSS = TranscriptResources.requiredResourceText("/web/chat/transcript-message-bubbles.css");
    private static final String TRANSCRIPT_MESSAGE_CONTENT_CSS = TranscriptResources.requiredResourceText("/web/chat/transcript-message-content.css");
    private static final String TRANSCRIPT_TABLE_CSS = TranscriptResources.requiredResourceText("/web/chat/transcript-tables.css");
    private static final String TRANSCRIPT_CODE_CSS = TranscriptResources.requiredResourceText("/web/chat/transcript-code.css");
    private static final String TRANSCRIPT_MATH_CSS = TranscriptResources.requiredResourceText("/web/chat/transcript-math.css");
    private static final String TRANSCRIPT_DIAGRAM_CSS = TranscriptResources.requiredResourceText("/web/chat/transcript-diagrams.css");
    private static final String TRANSCRIPT_ACTIVITY_CSS = TranscriptResources.requiredResourceText("/web/chat/transcript-activity.css");
    private static final String TRANSCRIPT_MESSAGE_ACTION_CSS = TranscriptResources.requiredResourceText("/web/chat/transcript-actions.css");
    private static final String TRANSCRIPT_SOURCE_CSS = TranscriptResources.requiredResourceText("/web/chat/transcript-sources.css");
    private static final String TRANSCRIPT_JUMP_CSS = TranscriptResources.requiredResourceText("/web/chat/transcript-jump.css");
    private static final int ATTACHMENT_IMAGE_MAX_WIDTH = 420;
    private static final int ATTACHMENT_IMAGE_MAX_HEIGHT = 360;

    private final TranscriptEntryRenderer entryRenderer = new TranscriptEntryRenderer();

    public String renderDocument(TranscriptDocumentRequest request) {
        TranscriptRenderSnapshot snapshot = request.snapshot();
        boolean jumpButtonVisible = snapshot.jumpButtonVisible();
        Palette palette = snapshot.palette();
        TranscriptChrome chrome = snapshot.chrome();
        String entriesHtml = renderEntriesHtml(snapshot);
        String scrollScript = request.scrollToBottom()
                ? "<script>window.addEventListener('load', function(){ setTimeout(function(){ window.scrollTo(0, document.documentElement.scrollHeight || document.body.scrollHeight || 0); }, 0); });</script>"
                : "";
        String headAssets = TranscriptBrowserAssets.headAssets(
                request.assetMode(),
                request.mermaidScriptUrl(),
                request.smilesDrawerScriptUrl()
        );

        String transcriptCss = TranscriptResources.resolveTemplate(
                TRANSCRIPT_CSS_TEMPLATE,
                Map.ofEntries(
                        entry("theme-css", themeCss(snapshot)),
                        entry("layout-css", TRANSCRIPT_LAYOUT_CSS),
                        entry("message-bubble-css", TRANSCRIPT_MESSAGE_BUBBLE_CSS),
                        entry("attachment-css", attachmentCss(chrome, palette)),
                        entry("message-content-css", TRANSCRIPT_MESSAGE_CONTENT_CSS),
                        entry("table-css", TRANSCRIPT_TABLE_CSS),
                        entry("syntax-highlight-css", chrome.syntaxHighlightCss()),
                        entry("code-css", TRANSCRIPT_CODE_CSS),
                        entry("math-css", TRANSCRIPT_MATH_CSS),
                        entry("diagram-css", TRANSCRIPT_DIAGRAM_CSS),
                        entry("activity-css", TRANSCRIPT_ACTIVITY_CSS),
                        entry("message-action-css", TRANSCRIPT_MESSAGE_ACTION_CSS),
                        entry("source-css", TRANSCRIPT_SOURCE_CSS),
                        entry("jump-css", TRANSCRIPT_JUMP_CSS)
                )
        );
        return TranscriptResources.resolveTemplate(
                TranscriptResources.requiredResourceText("/web/chat/transcript-document.html"),
                Map.of(
                        "asset-tags", headAssets,
                        "transcript-css", transcriptCss,
                        "entries-html", entriesHtml,
                        "jump-streaming-class", jumpButtonVisible ? " streaming" : "",
                        "jump-streaming", jumpButtonVisible ? "true" : "false",
                        "scroll-script", scrollScript
                )
        );
    }

    private static String themeCss(TranscriptRenderSnapshot snapshot) {
        Palette palette = snapshot.palette();
        TranscriptChrome chrome = snapshot.chrome();
        Color panelBackground = chrome.panelBackground();
        int smallFontSize = Math.max(10, chrome.baseBodyFontSize() - 2);
        return """
                :root {
                  --chat4j-bg: %s;
                  --chat4j-text: %s;
                  --chat4j-muted-text: %s;
                  --chat4j-link: %s;
                  --chat4j-base-font-family: %s;
                  --chat4j-mono-font-family: %s;
                  --chat4j-body-font-size: %dpx;
                  --chat4j-table-font-size: %dpx;
                  --chat4j-code-font-size: %dpx;
                  --chat4j-language-font-size: %dpx;
                  --chat4j-small-font-size: %dpx;
                  --chat4j-panel-bg-alpha: %s;
                  --chat4j-panel-bg-red: %d;
                  --chat4j-panel-bg-green: %d;
                  --chat4j-panel-bg-blue: %d;
                  --chat4j-user-bubble-bg: %s;
                  --chat4j-border: %s;
                  --chat4j-menu-bg: %s;
                  --chat4j-button-bg: %s;
                  --chat4j-button-border: %s;
                  --chat4j-hover-bg: %s;
                  --chat4j-hover-fg: %s;
                  --chat4j-icon-color: %s;
                  --chat4j-surface-bg: %s;
                  --chat4j-inline-code-bg: %s;
                  --chat4j-code-bg: %s;
                  --chat4j-code-header-bg: %s;
                  --chat4j-code-border: %s;
                  --chat4j-scrollbar-track: %s;
                  --chat4j-scrollbar-thumb: %s;
                  --chat4j-scrollbar-hover-thumb: %s;
                  --chat4j-source-citation-bg: %s;
                  --chat4j-source-citation-border: %s;
                  --chat4j-source-citation-text: %s;
                  --chat4j-source-citation-hover-bg: %s;
                  --chat4j-mermaid-canvas-bg: var(--chat4j-code-bg);
                  --chat4j-mermaid-primary-bg: var(--chat4j-menu-bg);
                  --chat4j-mermaid-secondary-bg: var(--chat4j-inline-code-bg);
                  --chat4j-mermaid-tertiary-bg: var(--chat4j-code-header-bg);
                  --chat4j-mermaid-edge-label-bg: var(--chat4j-code-bg);
                  --chat4j-mermaid-border: var(--chat4j-scrollbar-thumb);
                  --chat4j-mermaid-line: var(--chat4j-muted-text);
                  --chat4j-mermaid-text: var(--chat4j-text);
                  --chat4j-mermaid-muted-text: var(--chat4j-muted-text);
                  --chat4j-jump-ring: %s;
                  --chat4j-jump-ring-track: %s;
                  --chat4j-jump-button-display: %s;
                  --chat4j-copy-icon-mask: url('%s');
                  --chat4j-regenerate-icon-mask: url('%s');
                  --chat4j-arrow-down-icon-mask: url('%s');
                }
                """.formatted(
                chrome.background(),
                palette.textColor(),
                palette.mutedTextColor(),
                palette.linkColor(),
                palette.baseFontFamily(),
                palette.monoFontFamily(),
                chrome.bodyFontSize(),
                chrome.tableFontSize(),
                chrome.codeFontSize(),
                chrome.languageFontSize(),
                smallFontSize,
                alphaCssColor(panelBackground, 0.92f),
                panelBackground.getRed(),
                panelBackground.getGreen(),
                panelBackground.getBlue(),
                chrome.bubbleBackground(),
                chrome.borderColor(),
                chrome.menuBackground(),
                chrome.buttonBackground(),
                chrome.buttonBorder(),
                chrome.hoverBackground(),
                chrome.hoverForeground(),
                chrome.iconColorValue(),
                palette.surfaceBg(),
                palette.inlineCodeBg(),
                palette.codeBg(),
                palette.codeHeaderBg(),
                palette.codeBorder(),
                chrome.scrollbarTrack(),
                chrome.scrollbarThumb(),
                chrome.scrollbarHoverThumb(),
                chrome.sourceCitationBackground(),
                chrome.sourceCitationBorder(),
                chrome.sourceCitationText(),
                chrome.sourceCitationHoverBackground(),
                chrome.jumpRing(),
                chrome.jumpRingTrack(),
                snapshot.jumpButtonVisible() ? "flex" : "none",
                COPY_ICON,
                REGENERATE_ICON,
                ARROW_DOWN_ICON
        );
    }

    public static TranscriptChrome documentChrome(boolean dark, Palette palette) {
        Color panelBackground = uiManagerColor("Panel.background", dark ? new Color(30, 31, 34) : new Color(247, 248, 250));
        Color componentBorderColor = uiManagerColor("Component.borderColor", dark ? new Color(60, 63, 67) : new Color(217, 221, 228));
        Color menuBackgroundColor = uiManagerColor("PopupMenu.background", panelBackground);
        Color buttonBackgroundColor = uiManagerColor("Button.background", blend(panelBackground, dark ? Color.WHITE : Color.BLACK, dark ? 0.10f : 0.04f));
        Color buttonBorderColor = uiManagerColor("Button.borderColor", componentBorderColor);
        Color sourceAccentColor = uiManagerColor("Component.accentColor", dark ? new Color(60, 190, 188) : new Color(1, 106, 113));
        Color labelForegroundColor = uiManagerColor("Label.foreground", dark ? Color.WHITE : Color.BLACK);
        Color hoverBackgroundColor = uiManagerColor(
                "MenuItem.selectionBackground",
                blend(panelBackground, dark ? Color.WHITE : Color.BLACK, dark ? 0.14f : 0.08f)
        );
        Color hoverForegroundColor = uiManagerColor("MenuItem.selectionForeground", labelForegroundColor);
        Color iconColor = uiManagerColor("Label.disabledForeground", blend(hoverForegroundColor, panelBackground, dark ? 0.36f : 0.28f));
        Color jumpRingColor = uiManagerColor("ProgressBar.foreground", uiManagerColor("Component.accentColor", dark ? new Color(88, 166, 255) : new Color(70, 130, 230)));
        Color jumpRingTrackColor = alphaColor(uiManagerColor("ProgressBar.background", buttonBorderColor), 96);
        Color scrollbarTrackColor = blend(panelBackground, dark ? Color.WHITE : Color.BLACK, dark ? 0.06f : 0.025f);
        Color scrollbarThumbColor = blend(panelBackground, dark ? Color.WHITE : Color.BLACK, dark ? 0.34f : 0.24f);
        Color scrollbarHoverThumbColor = blend(panelBackground, dark ? Color.WHITE : Color.BLACK, dark ? 0.46f : 0.34f);
        int baseBodyFontSize = Fonts.scale(Fonts.SIZE_BODY);
        return new TranscriptChrome(
                panelBackground,
                cssColor(panelBackground),
                cssColor(userBubbleColor(panelBackground)),
                cssColor(componentBorderColor),
                cssColor(menuBackgroundColor),
                cssColor(buttonBackgroundColor),
                cssColor(buttonBorderColor),
                cssColor(blend(panelBackground, sourceAccentColor, dark ? 0.18f : 0.08f)),
                cssColor(blend(panelBackground, sourceAccentColor, dark ? 0.52f : 0.34f)),
                cssColor(blend(labelForegroundColor, sourceAccentColor, dark ? 0.36f : 0.28f)),
                cssColor(blend(panelBackground, sourceAccentColor, dark ? 0.28f : 0.14f)),
                cssColor(jumpRingColor),
                alphaCssColor(jumpRingTrackColor),
                cssColor(hoverBackgroundColor),
                cssColor(hoverForegroundColor),
                cssColor(iconColor),
                cssColor(scrollbarTrackColor),
                cssColor(scrollbarThumbColor),
                cssColor(scrollbarHoverThumbColor),
                baseBodyFontSize,
                baseBodyFontSize + 1,
                Math.max(11, baseBodyFontSize - 1),
                baseBodyFontSize,
                Math.max(10, baseBodyFontSize - 2),
                syntaxHighlightCss(dark)
        );
    }

    private static String attachmentCss(TranscriptChrome chrome, Palette palette) {
        return """
                    .attachment-strip { display: flex; flex-wrap: wrap; justify-content: flex-end; align-items: flex-end; gap: 6px; max-width: 72%%; margin: 0 0 8px auto; }
                    .attachment-image-button { display: inline-flex; border: 0; background: transparent; padding: 0; margin: 0; cursor: pointer; border-radius: 12px; }
                    .attachment-image { display: block; max-width: min(%dpx, 33vw); max-height: %dpx; border-radius: 12px; border: 1px solid %s; box-shadow: 0 2px 8px rgba(0,0,0,0.12); object-fit: contain; }
                    .attachment-chip { display: inline-flex; align-items: center; gap: 6px; max-width: 320px; min-height: 28px; box-sizing: border-box; border-radius: 10px; border: 1px solid %s; background: %s; color: %s; padding: 3px 8px; font: inherit; font-size: %dpx; line-height: 1.2; cursor: pointer; }
                    .attachment-chip:hover, .attachment-image-button:hover .attachment-image { border-color: currentColor; }
                    .attachment-chip.unavailable { cursor: default; opacity: 0.72; }
                    .attachment-chip.unavailable:hover { border-color: %s; }
                    .attachment-icon { flex: 0 0 auto; }
                    .attachment-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
                    .attachment-size { flex: 0 0 auto; color: %s; font-size: %dpx; }
                """.formatted(
                ATTACHMENT_IMAGE_MAX_WIDTH,
                ATTACHMENT_IMAGE_MAX_HEIGHT,
                chrome.buttonBorder(),
                chrome.buttonBorder(),
                chrome.buttonBackground(),
                palette.textColor(),
                Math.max(11, chrome.baseBodyFontSize() - 1),
                chrome.buttonBorder(),
                palette.mutedTextColor(),
                Math.max(10, chrome.baseBodyFontSize() - 2)
        );
    }

    private static String syntaxHighlightCss(boolean dark) {
        String keyword = dark ? "#ff7ab2" : "#9a3412";
        String string = dark ? "#8bd5a0" : "#166534";
        String comment = dark ? "#7f8c98" : "#6b7280";
        String number = dark ? "#f5c177" : "#b45309";
        String neutral = dark ? "#c7cbd1" : "#4b5563";
        String tag = dark ? "#89ddff" : "#0369a1";
        String meta = dark ? "#c792ea" : "#7e22ce";
        String section = dark ? "#f78c6c" : "#b91c1c";
        return """
                    .message pre.hljs { color: inherit; background: transparent; }
                    .message .hljs-keyword, .message .hljs-selector-tag, .message .hljs-subst, .message .chat4j-primitive { color: %s; }
                    .message .hljs-string, .message .hljs-doctag, .message .hljs-regexp { color: %s; }
                    .message .hljs-comment, .message .hljs-quote { color: %s; font-style: italic; }
                    .message .hljs-number, .message .hljs-literal { color: %s; }
                    .message .hljs-title, .message .hljs-title.function_, .message .hljs-class, .message .hljs-title.class_, .message .hljs-title.class_.inherited__, .message .hljs-type, .message .hljs-built_in, .message .hljs-variable, .message .hljs-variable.language_, .message .hljs-template-variable, .message .hljs-operator, .message .hljs-property, .message .hljs-params, .message .hljs-punctuation, .message .hljs-attr, .message .hljs-attribute { color: %s; }
                    .message pre.language-typescript .hljs-built_in, .message pre.language-ts .hljs-built_in, .message pre.language-javascript .hljs-built_in, .message pre.language-js .hljs-built_in { color: %s; }
                    .message .hljs-tag, .message .hljs-name, .message .hljs-selector-id, .message .hljs-selector-class { color: %s; }
                    .message .hljs-meta, .message .hljs-symbol, .message .hljs-bullet { color: %s; }
                    .message .hljs-section, .message .hljs-strong { color: %s; font-weight: 700; }
                    .message .hljs-emphasis { font-style: italic; }
                    .message .hljs-addition { color: %s; }
                    .message .hljs-deletion { color: %s; }
                """.formatted(
                keyword,
                string,
                comment,
                number,
                neutral,
                keyword,
                tag,
                meta,
                section,
                string,
                section
        );
    }

    private static Color userBubbleColor(Color panelBackground) {
        float[] hsb = Color.RGBtoHSB(panelBackground.getRed(), panelBackground.getGreen(), panelBackground.getBlue(), null);
        boolean darkTheme = hsb[2] <= 0.5f;
        float brightness = clamp(hsb[2] + (darkTheme ? 0.10f : -0.04f));
        float saturation = clamp(hsb[1] + (darkTheme ? -0.02f : 0.02f));
        return Color.getHSBColor(hsb[0], saturation, brightness);
    }

    private static Color uiManagerColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color == null ? fallback : color;
    }

    private static Color blend(Color base, Color overlay, float ratio) {
        float safeRatio = clamp(ratio);
        float inverse = 1f - safeRatio;
        int red = Math.round(base.getRed() * inverse + overlay.getRed() * safeRatio);
        int green = Math.round(base.getGreen() * inverse + overlay.getGreen() * safeRatio);
        int blue = Math.round(base.getBlue() * inverse + overlay.getBlue() * safeRatio);
        return new Color(red, green, blue);
    }

    private static String cssColor(Color color) {
        return "#%02x%02x%02x".formatted(color.getRed(), color.getGreen(), color.getBlue());
    }

    private static String alphaCssColor(Color color, float alpha) {
        return "rgba(%d,%d,%d,%.3f)".formatted(color.getRed(), color.getGreen(), color.getBlue(), clamp(alpha));
    }

    private static String alphaCssColor(Color color) {
        return "rgba(%d,%d,%d,%.3f)".formatted(color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha() / 255f);
    }

    private static Color alphaColor(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    public String renderEntriesHtml(TranscriptRenderSnapshot snapshot) {
        return entryRenderer.renderEntriesHtml(snapshot);
    }

    public String renderEntriesHtml(TranscriptRenderSnapshot snapshot, BooleanSupplier shouldContinue) {
        return entryRenderer.renderEntriesHtml(snapshot, shouldContinue);
    }

    public static String renderAttachmentStripHtml(List<ConversationAttachment> attachments) {
        return TranscriptEntryRenderer.renderAttachmentStripHtml(attachments);
    }

    public static void renderCodeHighlights(Document document) {
        TranscriptEntryRenderer.renderCodeHighlights(document);
    }

    public static void renderMathFallbacks(Document document) {
        TranscriptEntryRenderer.renderMathFallbacks(document);
    }

    public static void removeAdjacentDuplicateSourceCitations(Document document) {
        TranscriptEntryRenderer.removeAdjacentDuplicateSourceCitations(document);
    }
}
