package com.github.drafael.chat4j.chat.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.chat.CodeFontResolver;
import com.github.drafael.chat4j.chat.MarkdownPaletteResolver;
import com.github.drafael.chat4j.chat.Palette;
import com.github.drafael.chat4j.chat.RenderMode;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.util.Fonts;
import com.formdev.flatlaf.util.SystemInfo;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.callback.CefResourceReadCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefResourceHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.joining;

public final class JcefTranscriptView {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String COPY_ICON = iconDataUri("/icons/input/copy.svg");
    private static final String REGENERATE_ICON = iconDataUri("/icons/chat/refresh-cw.svg");
    private static final String ARROW_DOWN_ICON = iconDataUri("/icons/chat/arrow-down.svg");
    private static final Pattern CSS_FONT_URL_PATTERN = Pattern.compile("url\\((['\\\"]?)(fonts/([^)'\\\"?]+))\\1\\)");
    private static final String KATEX_CSS = inlineStylesheetFonts(resourceText("/web/katex/katex.min.css"));
    private static final String KATEX_SCRIPT = resourceText("/web/katex/katex.min.js");
    private static final String MHCHEM_SCRIPT = resourceText("/web/katex/contrib/mhchem.min.js");
    private static final int ATTACHMENT_IMAGE_MAX_WIDTH = 420;
    private static final int ATTACHMENT_IMAGE_MAX_HEIGHT = 360;
    private static final KatexMathRenderer KATEX_RENDERER = KatexMathRenderer.instance();
    private static final HighlightJsCodeRenderer HIGHLIGHT_RENDERER = HighlightJsCodeRenderer.instance();

    private final JPanel browserPanel = new JPanel(new BorderLayout());
    private final Map<String, String> htmlByUrl = new ConcurrentHashMap<>();
    private CefClient cefClient;
    private CefMessageRouter messageRouter;
    private CefBrowser browser;
    private final MessageHtmlRenderer messageHtmlRenderer = new MessageHtmlRenderer();
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("chat4j-transcript-render-", 0).factory()
    );
    private final AtomicLong renderRequestCounter = new AtomicLong();
    private List<Entry> entries = emptyList();
    private RenderMode renderMode = RenderMode.PREVIEW;
    private boolean dark;
    private boolean jumpButtonVisible;
    private boolean documentInitialized;
    private boolean documentLoadPending;
    private DocumentUrl pendingDocumentUrl;
    private long pendingDocumentRequestId;
    private boolean pendingDocumentScrollToBottom;
    private Path currentDocumentPath;
    private boolean disposed;
    private TranscriptActionListener actionListener;

    public JcefTranscriptView() {
        browserPanel.setPreferredSize(new Dimension(800, 600));
        browserPanel.setMinimumSize(new Dimension(320, 220));
        browserPanel.add(new JLabel("JCEF transcript not loaded", SwingConstants.CENTER), BorderLayout.CENTER);
        installBrowserResizeWorkaround();
        browserPanel.addHierarchyListener(event -> applyPendingDocumentUrl());
    }

    public JComponent component() {
        return browserPanel;
    }

    public void setActionListener(TranscriptActionListener actionListener) {
        this.actionListener = actionListener;
    }

    public void setTranscript(
            List<Entry> entries,
            RenderMode renderMode,
            boolean dark,
            boolean scrollToBottom,
            boolean jumpButtonVisible
    ) {
        RenderMode nextRenderMode = renderMode == null ? RenderMode.PREVIEW : renderMode;
        boolean styleChanged = this.renderMode != nextRenderMode || this.dark != dark;
        boolean jumpButtonChanged = this.jumpButtonVisible != jumpButtonVisible;
        this.entries = List.copyOf(entries == null ? emptyList() : entries);
        this.renderMode = nextRenderMode;
        this.dark = dark;
        this.jumpButtonVisible = jumpButtonVisible;

        if (jumpButtonChanged) {
            updateJumpButtonChrome();
            SwingUtilities.invokeLater(this::updateJumpButtonChrome);
        }
        if (styleChanged) {
            reload(scrollToBottom);
            return;
        }
        if (documentLoadPending) {
            if (jumpButtonChanged) {
                reload(scrollToBottom);
            }
            return;
        }
        if (!documentInitialized) {
            reload(scrollToBottom);
            return;
        }

        scheduleTranscriptHtmlUpdate(scrollToBottom, transcriptRenderSnapshot());
    }

    public void reload(boolean scrollToBottom) {
        if (disposed) {
            return;
        }
        long requestId = renderRequestCounter.incrementAndGet();
        documentInitialized = false;
        documentLoadPending = true;
        TranscriptRenderSnapshot snapshot = transcriptRenderSnapshot();
        renderExecutor.execute(() -> {
            if (disposed || requestId != renderRequestCounter.get()) {
                return;
            }
            String document = injectJcefBridge(renderWithSnapshotFonts(snapshot, () -> renderDocument(scrollToBottom, snapshot)));
            SwingUtilities.invokeLater(() -> {
                if (disposed || requestId != renderRequestCounter.get()) {
                    return;
                }
                applyDocumentUrl(requestId, toDocumentUrl(document), scrollToBottom);
            });
        });
    }

    public void scrollToBottom() {
        executeJavaScript("window.scrollTo(0, document.documentElement.scrollHeight || document.body.scrollHeight || 0);");
    }

    public void dispose() {
        disposed = true;
        renderRequestCounter.incrementAndGet();
        renderExecutor.shutdownNow();
        deletePendingDocumentUrl();
        replaceCurrentDocumentPath(null);
        closeBrowser();
    }

    public boolean isDisposed() {
        return disposed;
    }

    private String renderDocument(boolean scrollToBottom, TranscriptRenderSnapshot snapshot) {
        boolean jumpButtonVisible = snapshot.jumpButtonVisible();
        Palette palette = snapshot.palette();
        DocumentChrome chrome = snapshot.chrome();
        Color panelBackground = chrome.panelBackground();
        String background = chrome.background();
        String bubbleBackground = chrome.bubbleBackground();
        String borderColor = chrome.borderColor();
        String menuBackground = chrome.menuBackground();
        String buttonBackground = chrome.buttonBackground();
        String buttonBorder = chrome.buttonBorder();
        String sourceChipBackground = chrome.sourceChipBackground();
        String sourceChipBorder = chrome.sourceChipBorder();
        String sourceChipText = chrome.sourceChipText();
        String sourceChipHoverBackground = chrome.sourceChipHoverBackground();
        String jumpRing = chrome.jumpRing();
        String jumpRingTrack = chrome.jumpRingTrack();
        String hoverBackground = chrome.hoverBackground();
        String hoverForeground = chrome.hoverForeground();
        String iconColorValue = chrome.iconColorValue();
        String attachmentCss = attachmentCss(chrome, palette);
        String scrollbarTrack = chrome.scrollbarTrack();
        String scrollbarThumb = chrome.scrollbarThumb();
        String scrollbarHoverThumb = chrome.scrollbarHoverThumb();
        int baseBodyFontSize = chrome.baseBodyFontSize();
        int bodyFontSize = chrome.bodyFontSize();
        int codeFontSize = chrome.codeFontSize();
        int tableFontSize = chrome.tableFontSize();
        int languageFontSize = chrome.languageFontSize();
        String syntaxHighlightCss = chrome.syntaxHighlightCss();
        String entriesHtml = renderEntriesHtml(snapshot);
        String scrollScript = scrollToBottom
                ? "<script>window.addEventListener('load', function(){ setTimeout(function(){ window.scrollTo(0, document.documentElement.scrollHeight || document.body.scrollHeight || 0); }, 0); });</script>"
                : "";

        return """
                <html>
                <head>
                  <meta charset="UTF-8">
                  %s
                  <style>
                    html, body { margin: 0; min-height: 100%%; background: %s; color: %s; font-family: %s; font-size: %dpx; line-height: 1.62; scrollbar-color: transparent transparent; scrollbar-width: none; }
                    body { box-sizing: border-box; padding: 24px 36px 96px 28px; overflow-y: auto; -ms-overflow-style: none; }
                    html::-webkit-scrollbar, body::-webkit-scrollbar { width: 0 !important; height: 0 !important; display: none !important; background: transparent !important; }
                    .chat4j-scrollbar { position: fixed; top: 5px; right: 3px; bottom: 5px; width: 10px; border-radius: 999px; background: transparent; z-index: 95; opacity: 0; transition: opacity 120ms ease; }
                    body:hover .chat4j-scrollbar, .chat4j-scrollbar.visible, .chat4j-scrollbar.dragging { opacity: 1; }
                    .chat4j-scrollbar-thumb { position: absolute; top: 0; right: 1px; width: 8px; min-height: 32px; border-radius: 999px; background: %s; }
                    .chat4j-scrollbar-thumb:hover, .chat4j-scrollbar.dragging .chat4j-scrollbar-thumb { background: %s; }
                    .chat4j-scrollbar.hidden { display: none; }
                    .chat4j-fade { position: fixed; left: 0; right: 0; pointer-events: none; z-index: 90; opacity: 0; transition: opacity 140ms ease; }
                    .chat4j-fade.visible { opacity: 1; }
                    .chat4j-fade.top { top: 0; height: 34px; background: linear-gradient(to bottom, %s 0%%, %s 38%%, rgba(%d,%d,%d,0) 100%%); }
                    .chat4j-fade.bottom { bottom: 0; height: 42px; background: linear-gradient(to top, %s 0%%, %s 34%%, rgba(%d,%d,%d,0) 100%%); }
                    .transcript { max-width: none; margin: 0; }
                    .row { display: flex; margin: 6px 0 26px 0; }
                    .row.user { justify-content: flex-end; }
                    .row.assistant, .row.activity { justify-content: flex-start; }
                    .message { box-sizing: border-box; overflow-wrap: anywhere; word-break: break-word; }
                    .message.user { max-width: 72%%; background: %s; border-radius: 12px; padding: 8px 14px; }
                    .row.user .message.user { margin-left: auto; }
                    %s
                    .message.assistant { width: 100%%; padding: 0; line-height: 1.72; }
                    .message h1, .message h2, .message h3, .message h4, .message h5, .message h6 { color: %s; font-weight: 700; line-height: 1.24; }
                    .message h1 { font-size: 22px; margin: 14px 0 8px 0; }
                    .message h2 { font-size: 19px; margin: 13px 0 7px 0; }
                    .message h3 { font-size: 17px; margin: 12px 0 6px 0; }
                    .message h4, .message h5, .message h6 { font-size: 15px; margin: 10px 0 5px 0; }
                    .message br + h1, .message br + h2, .message br + h3, .message br + h4, .message br + h5, .message br + h6 { margin-top: 6px; }
                    .message p { margin: 8px 0 10px 0; }
                    .message.user p { margin: 2px 0; }
                    .message ul, .message ol { margin: 10px 0 16px 0; padding-left: 26px; }
                    .message li { margin: 8px 0; }
                    .message hr { border: none; border-top: 1px solid %s; margin: 18px 0; height: 0; background: transparent; }
                    .message blockquote { margin: 10px 0; padding: 8px 12px; border-left: 3px solid %s; background: %s; }
                    .message .table-wrap { width: 100%%; overflow-x: auto; margin: 10px 0 16px 0; scrollbar-color: %s %s; }
                    .message .table-wrap::-webkit-scrollbar { height: 10px !important; display: block !important; background-color: %s !important; }
                    .message .table-wrap::-webkit-scrollbar-track { background-color: %s !important; border-radius: 999px !important; }
                    .message .table-wrap::-webkit-scrollbar-thumb { background-color: %s !important; border-radius: 999px !important; border: 2px solid %s !important; }
                    .message .table-wrap::-webkit-scrollbar-thumb:hover { background-color: %s !important; }
                    .message .table-wrap::-webkit-scrollbar-corner { background-color: %s !important; }
                    .message table.md-table { border-collapse: collapse; margin: 0; width: 100%%; min-width: 760px; table-layout: auto; font-size: %dpx; }
                    .message table.md-table th, .message table.md-table td { border-bottom: 1px solid %s; padding: 8px 12px; vertical-align: top; word-break: normal; overflow-wrap: break-word; white-space: normal; }
                    .message table.md-table th:first-child, .message table.md-table td:first-child { min-width: 150px; }
                    .message table.md-table th:not(:first-child), .message table.md-table td:not(:first-child) { min-width: 220px; }
                    .message table.md-table code { word-break: normal; overflow-wrap: normal; white-space: nowrap; font-size: %dpx !important; }
                    %s
                    .code-block-shell { position: relative; margin: 10px 0 20px 0; border-radius: 3px; }
                    .code-block-shell table.md-code-block { margin: 0 !important; }
                    .code-copy-button, .activity-copy-button { position: absolute; width: 24px; height: 24px; border: 1px solid %s; border-radius: 5px; background: %s; color: %s; display: flex; align-items: center; justify-content: center; padding: 0; cursor: pointer; z-index: 12; opacity: 0; pointer-events: none; transform: translateY(-2px) scale(0.92); transition: opacity 120ms ease, transform 140ms cubic-bezier(.2,.8,.2,1), background 120ms ease, color 120ms ease, border-color 120ms ease; }
                    .code-copy-button { top: 6px; right: 6px; }
                    .code-block-shell:hover .code-copy-button, .activity-box:hover .activity-copy-button { opacity: 1; pointer-events: auto; transform: translateY(0) scale(1); }
                    .code-copy-button:hover, .activity-copy-button:hover { background: %s; color: %s; transform: translateY(0) scale(1.04); }
                    .code-copy-button .icon, .activity-copy-button .icon { width: 14px; height: 14px; }
                    .copy-flash .icon.copy, .message-action-button.copy-flash .icon.copy { animation: chat4j-copy-pop 420ms cubic-bezier(.2,.8,.2,1); }
                    .copy-flash { animation: chat4j-copy-button-flash 420ms ease; }
                    @keyframes chat4j-copy-pop { 0%% { transform: scale(1); } 35%% { transform: scale(0.78); } 70%% { transform: scale(1.22); } 100%% { transform: scale(1); } }
                    @keyframes chat4j-copy-button-flash { 0%% { filter: none; } 35%% { filter: brightness(1.12); } 100%% { filter: none; } }
                    .message table.md-code-block { border-collapse: separate !important; border-spacing: 0 !important; margin: 10px 0 20px 0 !important; width: 100%%; border: 0 !important; border-radius: 3px !important; }
                    .message table.md-code-block tr.code-header td { background-color: %s !important; border: 1px solid %s !important; border-bottom: 0 !important; border-radius: 3px 3px 0 0 !important; padding: 3px 10px !important; color: %s !important; }
                    .message table.md-code-block tr.code-body td { background-color: %s !important; border: 1px solid %s !important; border-radius: 0 0 3px 3px !important; padding: 10px 14px !important; }
                    .message table.md-code-block.without-header tr.code-body td { border-radius: 3px !important; }
                    .message table.md-code-block pre { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.45 !important; }
                    .message table.md-code-block[data-code-language="markdown"] pre { line-height: 1.7 !important; }
                    .message table.md-code-block tr.code-header font { color: %s !important; font-family: %s !important; font-size: %dpx !important; }
                    .message code, .message pre, .message table.md-code-block tr.code-body font { font-family: %s !important; font-size: %dpx !important; line-height: 1.45; }
                    .message code.md-latex-inline, .message code:not(.md-latex-inline) { background-color: %s; border: 1px solid %s; border-radius: 4px; padding: 1px 4px; font-size: %dpx !important; }
                    .message .chat4j-math-inline { display: inline-block; max-width: none; overflow: visible; vertical-align: -0.12em; line-height: 1.2; }
                    .message .chat4j-math-inline::-webkit-scrollbar { display: none !important; width: 0 !important; height: 0 !important; }
                    .message .chat4j-math-inline .katex { font-size: 1.04em; }
                    .message .chat4j-math-display { display: block; box-sizing: border-box; width: 100%%; margin: 10px 0 16px 0; overflow-x: auto; overflow-y: hidden; padding: 2px 0 4px 0; scrollbar-color: %s %s; }
                    .message .chat4j-math-display .katex-display { margin: 0; }
                    .message .chat4j-math-display .katex { text-align: left; }
                    .message .chat4j-math-display::-webkit-scrollbar { height: 8px !important; display: block !important; background-color: %s !important; }
                    .message .chat4j-math-display::-webkit-scrollbar-track { background-color: %s !important; border-radius: 999px !important; }
                    .message .chat4j-math-display::-webkit-scrollbar-thumb { background-color: %s !important; border-radius: 999px !important; border: 2px solid %s !important; }
                    .message .chat4j-math-display::-webkit-scrollbar-thumb:hover { background-color: %s !important; }
                    .activity-box { box-sizing: border-box; position: relative; width: 100%%; border: 1px solid %s; border-radius: 10px; padding: 0; color: %s; background: transparent; }
                    .activity-box summary { cursor: pointer; list-style: none; padding: 7px 40px 7px 10px; font-weight: 600; display: flex; align-items: center; gap: 8px; }
                    .activity-copy-button { top: 4px; right: 6px; }
                    .activity-box summary::-webkit-details-marker { display: none; }
                    .activity-box summary::before { content: '›'; display: inline-flex; align-items: center; justify-content: center; width: 14px; color: %s; font-size: 16px; line-height: 1; transition: transform 120ms ease; }
                    .activity-box[open] summary { border-bottom: 1px solid %s; }
                    .activity-box[open] summary::before { transform: rotate(90deg); }
                    .activity-content { box-sizing: border-box; padding: 8px 12px 10px 24px; }
                    .activity-content .message.assistant { padding: 0; }
                    .activity-content .message > :first-child { margin-top: 0; }
                    .activity-content .message > :last-child { margin-bottom: 0; }
                    .activity-content ul, .activity-content ol { margin: 4px 0 8px 0; padding-left: 20px; }
                    .activity-content .message p { margin: 4px 0 8px 0; }
                    .activity-content .message h1, .activity-content .message h2, .activity-content .message h3 { margin-top: 8px; margin-bottom: 5px; }
                    .message-shell { position: relative; width: 100%%; }
                    .message-actions { position: absolute; right: 4px; bottom: -30px; display: flex; gap: 4px; padding: 2px; border-radius: 8px; background: %s; border: 1px solid %s; box-shadow: 0 3px 10px rgba(0,0,0,0.12); z-index: 10; opacity: 0; pointer-events: none; transform: translateY(4px) scale(0.96); transition: opacity 120ms ease, transform 140ms cubic-bezier(.2,.8,.2,1); }
                    .row.assistant .message-actions { left: 0; right: auto; }
                    .row:hover .message-actions { opacity: 1; pointer-events: auto; transform: translateY(0) scale(1); }
                    .message-action-button { width: 24px; height: 24px; border: none; border-radius: 6px; background: transparent; color: %s; line-height: 1; cursor: pointer; display: flex; align-items: center; justify-content: center; padding: 0; transition: background 120ms ease, color 120ms ease, transform 120ms ease; }
                    .message-action-button:hover { background: %s; color: %s; transform: scale(1.04); }
                    .transcript-menu { position: fixed; min-width: 232px; display: none; padding: 5px 0; border-radius: 10px; background: %s; border: 1px solid %s; box-shadow: 0 8px 24px rgba(0,0,0,0.22); z-index: 100; }
                    .transcript-menu button { display: grid; grid-template-columns: 22px 1fr auto; align-items: center; column-gap: 8px; width: 100%%; height: 28px; border: none; border-radius: 0; background: transparent; color: %s; text-align: left; padding: 3px 22px 3px 14px; font: inherit; cursor: pointer; }
                    .transcript-menu button:hover { background: %s; color: %s; }
                    .transcript-menu .label { white-space: nowrap; }
                    .transcript-menu .shortcut { margin-left: 24px; color: currentColor; opacity: 0.82; white-space: nowrap; }
                    .icon { width: 16px; height: 16px; opacity: 0.86; flex: 0 0 auto; background: currentColor; display: inline-block; }
                    .message-action-button .icon { width: 15px; height: 15px; }
                    .icon.copy { -webkit-mask: url('%s') center / contain no-repeat; mask: url('%s') center / contain no-repeat; }
                    .icon.regenerate { -webkit-mask: url('%s') center / contain no-repeat; mask: url('%s') center / contain no-repeat; }
                    .transcript-menu-separator { height: 1px; margin: 4px 8px; background: %s; }
                    a { color: %s; text-decoration: none; }
                    a:hover { text-decoration: underline; }
                    a.source-citation { display: inline-flex; align-items: center; justify-content: center; min-width: 16px; height: 18px; padding: 0 5px; border-radius: 999px; background: %s; border: 1px solid %s; color: %s; font-size: %dpx; font-weight: 600; line-height: 18px; text-decoration: none; vertical-align: baseline; }
                    .source-chip-row { display: flex; flex-wrap: wrap; gap: 3px; margin: 3px 0 6px 0; }
                    a.source-chip { display: inline-flex; align-items: center; gap: 2px; height: 20px; padding: 0 6px; border-radius: 999px; background: %s; border: 1px solid %s; color: %s; font-size: %dpx; font-weight: 600; line-height: 20px; text-decoration: none; letter-spacing: -0.01em; box-shadow: 0 1px 0 rgba(0,0,0,0.03); }
                    .source-chip-count { color: currentColor; opacity: 0.68; font-weight: 500; }
                    a.source-citation:hover, a.source-chip:hover { background: %s; color: %s; border-color: currentColor; text-decoration: none; }
                    .source-preview { position: fixed; display: none; width: min(420px, calc(100vw - 32px)); padding: 0; border-radius: 12px; background: %s; border: 1px solid %s; color: %s; box-shadow: 0 12px 32px rgba(0,0,0,0.24); z-index: 130; overflow: hidden; pointer-events: none; }
                    .source-preview.visible { display: block; animation: chat4j-source-pop 110ms cubic-bezier(.2,.8,.2,1); }
                    .source-preview-header { display: flex; align-items: center; gap: 8px; padding: 10px 12px 8px 12px; border-bottom: 1px solid %s; color: %s; }
                    .source-preview-favicon { width: 22px; height: 22px; border-radius: 999px; border: 1px solid %s; display: inline-flex; align-items: center; justify-content: center; font-size: 12px; color: %s; background: %s; flex: 0 0 auto; }
                    .source-preview-domain { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
                    .source-preview-body { padding: 10px 12px 12px 12px; }
                    .source-preview-title { font-weight: 700; margin-bottom: 6px; color: %s; }
                    .source-preview-snippet { color: %s; line-height: 1.35; }
                    @keyframes chat4j-source-pop { from { opacity: 0; transform: translateY(4px) scale(0.98); } to { opacity: 1; transform: translateY(0) scale(1); } }
                    .jump-button { position: fixed; left: 50%%; bottom: 14px; transform: translateX(-50%%); width: 44px; height: 44px; border-radius: 999px; border: 1px solid %s; background: %s; color: %s; font-size: 0; line-height: 1; display: %s; align-items: center; justify-content: center; box-shadow: 0 4px 16px rgba(0,0,0,0.18); cursor: pointer; z-index: 20; }
                    .jump-button:hover { background: %s; color: %s; }
                    .jump-button::before { content: ''; position: absolute; inset: 1px; border-radius: 999px; border: 2px solid %s; opacity: 0; animation: none; }
                    .jump-button::after { content: ''; width: 16px; height: 16px; background: currentColor; -webkit-mask: url('%s') center / contain no-repeat; mask: url('%s') center / contain no-repeat; }
                    .jump-button[data-streaming="true"].streaming::before { border-color: %s; border-top-color: %s; opacity: 1; animation: chat4j-spin 900ms linear infinite; }
                    @keyframes chat4j-spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
                  </style>
                </head>
                <body onload="if(window.chat4jRenderMath){window.chat4jRenderMath(document.querySelector('.transcript')||document.body);}">
                  <main class="transcript">%s</main>
                  <div id="chat4j-top-fade" class="chat4j-fade top"></div>
                  <div id="chat4j-bottom-fade" class="chat4j-fade bottom"></div>
                  <div id="chat4j-transcript-menu" class="transcript-menu"></div>
                  <div id="chat4j-source-preview" class="source-preview" aria-hidden="true">
                    <div class="source-preview-header"><span class="source-preview-favicon"></span><span class="source-preview-domain"></span></div>
                    <div class="source-preview-body"><div class="source-preview-title"></div><div class="source-preview-snippet"></div></div>
                  </div>
                  <div id="chat4j-scrollbar" class="chat4j-scrollbar"><div id="chat4j-scrollbar-thumb" class="chat4j-scrollbar-thumb"></div></div>
                  <button id="chat4j-jump-bottom" class="jump-button%s" data-streaming="%s" title="Jump to latest" aria-label="Jump to latest" onclick="window.scrollTo(0, document.documentElement.scrollHeight || document.body.scrollHeight || 0); if(window.chat4jUpdateJumpButton){setTimeout(window.chat4jUpdateJumpButton, 0);}"></button>
                  <script>if(window.chat4jRenderMath){window.chat4jRenderMath(document.querySelector('.transcript')||document.body);}</script>
                  %s
                </body>
                </html>
                """.formatted(
                mathHeadAssets(),
                background,
                palette.textColor(),
                palette.baseFontFamily(),
                bodyFontSize,
                scrollbarThumb,
                scrollbarHoverThumb,
                background,
                alphaCssColor(panelBackground, 0.92f),
                panelBackground.getRed(),
                panelBackground.getGreen(),
                panelBackground.getBlue(),
                background,
                alphaCssColor(panelBackground, 0.92f),
                panelBackground.getRed(),
                panelBackground.getGreen(),
                panelBackground.getBlue(),
                bubbleBackground,
                attachmentCss,
                palette.textColor(),
                palette.codeBorder(),
                palette.codeBorder(),
                palette.surfaceBg(),
                scrollbarThumb,
                scrollbarTrack,
                scrollbarTrack,
                scrollbarTrack,
                scrollbarThumb,
                scrollbarTrack,
                scrollbarHoverThumb,
                scrollbarTrack,
                tableFontSize,
                palette.codeBorder(),
                codeFontSize,
                syntaxHighlightCss,
                borderColor,
                menuBackground,
                iconColorValue,
                hoverBackground,
                hoverForeground,
                palette.inlineCodeBg(),
                palette.codeBorder(),
                palette.mutedTextColor(),
                palette.codeBg(),
                palette.codeBorder(),
                palette.mutedTextColor(),
                palette.baseFontFamily(),
                languageFontSize,
                palette.monoFontFamily(),
                codeFontSize,
                palette.inlineCodeBg(),
                palette.codeBorder(),
                codeFontSize,
                scrollbarThumb,
                scrollbarTrack,
                scrollbarTrack,
                scrollbarTrack,
                scrollbarThumb,
                scrollbarTrack,
                scrollbarHoverThumb,
                borderColor,
                palette.mutedTextColor(),
                palette.mutedTextColor(),
                borderColor,
                menuBackground,
                borderColor,
                iconColorValue,
                hoverBackground,
                hoverForeground,
                menuBackground,
                borderColor,
                palette.textColor(),
                hoverBackground,
                hoverForeground,
                COPY_ICON,
                COPY_ICON,
                REGENERATE_ICON,
                REGENERATE_ICON,
                borderColor,
                palette.linkColor(),
                sourceChipBackground,
                sourceChipBorder,
                sourceChipText,
                Math.max(10, baseBodyFontSize - 2),
                sourceChipBackground,
                sourceChipBorder,
                sourceChipText,
                Math.max(10, baseBodyFontSize - 2),
                sourceChipHoverBackground,
                sourceChipText,
                menuBackground,
                borderColor,
                palette.textColor(),
                borderColor,
                palette.mutedTextColor(),
                borderColor,
                palette.mutedTextColor(),
                palette.inlineCodeBg(),
                palette.textColor(),
                palette.mutedTextColor(),
                buttonBorder,
                buttonBackground,
                palette.textColor(),
                jumpButtonVisible ? "flex" : "none",
                hoverBackground,
                hoverForeground,
                jumpRingTrack,
                ARROW_DOWN_ICON,
                ARROW_DOWN_ICON,
                jumpRingTrack,
                jumpRing,
                entriesHtml,
                jumpButtonVisible ? " streaming" : "",
                jumpButtonVisible ? "true" : "false",
                scrollScript
        );
    }

    private void updateJumpButtonChrome() {
        String script = """
                (function() {
                  var jump = document.getElementById('chat4j-jump-bottom');
                  if (!jump) {
                    return;
                  }
                  jump.setAttribute('data-streaming', %s);
                  jump.classList.toggle('streaming', %s);
                  if (window.chat4jUpdateJumpButton) {
                    window.chat4jUpdateJumpButton();
                  } else {
                    jump.style.display = 'none';
                  }
                })();
                """.formatted(
                toJsonString(jumpButtonVisible ? "true" : "false"),
                jumpButtonVisible ? "true" : "false"
        );
        executeJavaScript(script);
    }

    private void applyDocumentUrl(long requestId, DocumentUrl documentUrl, boolean scrollToBottom) {
        if (disposed || requestId != renderRequestCounter.get()) {
            documentUrl.deleteFile();
            return;
        }
        if (!isBrowserPanelReadyForDocumentLoad()) {
            storePendingDocumentUrl(requestId, documentUrl, scrollToBottom);
            return;
        }

        pendingDocumentUrl = null;
        pendingDocumentRequestId = 0L;
        pendingDocumentScrollToBottom = false;
        loadUrl(documentUrl.url());
        replaceCurrentDocumentPath(documentUrl.path());
        documentInitialized = true;
        documentLoadPending = false;
        schedulePostReloadTranscriptUpdate(requestId, scrollToBottom);
    }

    private void applyPendingDocumentUrl() {
        if (disposed || pendingDocumentUrl == null || !isBrowserPanelReadyForDocumentLoad()) {
            return;
        }
        applyDocumentUrl(pendingDocumentRequestId, pendingDocumentUrl, pendingDocumentScrollToBottom);
    }

    private boolean isBrowserPanelReadyForDocumentLoad() {
        return browserPanel.isShowing() && browserPanel.getWidth() > 0 && browserPanel.getHeight() > 0;
    }

    private void storePendingDocumentUrl(long requestId, DocumentUrl documentUrl, boolean scrollToBottom) {
        deletePendingDocumentUrl();
        pendingDocumentUrl = documentUrl;
        pendingDocumentRequestId = requestId;
        pendingDocumentScrollToBottom = scrollToBottom;
    }

    private void deletePendingDocumentUrl() {
        if (pendingDocumentUrl != null) {
            pendingDocumentUrl.deleteFile();
        }
        pendingDocumentUrl = null;
        pendingDocumentRequestId = 0L;
        pendingDocumentScrollToBottom = false;
    }

    private void replaceCurrentDocumentPath(Path nextPath) {
        Path previousPath = currentDocumentPath;
        currentDocumentPath = nextPath;
        if (previousPath != null && !previousPath.equals(nextPath)) {
            try {
                Files.deleteIfExists(previousPath);
            } catch (Exception ignored) {
            }
        }
    }

    private void schedulePostReloadTranscriptUpdate(long reloadRequestId, boolean scrollToBottom) {
        Timer timer = new Timer(150, event -> {
            if (disposed || reloadRequestId != renderRequestCounter.get() || !documentInitialized) {
                return;
            }
            scheduleTranscriptHtmlUpdate(scrollToBottom, transcriptRenderSnapshot());
        });
        timer.setRepeats(false);
        timer.start();
    }

    private void scheduleTranscriptHtmlUpdate(boolean scrollToBottom, TranscriptRenderSnapshot snapshot) {
        if (disposed) {
            return;
        }
        long requestId = renderRequestCounter.incrementAndGet();
        renderExecutor.execute(() -> {
            if (disposed || requestId != renderRequestCounter.get()) {
                return;
            }
            String entriesHtml = renderWithSnapshotFonts(snapshot, () -> renderEntriesHtml(snapshot, requestId));
            if (entriesHtml == null) {
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (disposed || requestId != renderRequestCounter.get() || !documentInitialized) {
                    return;
                }
                updateTranscriptHtml(scrollToBottom, snapshot, entriesHtml);
            });
        });
    }

    private TranscriptRenderSnapshot transcriptRenderSnapshot() {
        Palette palette = MarkdownPaletteResolver.resolve(dark);
        int codeFontSize = CodeFontResolver.resolveCodeFontSize();
        return new TranscriptRenderSnapshot(
                entries,
                renderMode,
                dark,
                jumpButtonVisible,
                palette,
                documentChrome(dark, palette),
                codeFontSize,
                Fonts.scale(Fonts.SIZE_BODY) / (float) Fonts.SIZE_BODY
        );
    }

    private <T> T renderWithSnapshotFonts(TranscriptRenderSnapshot snapshot, Supplier<T> action) {
        return Fonts.withScaleFactor(
                snapshot.fontScaleFactor(),
                () -> CodeFontResolver.withResolvedCodeFontSize(snapshot.codeFontSize(), action)
        );
    }

    private DocumentChrome documentChrome(boolean dark, Palette palette) {
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
        return new DocumentChrome(
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
                syntaxHighlightCss()
        );
    }

    private void updateTranscriptHtml(boolean scrollToBottom, TranscriptRenderSnapshot snapshot, String entriesHtml) {
        String script = """
                (function() {
                  var transcript = document.querySelector('.transcript');
                  if (transcript) {
                    transcript.innerHTML = %s;
                  }
                  if (window.chat4jRenderMath) {
                    window.chat4jRenderMath(transcript);
                  }
                  if (window.chat4jInstallTranscriptActions) {
                    window.chat4jInstallTranscriptActions();
                  }
                  if (window.chat4jUpdateFadeOverlays) {
                    window.chat4jUpdateFadeOverlays();
                  }
                  var jump = document.getElementById('chat4j-jump-bottom');
                  if (jump) {
                    jump.setAttribute('data-streaming', %s);
                    jump.classList.toggle('streaming', %s);
                    if (window.chat4jUpdateJumpButton) {
                      window.chat4jUpdateJumpButton();
                    } else {
                      jump.style.display = 'none';
                    }
                  }
                  if (%s) {
                    window.scrollTo(0, document.documentElement.scrollHeight || document.body.scrollHeight || 0);
                  }
                })();
                """.formatted(
                toJsonString(encodeSupplementaryCodePoints(entriesHtml)),
                toJsonString(snapshot.jumpButtonVisible() ? "true" : "false"),
                snapshot.jumpButtonVisible() ? "true" : "false",
                scrollToBottom ? "true" : "false"
        );
        executeJavaScript(script);
    }

    private String renderEntriesHtml(TranscriptRenderSnapshot snapshot) {
        return snapshot.entries().stream()
                .map(entry -> renderEntrySafely(entry, snapshot))
                .collect(joining("\n"));
    }

    private String renderEntriesHtml(TranscriptRenderSnapshot snapshot, long requestId) {
        StringBuilder html = new StringBuilder();
        for (Entry entry : snapshot.entries()) {
            if (disposed || requestId != renderRequestCounter.get()) {
                return null;
            }
            if (!html.isEmpty()) {
                html.append("\n");
            }
            html.append(renderEntrySafely(entry, snapshot));
        }
        return html.toString();
    }

    private String renderEntrySafely(Entry entry, TranscriptRenderSnapshot snapshot) {
        try {
            return renderEntry(entry, snapshot);
        } catch (Exception e) {
            return renderFallbackEntry(entry);
        }
    }

    private String renderFallbackEntry(Entry entry) {
        String roleClass = entry.role() == Role.USER ? "user" : "assistant";
        String text = escapeHtml(entry.text()).replace("\n", "<br>");
        String attachments = renderAttachmentStripHtml(entry.attachments());
        return """
                <section class="row %s" data-message-index="%d">
                  <div class="message-shell">
                    %s
                    <div class="message %s">%s</div>
                  </div>
                </section>
                """.formatted(roleClass, entry.messageIndex(), attachments, roleClass, text);
    }

    private String renderEntry(Entry entry, TranscriptRenderSnapshot snapshot) {
        if (entry.kind() == EntryKind.ACTIVITY) {
            String renderedActivity = renderEntryContentHtml(Role.ASSISTANT, entry.text(), snapshot);
            Document activityDocument = Jsoup.parse(renderedActivity);
            prepareRenderedDocument(activityDocument, false);
            String activityBody = activityDocument.body() == null ? escapeHtml(entry.text()) : activityDocument.body().html();
            String content = StringUtils.isBlank(entry.text())
                    ? ""
                    : "<div class=\"activity-content\"><div class=\"message assistant\">%s</div></div>".formatted(activityBody);
            String openAttribute = entry.collapsed() ? "" : " open";
            return """
                    <section class="row activity">
                      <details class="activity-box"%s>
                        <summary>%s<button class="activity-copy-button" title="Copy activity" data-action="copy-activity"><span class="icon copy" aria-hidden="true"></span></button></summary>
                        %s
                      </details>
                    </section>
                    """.formatted(openAttribute, escapeHtml(entry.title()), content);
        }

        String rendered = renderEntryContentHtml(entry.role(), entry.text(), snapshot);
        Document document = Jsoup.parse(rendered);
        prepareRenderedDocument(document, false);
        String body = document.body() == null ? escapeHtml(entry.text()) : document.body().html();
        String roleClass = entry.role() == Role.USER ? "user" : "assistant";
        String attachments = renderAttachmentStripHtml(entry.attachments());
        String actions = entry.messageIndex() < 0
                ? ""
                : """
                  <div class="message-actions" data-message-index="%d">
                    <button class="message-action-button" title="Copy message" data-action="copy" data-message-index="%d"><span class="icon copy" aria-hidden="true"></span></button>
                    <button class="message-action-button" title="%s" data-action="regenerate" data-message-index="%d"><span class="icon regenerate" aria-hidden="true"></span></button>
                  </div>
                """.formatted(
                        entry.messageIndex(),
                        entry.messageIndex(),
                        entry.role() == Role.USER ? "Regenerate response" : "Regenerate this response",
                        entry.messageIndex()
                );
        return """
                <section class="row %s" data-message-index="%d">
                  <div class="message-shell">
                    %s
                    %s
                    <div class="message %s">%s</div>
                  </div>
                </section>
                """.formatted(roleClass, entry.messageIndex(), attachments, actions, roleClass, body);
    }

    static String renderAttachmentStripHtml(List<TranscriptAttachment> attachments) {
        List<TranscriptAttachment> safeAttachments = attachments == null ? emptyList() : attachments;
        if (safeAttachments.isEmpty()) {
            return "";
        }

        String content = safeAttachments.stream()
                .map(JcefTranscriptView::renderAttachmentHtml)
                .collect(joining(""));
        return "<div class=\"attachment-strip\">%s</div>".formatted(content);
    }

    private static String renderAttachmentHtml(TranscriptAttachment attachment) {
        return attachment == null
                ? ""
                : attachment.image() ? renderImageAttachmentHtml(attachment) : renderFileAttachmentHtml(attachment);
    }

    private static String renderImageAttachmentHtml(TranscriptAttachment attachment) {
        String imageDataUri = imageThumbnailDataUri(attachment);
        if (StringUtils.isBlank(imageDataUri)) {
            return renderUnavailableAttachmentHtml(attachment, "🖼", "Image unavailable");
        }

        String title = escapeHtmlAttribute(displayName(attachment));
        String path = escapeHtmlAttribute(attachment.storagePath());
        return """
                <button class="attachment-image-button" type="button" data-action="open-attachment" data-attachment-path="%s" title="%s">
                  <img class="attachment-image" src="%s" alt="%s">
                </button>
                """.formatted(path, title, imageDataUri, title);
    }

    private static String renderFileAttachmentHtml(TranscriptAttachment attachment) {
        boolean available = isAttachmentAvailable(attachment);
        String className = available ? "attachment-chip" : "attachment-chip unavailable";
        String actionAttributes = available
                ? " data-action=\"open-attachment\" data-attachment-path=\"%s\"".formatted(escapeHtmlAttribute(attachment.storagePath()))
                : " disabled";
        String size = attachment.sizeBytes() > 0 ? formatAttachmentSize(attachment.sizeBytes()) : "";
        String sizeHtml = StringUtils.isBlank(size) ? "" : "<span class=\"attachment-size\">%s</span>".formatted(escapeHtml(size));
        return """
                <button class="%s" type="button"%s title="%s">
                  <span class="attachment-icon" aria-hidden="true">📄</span>
                  <span class="attachment-name">%s</span>
                  %s
                </button>
                """.formatted(
                className,
                actionAttributes,
                escapeHtmlAttribute(displayName(attachment)),
                escapeHtml(displayName(attachment)),
                sizeHtml
        );
    }

    private static String renderUnavailableAttachmentHtml(TranscriptAttachment attachment, String icon, String status) {
        String size = attachment.sizeBytes() > 0 ? formatAttachmentSize(attachment.sizeBytes()) : status;
        return """
                <span class="attachment-chip unavailable" title="%s">
                  <span class="attachment-icon" aria-hidden="true">%s</span>
                  <span class="attachment-name">%s</span>
                  <span class="attachment-size">%s</span>
                </span>
                """.formatted(
                escapeHtmlAttribute(status),
                escapeHtml(icon),
                escapeHtml(displayName(attachment)),
                escapeHtml(size)
        );
    }

    private static String imageThumbnailDataUri(TranscriptAttachment attachment) {
        Path path = attachmentPath(attachment);
        if (path == null || !Files.exists(path)) {
            return "";
        }

        try {
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) {
                return "";
            }
            BufferedImage thumbnail = renderThumbnail(image);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(thumbnail, "png", output);
            String encoded = Base64.getEncoder().encodeToString(output.toByteArray());
            return "data:image/png;base64,%s".formatted(encoded);
        } catch (Exception e) {
            return "";
        }
    }

    private static BufferedImage renderThumbnail(BufferedImage source) {
        double scale = Math.min(
                1.0,
                Math.min(
                        ATTACHMENT_IMAGE_MAX_WIDTH / (double) source.getWidth(),
                        ATTACHMENT_IMAGE_MAX_HEIGHT / (double) source.getHeight()
                )
        );
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage thumbnail = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = thumbnail.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return thumbnail;
    }

    private static boolean isAttachmentAvailable(TranscriptAttachment attachment) {
        Path path = attachmentPath(attachment);
        return path != null && Files.exists(path);
    }

    private static Path attachmentPath(TranscriptAttachment attachment) {
        if (attachment == null || StringUtils.isBlank(attachment.storagePath())) {
            return null;
        }
        try {
            return Path.of(attachment.storagePath());
        } catch (Exception e) {
            return null;
        }
    }

    private static String displayName(TranscriptAttachment attachment) {
        String fallback = attachment.image() ? "image" : "attachment";
        if (StringUtils.isNotBlank(attachment.originalName())) {
            return attachment.originalName();
        }
        Path path = attachmentPath(attachment);
        if (path != null && path.getFileName() != null) {
            return path.getFileName().toString();
        }
        return fallback;
    }

    private static String formatAttachmentSize(long bytes) {
        if (bytes < 1_000) {
            return "%d B".formatted(bytes);
        }
        if (bytes < 1_000_000) {
            return "%.1f kB".formatted(bytes / 1_000.0);
        }
        if (bytes < 1_000_000_000) {
            return "%.1f MB".formatted(bytes / 1_000_000.0);
        }
        return "%.1f GB".formatted(bytes / 1_000_000_000.0);
    }

    private String renderEntryContentHtml(Role role, String text, TranscriptRenderSnapshot snapshot) {
        return snapshot.renderMode() == RenderMode.MARKDOWN
                ? renderRawMarkdownSourceHtml(text, snapshot)
                : messageHtmlRenderer.render(role, snapshot.renderMode(), text, snapshot.dark(), snapshot.palette());
    }

    private String renderRawMarkdownSourceHtml(String text, TranscriptRenderSnapshot snapshot) {
        Palette palette = snapshot.palette();
        int languageFontSize = snapshot.chrome().languageFontSize();
        int codeFontSize = snapshot.codeFontSize();
        String source = escapeHtml(StringUtils.defaultString(text));
        return """
                <html><body><table class="md-code-block" data-code-language="markdown" width="100%%" cellpadding="0" cellspacing="0" border="0" style="margin: 6px 0;">
                  <tr><td bgcolor="%s" style="border: 1px solid %s; border-bottom: none; padding: 2px 8px; font-size: %dpx;"><font face="%s" color="%s">markdown</font></td></tr>
                  <tr><td bgcolor="%s" style="border: 1px solid %s; padding: 8px 12px;"><pre style="margin: 0;"><font face="%s" color="%s" style="font-size: %dpx;">%s</font></pre></td></tr>
                </table></body></html>
                """.formatted(
                palette.codeHeaderBg(),
                palette.hrColor(),
                languageFontSize,
                palette.baseFontFamilyAttr(),
                palette.mutedTextColor(),
                palette.codeBg(),
                palette.hrColor(),
                palette.monoFontFamilyAttr(),
                palette.codeText(),
                codeFontSize,
                source
        );
    }

    private void prepareRenderedDocument(Document document, boolean replaceInlineCitationsWithChips) {
        renderCodeHighlights(document);
        renderMathFallbacks(document);
        document.select("table.md-table").wrap("<div class=\"table-wrap\"></div>");
        annotateSourceLinks(document, replaceInlineCitationsWithChips);
        removeAdjacentDuplicateSourceCitations(document);
        document.select("table.md-code-block").forEach(table -> {
            var rows = table.select("tr");
            if (rows.size() > 1) {
                table.addClass("with-header");
                rows.first().addClass("code-header");
                rows.last().addClass("code-body");
                return;
            }
            table.addClass("without-header");
            rows.addClass("code-body");
        });
    }

    static void renderCodeHighlights(Document document) {
        document.select("table.md-code-block:not(.md-latex-block)").forEach(table -> {
            String language = StringUtils.defaultIfBlank(table.attr("data-code-language"), codeBlockHeaderText(table));
            Element pre = table.selectFirst("pre");
            if (pre == null) {
                return;
            }

            HIGHLIGHT_RENDERER.render(pre.wholeText(), language).ifPresent(html -> {
                String normalizedLanguage = HIGHLIGHT_RENDERER.normalizeLanguage(language);
                pre.html(html);
                pre.addClass("hljs");
                pre.addClass("language-%s".formatted(normalizedLanguage));
                pre.attr("data-chat4j-highlighted", "server");
            });
        });
    }

    private static String codeBlockHeaderText(Element table) {
        var rows = table.select("tr");
        if (rows.size() <= 1) {
            return "";
        }
        Element header = rows.first();
        return header == null ? "" : header.text();
    }

    static void renderMathFallbacks(Document document) {
        document.select("code.md-latex-inline").forEach(code ->
                KATEX_RENDERER.render(code.text(), false).ifPresent(html -> {
                    Element replacement = new Element("span")
                            .addClass("chat4j-math-inline")
                            .attr("data-chat4j-math-rendered", "server");
                    replacement.html(html);
                    code.replaceWith(replacement);
                })
        );

        document.select("table.md-latex-block").forEach(table -> {
            Element pre = table.selectFirst("pre");
            if (pre == null) {
                return;
            }
            KATEX_RENDERER.render(pre.text(), true).ifPresent(html -> {
                Element replacement = new Element("div")
                        .addClass("chat4j-math-display")
                        .attr("data-chat4j-math-rendered", "server");
                replacement.html(html);
                table.replaceWith(replacement);
            });
        });
    }

    private void annotateSourceLinks(Document document, boolean replaceInlineCitationsWithChips) {
        Map<String, SourcePreview> previewsByUrl = new LinkedHashMap<>();
        document.select("a[href]").stream()
                .filter(anchor -> isHttpUrl(anchor.attr("href")))
                .forEach(anchor -> previewsByUrl.merge(anchor.attr("href"), sourcePreview(anchor), this::preferSourcePreview));

        document.select("a[href]").forEach(anchor -> {
            SourcePreview preview = previewsByUrl.get(anchor.attr("href"));
            if (preview == null) {
                return;
            }

            anchor.addClass("source-link");
            if (isCitationAnchor(anchor)) {
                if (replaceInlineCitationsWithChips && !isInsideSourcesList(anchor)) {
                    applyInlineSourceChip(anchor, preview);
                } else {
                    anchor.addClass("source-citation");
                    anchor.text(citationNumber(anchor.text()));
                }
            }
            anchor.attr("data-source-title", preview.title());
            anchor.attr("data-source-domain", preview.domain());
            anchor.attr("data-source-url", preview.url());
            anchor.attr("data-source-snippet", preview.snippet());
        });
    }

    static void removeAdjacentDuplicateSourceCitations(Document document) {
        document.select("a.source-citation").forEach(anchor -> {
            Element previous = previousSourceCitation(anchor);
            if (previous == null || !sameCitation(previous, anchor)) {
                return;
            }

            removeBlankPreviousSibling(anchor);
            anchor.remove();
        });
    }

    private static Element previousSourceCitation(Element anchor) {
        Node previous = anchor.previousSibling();
        while (previous instanceof TextNode textNode && StringUtils.isBlank(textNode.text())) {
            previous = previous.previousSibling();
        }
        return previous instanceof Element element && element.hasClass("source-citation") ? element : null;
    }

    private static boolean sameCitation(Element first, Element second) {
        String firstUrl = StringUtils.defaultIfBlank(first.attr("data-source-url"), first.attr("href"));
        String secondUrl = StringUtils.defaultIfBlank(second.attr("data-source-url"), second.attr("href"));
        return Strings.CS.equals(firstUrl, secondUrl) && Strings.CS.equals(first.text(), second.text());
    }

    private static void removeBlankPreviousSibling(Element anchor) {
        Node previous = anchor.previousSibling();
        if (previous instanceof TextNode textNode && StringUtils.isBlank(textNode.text())) {
            textNode.remove();
        }
    }

    private boolean isInsideSourcesList(Element anchor) {
        Element listItem = anchor.closest("li");
        if (listItem == null || listItem.parent() == null) {
            return false;
        }
        Element previous = listItem.parent().previousElementSibling();
        if (previous == null) {
            return false;
        }
        String heading = StringUtils.removeEnd(StringUtils.defaultString(previous.text()).trim(), ":");
        return Strings.CI.equals(heading, "Sources");
    }

    private void applyInlineSourceChip(Element anchor, SourcePreview preview) {
        anchor.removeClass("source-citation");
        anchor.addClass("source-chip");
        anchor.empty();
        anchor.appendElement("span").addClass("source-chip-label").text(sourceChipLabel(preview.domain()));
    }

    private String sourceChipLabel(String domain) {
        String value = StringUtils.defaultIfBlank(domain, "source");
        String[] parts = value.split("\\.");
        if (parts.length >= 2 && parts[parts.length - 1].length() <= 3) {
            return parts[parts.length - 2];
        }
        return parts.length == 0 ? value : parts[0];
    }

    private SourcePreview preferSourcePreview(SourcePreview existing, SourcePreview candidate) {
        boolean existingNumeric = isCitationText(existing.title());
        boolean candidateNumeric = isCitationText(candidate.title());
        if (existingNumeric && !candidateNumeric) {
            return candidate;
        }
        if (!candidateNumeric && candidate.snippet().length() > existing.snippet().length()) {
            return candidate;
        }
        return existing;
    }

    private boolean isCitationAnchor(Element anchor) {
        return isCitationText(anchor.text());
    }

    private String citationNumber(String text) {
        return stripCitationBrackets(text);
    }

    private boolean isCitationText(String text) {
        return stripCitationBrackets(text).matches("\\d+");
    }

    private String stripCitationBrackets(String text) {
        return Strings.CS.removeEnd(Strings.CS.removeStart(StringUtils.trimToEmpty(text), "["), "]");
    }

    private SourcePreview sourcePreview(Element anchor) {
        String href = anchor.attr("href");
        String title = StringUtils.defaultIfBlank(anchor.text(), sourceDomain(href));
        String domain = sourceDomain(href);
        String context = sourceContext(anchor);
        String snippet = sourceSnippet(context, title, href);
        return new SourcePreview(title, domain, href, snippet);
    }

    private String sourceContext(Element anchor) {
        Element listItem = anchor.closest("li");
        if (listItem != null) {
            return listItem.text();
        }
        Element paragraph = anchor.closest("p");
        return paragraph == null ? anchor.parent() == null ? anchor.text() : anchor.parent().text() : paragraph.text();
    }

    private String sourceSnippet(String context, String title, String url) {
        String snippet = StringUtils.defaultString(context)
                .replaceFirst("^\\s*\\d+\\.\\s*", "")
                .trim();
        if (Strings.CI.startsWith(snippet, title)) {
            snippet = snippet.substring(title.length()).trim();
        }
        snippet = snippet.replaceFirst("^[—–-]\\s*", "").trim();
        if (StringUtils.isBlank(snippet) || Strings.CS.equals(snippet, title)) {
            snippet = url;
        }
        return StringUtils.abbreviate(snippet, 260);
    }

    private boolean isHttpUrl(String href) {
        return Strings.CI.startsWith(href, "http://") || Strings.CI.startsWith(href, "https://");
    }

    private String sourceDomain(String url) {
        try {
            String host = URI.create(url).getHost();
            return StringUtils.defaultIfBlank(Strings.CS.removeStart(host, "www."), url);
        } catch (Exception e) {
            return url;
        }
    }

    private record SourcePreview(String title, String domain, String url, String snippet) {
        @Override
        public String toString() {
            return "SourcePreview[title=%s, domain=%s, url=<masked>, snippet=<masked>]".formatted(title, domain);
        }
    }

    private String bridgeScript() {
        return """
                (function () {
                    function closest(node, selector) {
                        if (!node) {
                            return null;
                        }
                        if (node.closest) {
                            return node.closest(selector);
                        }
                        while (node && node.tagName) {
                            if (matches(node, selector)) {
                                return node;
                            }
                            node = node.parentNode;
                        }
                        return null;
                    }
                    function matches(node, selector) {
                        var matcher = node.matches || node.msMatchesSelector || node.webkitMatchesSelector;
                        return matcher ? matcher.call(node, selector) : false;
                    }
                    function selectedText() {
                        var selection = window.getSelection ? window.getSelection() : null;
                        return selection ? String(selection.toString()) : '';
                    }
                    function dispatchTranscriptAction(action, messageIndex, text) {
                        var payloadText = text || '';
                        if (window.chat4jTranscriptAction && (messageIndex >= 0 || payloadText.length > 0)) {
                            window.chat4jTranscriptAction(action, String(messageIndex), payloadText);
                        }
                    }
                    function animateCopyButton(button) {
                        if (!button) {
                            return;
                        }
                        button.classList.remove('copy-flash');
                        void button.offsetWidth;
                        button.classList.add('copy-flash');
                        setTimeout(function() {
                            button.classList.remove('copy-flash');
                        }, 460);
                    }
                    function hideTranscriptMenu() {
                        var menu = document.getElementById('chat4j-transcript-menu');
                        if (menu) {
                            menu.style.display = 'none';
                        }
                    }
                    function hideSourcePreview() {
                        var preview = document.getElementById('chat4j-source-preview');
                        if (!preview) {
                            return;
                        }
                        preview.classList.remove('visible');
                        preview.setAttribute('aria-hidden', 'true');
                    }
                    function sourceInitial(domain) {
                        var value = String(domain || '').trim();
                        return value.length > 0 ? value.charAt(0).toUpperCase() : '↗';
                    }
                    function showSourcePreview(anchor, event) {
                        var preview = document.getElementById('chat4j-source-preview');
                        if (!preview || !anchor) {
                            return;
                        }
                        preview.querySelector('.source-preview-favicon').textContent = sourceInitial(anchor.getAttribute('data-source-domain'));
                        preview.querySelector('.source-preview-domain').textContent = anchor.getAttribute('data-source-domain') || '';
                        preview.querySelector('.source-preview-title').textContent = anchor.getAttribute('data-source-title') || anchor.textContent || '';
                        preview.querySelector('.source-preview-snippet').textContent = anchor.getAttribute('data-source-snippet') || '';
                        preview.classList.add('visible');
                        preview.setAttribute('aria-hidden', 'false');
                        positionSourcePreview(event || { clientX: anchor.getBoundingClientRect().left, clientY: anchor.getBoundingClientRect().bottom });
                    }
                    function positionSourcePreview(event) {
                        var preview = document.getElementById('chat4j-source-preview');
                        if (!preview || !preview.classList.contains('visible')) {
                            return;
                        }
                        var margin = 12;
                        var rect = preview.getBoundingClientRect();
                        var x = Math.min(Math.max(margin, event.clientX + 14), Math.max(margin, window.innerWidth - rect.width - margin));
                        var y = event.clientY + 16;
                        if (y + rect.height + margin > window.innerHeight) {
                            y = Math.max(margin, event.clientY - rect.height - 14);
                        }
                        preview.style.left = x + 'px';
                        preview.style.top = y + 'px';
                    }
                    function showTranscriptMenu(event, row) {
                        var menu = document.getElementById('chat4j-transcript-menu');
                        if (!menu || !row) {
                            return;
                        }
                        var messageIndex = Number(row.getAttribute('data-message-index'));
                        if (messageIndex < 0) {
                            return;
                        }
                        var regenerateLabel = row.classList.contains('user') ? 'Regenerate Response' : 'Regenerate This Response';
                        var selection = selectedText().trim();
                        var selectedCopy = selection.length > 0
                                ? '<button data-action="copy-selected"><span class="icon copy" aria-hidden="true"></span><span class="label">Copy Selected Text</span><span class="shortcut">⌘C</span></button><div class="transcript-menu-separator"></div>'
                                : '';
                        menu.setAttribute('data-selected-text', selection);
                        menu.innerHTML = selectedCopy
                                + '<button data-action="copy"><span class="icon copy" aria-hidden="true"></span><span class="label">Copy Message</span><span class="shortcut"></span></button>'
                                + '<div class="transcript-menu-separator"></div>'
                                + '<button data-action="regenerate"><span class="icon regenerate" aria-hidden="true"></span><span class="label">' + regenerateLabel + '</span><span class="shortcut"></span></button>';
                        Array.prototype.forEach.call(menu.querySelectorAll('button[data-action]'), function(button) {
                            button.onclick = function(clickEvent) {
                                clickEvent.preventDefault();
                                clickEvent.stopPropagation();
                                var action = button.getAttribute('data-action');
                                var text = action === 'copy-selected' ? menu.getAttribute('data-selected-text') : '';
                                dispatchTranscriptAction(action, messageIndex, text);
                                hideTranscriptMenu();
                            };
                        });
                        menu.style.left = Math.min(event.clientX, Math.max(0, window.innerWidth - 240)) + 'px';
                        menu.style.top = Math.min(event.clientY, Math.max(0, window.innerHeight - 120)) + 'px';
                        menu.style.display = 'block';
                    }
                    function installCodeCopyButtons() {
                        Array.prototype.forEach.call(document.querySelectorAll('table.md-code-block'), function(table) {
                            if (table.parentNode && table.parentNode.classList && table.parentNode.classList.contains('code-block-shell')) {
                                return;
                            }
                            var shell = document.createElement('div');
                            shell.className = 'code-block-shell';
                            table.parentNode.insertBefore(shell, table);
                            shell.appendChild(table);
                            var button = document.createElement('button');
                            button.className = 'code-copy-button';
                            button.title = 'Copy code';
                            button.innerHTML = '<span class="icon copy" aria-hidden="true"></span>';
                            button.onclick = function(event) {
                                var code = table.querySelector('tr.code-body pre') || table.querySelector('pre') || table;
                                dispatchTranscriptAction('copy-text', -1, String(code.textContent || ''));
                                animateCopyButton(button);
                                event.preventDefault();
                                event.stopPropagation();
                            };
                            shell.appendChild(button);
                        });
                    }
                    function scrollRoot() {
                        return document.scrollingElement || document.documentElement || document.body;
                    }
                    function updateFadeOverlays() {
                        var root = scrollRoot();
                        var topFade = document.getElementById('chat4j-top-fade');
                        var bottomFade = document.getElementById('chat4j-bottom-fade');
                        if (!root || !topFade || !bottomFade) {
                            return;
                        }
                        var scrollHeight = Math.max(root.scrollHeight, document.body ? document.body.scrollHeight : 0);
                        var clientHeight = Math.max(root.clientHeight || 0, window.innerHeight || 0);
                        var scrollTop = root.scrollTop || 0;
                        var maxScroll = Math.max(0, scrollHeight - clientHeight);
                        topFade.classList.toggle('visible', scrollTop > 3);
                        bottomFade.classList.toggle('visible', maxScroll - scrollTop > 3);
                    }
                    window.chat4jUpdateFadeOverlays = updateFadeOverlays;
                    function updateJumpButtonVisibility() {
                        var root = scrollRoot();
                        var jump = document.getElementById('chat4j-jump-bottom');
                        if (!root || !jump) {
                            return;
                        }
                        var scrollHeight = Math.max(root.scrollHeight, document.body ? document.body.scrollHeight : 0);
                        var clientHeight = Math.max(root.clientHeight || 0, window.innerHeight || 0);
                        var maxScroll = Math.max(0, scrollHeight - clientHeight);
                        var scrollTop = root.scrollTop || 0;
                        var streaming = jump.getAttribute('data-streaming') === 'true';
                        var atBottom = maxScroll - scrollTop <= 3;
                        jump.classList.toggle('streaming', streaming && !atBottom);
                        jump.style.display = atBottom ? 'none' : 'flex';
                    }
                    window.chat4jUpdateJumpButton = updateJumpButtonVisibility;
                    function updateCustomScrollbar() {
                        var root = scrollRoot();
                        var track = document.getElementById('chat4j-scrollbar');
                        var thumb = document.getElementById('chat4j-scrollbar-thumb');
                        updateFadeOverlays();
                        updateJumpButtonVisibility();
                        if (!root || !track || !thumb) {
                            return;
                        }
                        var scrollHeight = Math.max(root.scrollHeight, document.body ? document.body.scrollHeight : 0);
                        var clientHeight = Math.max(root.clientHeight || 0, window.innerHeight || 0);
                        if (scrollHeight <= clientHeight + 1) {
                            track.classList.add('hidden');
                            return;
                        }
                        track.classList.remove('hidden');
                        var trackHeight = track.clientHeight;
                        var thumbHeight = Math.max(32, Math.round(trackHeight * clientHeight / scrollHeight));
                        var maxTop = Math.max(0, trackHeight - thumbHeight);
                        var maxScroll = Math.max(1, scrollHeight - clientHeight);
                        var top = Math.round(maxTop * (root.scrollTop || 0) / maxScroll);
                        thumb.style.height = thumbHeight + 'px';
                        thumb.style.transform = 'translateY(' + top + 'px)';
                    }
                    function installCustomScrollbar() {
                        var track = document.getElementById('chat4j-scrollbar');
                        var thumb = document.getElementById('chat4j-scrollbar-thumb');
                        if (!track || !thumb || track.getAttribute('data-installed') === 'true') {
                            updateCustomScrollbar();
                            return;
                        }
                        track.setAttribute('data-installed', 'true');
                        var dragging = false;
                        var dragStartY = 0;
                        var dragStartScrollTop = 0;
                        thumb.addEventListener('mousedown', function(event) {
                            dragging = true;
                            dragStartY = event.clientY;
                            dragStartScrollTop = scrollRoot().scrollTop || 0;
                            track.classList.add('dragging');
                            event.preventDefault();
                            event.stopPropagation();
                        }, true);
                        track.addEventListener('mousedown', function(event) {
                            if (event.target === thumb) {
                                return;
                            }
                            var root = scrollRoot();
                            var rect = track.getBoundingClientRect();
                            var thumbTop = thumb.getBoundingClientRect().top - rect.top;
                            var direction = event.clientY < rect.top + thumbTop ? -1 : 1;
                            root.scrollTop += direction * Math.max(80, root.clientHeight * 0.85);
                            updateCustomScrollbar();
                            event.preventDefault();
                            event.stopPropagation();
                        }, true);
                        document.addEventListener('mousemove', function(event) {
                            if (!dragging) {
                                return;
                            }
                            var root = scrollRoot();
                            var trackHeight = Math.max(1, track.clientHeight - thumb.clientHeight);
                            var scrollRange = Math.max(1, root.scrollHeight - root.clientHeight);
                            root.scrollTop = dragStartScrollTop + ((event.clientY - dragStartY) / trackHeight) * scrollRange;
                            updateCustomScrollbar();
                            event.preventDefault();
                        }, true);
                        document.addEventListener('mouseup', function() {
                            dragging = false;
                            track.classList.remove('dragging');
                        }, true);
                        window.addEventListener('scroll', updateCustomScrollbar, true);
                        window.addEventListener('resize', updateCustomScrollbar);
                        setTimeout(updateCustomScrollbar, 0);
                    }
                    window.chat4jInstallTranscriptActions = function() {
                        hideTranscriptMenu();
                        if (window.chat4jRenderMath) {
                            window.chat4jRenderMath(document.querySelector('.transcript') || document.body);
                        }
                        installCodeCopyButtons();
                        installCustomScrollbar();
                    };
                    window.addEventListener('load', function() {
                        if (window.chat4jRenderMath) {
                            window.chat4jRenderMath(document.querySelector('.transcript') || document.body);
                        }
                        installCodeCopyButtons();
                        installCustomScrollbar();
                        setTimeout(function() {
                            installCodeCopyButtons();
                            updateCustomScrollbar();
                        }, 50);
                    });
                    document.addEventListener('mouseover', function(event) {
                        var sourceLink = closest(event.target, 'a[data-source-url]');
                        if (sourceLink) {
                            showSourcePreview(sourceLink, event);
                        }
                    }, true);
                    document.addEventListener('mousemove', function(event) {
                        positionSourcePreview(event);
                    }, true);
                    document.addEventListener('mouseout', function(event) {
                        var sourceLink = closest(event.target, 'a[data-source-url]');
                        if (sourceLink) {
                            hideSourcePreview();
                        }
                    }, true);
                    document.addEventListener('focusin', function(event) {
                        var sourceLink = closest(event.target, 'a[data-source-url]');
                        if (sourceLink) {
                            showSourcePreview(sourceLink, event);
                        }
                    }, true);
                    document.addEventListener('focusout', function(event) {
                        if (closest(event.target, 'a[data-source-url]')) {
                            hideSourcePreview();
                        }
                    }, true);
                    document.addEventListener('click', function (event) {
                        hideSourcePreview();
                        var attachmentButton = closest(event.target, '[data-action="open-attachment"][data-attachment-path]');
                        if (attachmentButton) {
                            event.preventDefault();
                            event.stopPropagation();
                            dispatchTranscriptAction('open-attachment', -1, attachmentButton.getAttribute('data-attachment-path') || '');
                            return;
                        }
                        var activityCopyButton = closest(event.target, 'button[data-action="copy-activity"]');
                        if (activityCopyButton) {
                            var activityBox = closest(activityCopyButton, '.activity-box');
                            var activityContent = activityBox ? activityBox.querySelector('.activity-content') : null;
                            var activitySummary = activityBox ? activityBox.querySelector('summary') : null;
                            var text = activityContent && String(activityContent.textContent || '').trim().length > 0
                                    ? activityContent.textContent
                                    : (activitySummary ? activitySummary.textContent : '');
                            dispatchTranscriptAction('copy-text', -1, String(text || ''));
                            animateCopyButton(activityCopyButton);
                            event.preventDefault();
                            event.stopPropagation();
                            return;
                        }
                        var actionButton = closest(event.target, 'button[data-action][data-message-index]');
                        if (actionButton) {
                            event.preventDefault();
                            event.stopPropagation();
                            dispatchTranscriptAction(
                                    actionButton.getAttribute('data-action'),
                                    Number(actionButton.getAttribute('data-message-index')),
                                    ''
                            );
                            if (actionButton.getAttribute('data-action') === 'copy') {
                                animateCopyButton(actionButton);
                            }
                            return;
                        }
                        hideTranscriptMenu();
                        var anchor = closest(event.target, 'a[href]');
                        if (!anchor) {
                            return;
                        }
                        event.preventDefault();
                        event.stopPropagation();
                        if (window.chat4jOpenExternalLink) {
                            window.chat4jOpenExternalLink(anchor.href);
                        }
                    }, true);
                    document.addEventListener('contextmenu', function (event) {
                        var row = closest(event.target, '.row[data-message-index]');
                        if (!row) {
                            hideTranscriptMenu();
                            return;
                        }
                        event.preventDefault();
                        event.stopPropagation();
                        showTranscriptMenu(event, row);
                    }, true);
                })();
                """;
    }

    private String attachmentCss(DocumentChrome chrome, Palette palette) {
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

    private String syntaxHighlightCss() {
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

    private Color userBubbleColor(Color panelBackground) {
        float[] hsb = Color.RGBtoHSB(panelBackground.getRed(), panelBackground.getGreen(), panelBackground.getBlue(), null);
        boolean darkTheme = hsb[2] <= 0.5f;
        float brightness = clamp(hsb[2] + (darkTheme ? 0.10f : -0.04f));
        float saturation = clamp(hsb[1] + (darkTheme ? -0.02f : 0.02f));
        return Color.getHSBColor(hsb[0], saturation, brightness);
    }

    private Color uiManagerColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color == null ? fallback : color;
    }

    private Color blend(Color base, Color overlay, float ratio) {
        float safeRatio = clamp(ratio);
        float inverse = 1f - safeRatio;
        int red = Math.round(base.getRed() * inverse + overlay.getRed() * safeRatio);
        int green = Math.round(base.getGreen() * inverse + overlay.getGreen() * safeRatio);
        int blue = Math.round(base.getBlue() * inverse + overlay.getBlue() * safeRatio);
        return new Color(red, green, blue);
    }

    private String cssColor(Color color) {
        return "#%02x%02x%02x".formatted(color.getRed(), color.getGreen(), color.getBlue());
    }

    private String alphaCssColor(Color color, float alpha) {
        return "rgba(%d,%d,%d,%.3f)".formatted(color.getRed(), color.getGreen(), color.getBlue(), clamp(alpha));
    }

    private String alphaCssColor(Color color) {
        return "rgba(%d,%d,%d,%.3f)".formatted(color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha() / 255f);
    }

    private Color alphaColor(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private String toJsonString(String value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(StringUtils.defaultString(value));
        } catch (Exception e) {
            return "\"\"";
        }
    }

    private DocumentUrl toDocumentUrl(String html) {
        String url = "https://chat4j.local/transcript/%s.html".formatted(UUID.randomUUID());
        htmlByUrl.put(url, encodeSupplementaryCodePoints(html));
        return new DocumentUrl(url, null);
    }

    private String htmlForUrl(String url) {
        return htmlByUrl.get(url);
    }

    private boolean isInternalUrl(String url) {
        try {
            URI uri = new URI(StringUtils.defaultString(url));
            return Strings.CI.equals(uri.getScheme(), "https") && Strings.CI.equals(uri.getHost(), "chat4j.local");
        } catch (Exception e) {
            return false;
        }
    }

    private String injectJcefBridge(String html) {
        return html.replace("</head>", "<script>" + jcefCallbackScript() + bridgeScript() + "</script>\n</head>");
    }

    private String jcefCallbackScript() {
        return """
                (function() {
                    window.chat4jOpenExternalLink = function(link) {
                        if (window.chat4jJcefQuery) {
                            window.chat4jJcefQuery({request: JSON.stringify({type: 'open-link', args: [String(link || '')]})});
                        }
                    };
                    window.chat4jTranscriptAction = function(action, messageIndex, text) {
                        if (window.chat4jJcefQuery) {
                            window.chat4jJcefQuery({request: JSON.stringify({type: 'transcript-action', args: [String(action || ''), Number(messageIndex || -1), String(text || '')]})});
                        }
                    };
                })();
                """;
    }

    private void loadUrl(String url) {
        ensureBrowser(url);
        if (browser != null) {
            browser.loadURL(url);
        }
    }

    private void ensureBrowser(String initialUrl) {
        if (browser != null || disposed) {
            return;
        }
        try {
            cefClient = JcefRuntime.getInstance().createClient();
            messageRouter = createMessageRouter();
            cefClient.addMessageRouter(messageRouter);
            cefClient.addRequestHandler(new TranscriptRequestHandler());
            browser = cefClient.createBrowser(initialUrl, false, false);
            Component browserComponent = browser.getUIComponent();
            browserComponent.setPreferredSize(new Dimension(800, 600));
            browserComponent.setMinimumSize(new Dimension(320, 220));
            browserPanel.removeAll();
            browserPanel.add(browserComponent, BorderLayout.CENTER);
            browserPanel.revalidate();
            browserPanel.repaint();
            repairBrowserLayout();
        } catch (Throwable t) {
            browserPanel.removeAll();
            browserPanel.add(new JLabel("JCEF transcript failed to start", SwingConstants.CENTER), BorderLayout.CENTER);
            browserPanel.revalidate();
            browserPanel.repaint();
        }
    }

    private CefMessageRouter createMessageRouter() {
        CefMessageRouter.CefMessageRouterConfig config = new CefMessageRouter.CefMessageRouterConfig(
                "chat4jJcefQuery",
                "chat4jJcefQueryCancel"
        );
        CefMessageRouter router = CefMessageRouter.create(config);
        router.addHandler(new CefMessageRouterHandlerAdapter() {
            @Override
            public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId, String request, boolean persistent, CefQueryCallback callback) {
                handleBridgeQuery(request);
                callback.success("");
                return true;
            }
        }, true);
        return router;
    }

    private void handleBridgeQuery(String request) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(StringUtils.defaultString(request));
            String type = node.path("type").asText("");
            JsonNode args = node.path("args");
            if (Strings.CS.equals(type, "open-link")) {
                String link = args.isArray() && !args.isEmpty() ? args.get(0).asText("") : "";
                ExternalLinkSupport.openExternalLink(link);
                return;
            }
            if (Strings.CS.equals(type, "transcript-action") && actionListener != null && args.isArray() && args.size() >= 2) {
                actionListener.handle(
                        args.get(0).asText(""),
                        args.get(1).asInt(-1),
                        args.size() >= 3 ? args.get(2).asText("") : ""
                );
            }
        } catch (Exception ignored) {
            // Ignore malformed browser bridge payloads.
        }
    }

    private void executeJavaScript(String script) {
        if (browser == null || disposed) {
            return;
        }
        browser.executeJavaScript(script, browser.getURL(), 0);
    }

    private void installBrowserResizeWorkaround() {
        Timer repairTimer = new Timer(180, event -> repairBrowserLayout());
        repairTimer.setRepeats(false);
        browserPanel.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                applyPendingDocumentUrl();
                repairTimer.restart();
            }

            @Override
            public void componentMoved(ComponentEvent e) {
                repairTimer.restart();
            }
        });
    }

    private void repairBrowserLayout() {
        if (browser == null || !browserPanel.isShowing()) {
            return;
        }
        Component uiComponent = browser.getUIComponent();
        uiComponent.setBounds(0, 0, Math.max(0, browserPanel.getWidth()), Math.max(0, browserPanel.getHeight()));
        uiComponent.invalidate();
        browserPanel.revalidate();
        browserPanel.repaint();
        if (SystemInfo.isMacOS) {
            SwingUtilities.invokeLater(() -> {
                uiComponent.setBounds(0, 0, Math.max(0, browserPanel.getWidth()), Math.max(0, browserPanel.getHeight()));
                browserPanel.revalidate();
                browserPanel.repaint();
            });
        }
    }

    private void closeBrowser() {
        try {
            if (browser != null) {
                browser.stopLoad();
                browser.setCloseAllowed();
                Component uiComponent = browser.getUIComponent();
                Container parent = uiComponent.getParent();
                if (parent != null) {
                    parent.remove(uiComponent);
                    parent.revalidate();
                    parent.repaint();
                }
                browser.close(true);
                browser = null;
            }
            if (cefClient != null) {
                if (messageRouter != null) {
                    cefClient.removeMessageRouter(messageRouter);
                    messageRouter.dispose();
                    messageRouter = null;
                }
                cefClient.dispose();
                cefClient = null;
            }
        } catch (Exception ignored) {
            // Native browser shutdown can be noisy; app shutdown owns final process cleanup.
        } finally {
            htmlByUrl.clear();
        }
    }

    static String mathHeadAssets() {
        if (StringUtils.isBlank(KATEX_CSS) && StringUtils.isBlank(KATEX_SCRIPT)) {
            return "";
        }

        return """
                <style id="chat4j-katex-css">%s</style>
                <script id="chat4j-katex-script">%s</script>
                <script id="chat4j-mhchem-script">%s</script>
                <script id="chat4j-math-render-script">%s</script>
                """.formatted(
                KATEX_CSS,
                safeScriptContent(KATEX_SCRIPT),
                safeScriptContent(MHCHEM_SCRIPT),
                safeScriptContent(mathRenderScript())
        );
    }

    static String katexScript() {
        return KATEX_SCRIPT;
    }

    static String mhchemScript() {
        return MHCHEM_SCRIPT;
    }

    static String mathBridgeScript() {
        if (StringUtils.isBlank(KATEX_SCRIPT)) {
            return mathRenderScript();
        }

        return "%s\n%s\n%s".formatted(KATEX_SCRIPT, MHCHEM_SCRIPT, mathRenderScript());
    }

    private static String mathRenderScript() {
        return """
                (function() {
                    function closestCodeBlockShell(table) {
                        var parent = table ? table.parentNode : null;
                        return parent && parent.classList && parent.classList.contains('code-block-shell') ? parent : null;
                    }
                    function parseDelimitedMath(source, fallbackDisplayMode) {
                        var text = String(source || '').trim();
                        if (!text) {
                            return null;
                        }
                        if (text.length >= 4 && text.slice(0, 2) === '$$' && text.slice(-2) === '$$') {
                            return { tex: text.slice(2, -2).trim(), displayMode: true };
                        }
                        if (text.length >= 4 && text.slice(0, 2) === '\\\\[' && text.slice(-2) === '\\\\]') {
                            return { tex: text.slice(2, -2).trim(), displayMode: true };
                        }
                        if (text.length >= 4 && text.slice(0, 2) === '\\\\(' && text.slice(-2) === '\\\\)') {
                            return { tex: text.slice(2, -2).trim(), displayMode: false };
                        }
                        if (text.length >= 2 && text.charAt(0) === '$' && text.charAt(text.length - 1) === '$') {
                            return { tex: text.slice(1, -1).trim(), displayMode: false };
                        }
                        return { tex: text, displayMode: fallbackDisplayMode === true };
                    }
                    function renderOptions(displayMode) {
                        return {
                            displayMode: displayMode,
                            throwOnError: false,
                            trust: false,
                            strict: 'warn'
                        };
                    }
                    function renderIntoReplacement(sourceNode, parsed) {
                        if (!sourceNode || !sourceNode.parentNode || !parsed || !parsed.tex || typeof window.katex === 'undefined') {
                            return;
                        }
                        var target = document.createElement(parsed.displayMode ? 'div' : 'span');
                        target.className = parsed.displayMode ? 'chat4j-math-display' : 'chat4j-math-inline';
                        window.katex.render(parsed.tex, target, renderOptions(parsed.displayMode));
                        target.setAttribute('data-chat4j-math-rendered', 'true');
                        sourceNode.parentNode.replaceChild(target, sourceNode);
                    }
                    function renderInlineMath(root) {
                        Array.prototype.forEach.call(root.querySelectorAll('code.md-latex-inline:not([data-chat4j-math-rendered])'), function(code) {
                            try {
                                renderIntoReplacement(code, parseDelimitedMath(code.textContent, false));
                            } catch (error) {
                                code.setAttribute('data-chat4j-math-rendered', 'error');
                            }
                        });
                    }
                    function renderDisplayMath(root) {
                        Array.prototype.forEach.call(root.querySelectorAll('table.md-latex-block:not([data-chat4j-math-rendered])'), function(table) {
                            var pre = table.querySelector('tr.code-body pre') || table.querySelector('pre');
                            if (!pre) {
                                return;
                            }
                            try {
                                renderIntoReplacement(closestCodeBlockShell(table) || table, parseDelimitedMath(pre.textContent, true));
                            } catch (error) {
                                table.setAttribute('data-chat4j-math-rendered', 'error');
                            }
                        });
                    }
                    window.chat4jRenderMath = function(root) {
                        if (typeof window.katex === 'undefined') {
                            return;
                        }
                        var targetRoot = root || document;
                        renderDisplayMath(targetRoot);
                        renderInlineMath(targetRoot);
                    };
                })();
                """;
    }

    private static String inlineStylesheetFonts(String css) {
        if (StringUtils.isBlank(css)) {
            return "";
        }

        Matcher matcher = CSS_FONT_URL_PATTERN.matcher(css);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String fontPath = "/web/katex/%s".formatted(matcher.group(2));
            String dataUri = fontDataUri(fontPath);
            if (StringUtils.isBlank(dataUri)) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
            } else {
                matcher.appendReplacement(result, Matcher.quoteReplacement("url(%s)".formatted(dataUri)));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String fontDataUri(String path) {
        byte[] bytes = resourceBytes(path);
        if (bytes.length == 0) {
            return "";
        }

        String mediaType = Strings.CI.endsWith(path, ".woff2")
                ? "font/woff2"
                : Strings.CI.endsWith(path, ".woff") ? "font/woff" : "font/ttf";
        String encoded = Base64.getEncoder().encodeToString(bytes);
        return "data:%s;base64,%s".formatted(mediaType, encoded);
    }

    private static String safeScriptContent(String script) {
        return StringUtils.defaultString(script).replace("</script", "<\\/script");
    }

    private static String resourceText(String path) {
        byte[] bytes = resourceBytes(path);
        return bytes.length == 0 ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] resourceBytes(String path) {
        try (InputStream input = JcefTranscriptView.class.getResourceAsStream(path)) {
            return input == null ? new byte[0] : input.readAllBytes();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static String iconDataUri(String path) {
        try (InputStream input = JcefTranscriptView.class.getResourceAsStream(path)) {
            if (input == null) {
                return "";
            }
            String encoded = Base64.getEncoder().encodeToString(input.readAllBytes());
            return "data:image/svg+xml;base64,%s".formatted(encoded);
        } catch (Exception e) {
            return "";
        }
    }

    private static String escapeHtml(String text) {
        return StringUtils.defaultString(text)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String encodeSupplementaryCodePoints(String text) {
        String value = StringUtils.defaultString(text);
        StringBuilder encoded = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (Character.isSupplementaryCodePoint(codePoint)) {
                encoded.append("&#x").append(Integer.toHexString(codePoint)).append(";");
            } else {
                encoded.appendCodePoint(codePoint);
            }
        });
        return encoded.toString();
    }

    private static String escapeHtmlAttribute(String text) {
        return escapeHtml(text)
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String unwrapCallbackArg(String raw) {
        String value = StringUtils.defaultString(raw).trim();
        if (value.isEmpty()) {
            return "";
        }

        try {
            JsonNode node = OBJECT_MAPPER.readTree(value);
            if (node.has("args") && node.get("args").isArray() && !node.get("args").isEmpty()) {
                return node.get("args").get(0).asText("");
            }
            if (node.isArray() && !node.isEmpty()) {
                return node.get(0).asText("");
            }
            if (node.isTextual() || node.isNumber() || node.isBoolean()) {
                return node.asText("");
            }
        } catch (Exception ignored) {
            // Fall back to legacy raw string handling.
        }

        return StringUtils.unwrap(value, '"');
    }

    private TranscriptAction unwrapTranscriptAction(String raw) {
        String value = StringUtils.defaultString(raw).trim();
        if (value.isEmpty()) {
            return null;
        }

        try {
            JsonNode node = OBJECT_MAPPER.readTree(value);
            JsonNode args = node.has("args") && node.get("args").isArray() ? node.get("args") : node;
            if (args.isArray() && args.size() >= 2) {
                String text = args.size() >= 3 ? args.get(2).asText("") : "";
                return new TranscriptAction(args.get(0).asText(""), args.get(1).asInt(-1), text);
            }
        } catch (Exception ignored) {
            // Ignore malformed callback payloads.
        }

        return null;
    }

    private final class TranscriptRequestHandler extends CefRequestHandlerAdapter {
        @Override
        public boolean onBeforeBrowse(CefBrowser browser, CefFrame frame, CefRequest request, boolean userGesture, boolean isRedirect) {
            String url = request == null ? "" : request.getURL();
            if (!userGesture) {
                return false;
            }
            if (isInternalUrl(url)) {
                return true;
            }
            if (StringUtils.isNotBlank(url)) {
                ExternalLinkSupport.openExternalLink(url);
            }
            return true;
        }

        @Override
        public boolean onOpenURLFromTab(CefBrowser browser, CefFrame frame, String targetUrl, boolean userGesture) {
            if (userGesture && StringUtils.isNotBlank(targetUrl)) {
                ExternalLinkSupport.openExternalLink(targetUrl);
            }
            return true;
        }

        @Override
        public CefResourceRequestHandler getResourceRequestHandler(
                CefBrowser browser,
                CefFrame frame,
                CefRequest request,
                boolean isNavigation,
                boolean isDownload,
                String requestInitiator,
                BoolRef disableDefaultHandling
        ) {
            String html = request == null ? null : htmlForUrl(request.getURL());
            return html == null ? null : new DirectHtmlResourceRequestHandler(html);
        }
    }

    private static final class DirectHtmlResourceRequestHandler extends CefResourceRequestHandlerAdapter {
        private final String html;

        private DirectHtmlResourceRequestHandler(String html) {
            this.html = html;
        }

        @Override
        public DirectHtmlResourceHandler getResourceHandler(CefBrowser browser, CefFrame frame, CefRequest request) {
            return new DirectHtmlResourceHandler(html);
        }
    }

    private static final class DirectHtmlResourceHandler extends CefResourceHandlerAdapter {
        private final byte[] bytes;
        private int offset;

        private DirectHtmlResourceHandler(String html) {
            bytes = html.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public boolean open(CefRequest request, BoolRef handleRequest, org.cef.callback.CefCallback callback) {
            handleRequest.set(true);
            return true;
        }

        @Override
        public void getResponseHeaders(CefResponse response, IntRef responseLength, StringRef redirectUrl) {
            response.setStatus(200);
            response.setStatusText("OK");
            response.setMimeType("text/html");
            response.setHeaderByName("Content-Type", "text/html; charset=utf-8", true);
            response.setHeaderByName("Cache-Control", "no-store", true);
            response.setHeaderByName("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:; font-src data:;", true);
            responseLength.set(bytes.length);
        }

        @Override
        public boolean read(byte[] dataOut, int bytesToRead, IntRef bytesRead, CefResourceReadCallback callback) {
            int remaining = bytes.length - offset;
            if (remaining <= 0) {
                bytesRead.set(0);
                return false;
            }
            int count = Math.min(bytesToRead, remaining);
            System.arraycopy(bytes, offset, dataOut, 0, count);
            offset += count;
            bytesRead.set(count);
            return true;
        }
    }

    private record TranscriptAction(String action, int messageIndex, String text) {
        @Override
        public String toString() {
            return "TranscriptAction[action=%s, messageIndex=%d, text=<masked>]".formatted(action, messageIndex);
        }
    }

    private record DocumentUrl(String url, Path path) {
        private void deleteFile() {
            if (path == null) {
                return;
            }
            try {
                Files.deleteIfExists(path);
            } catch (Exception ignored) {
            }
        }
    }

    private record DocumentChrome(
            Color panelBackground,
            String background,
            String bubbleBackground,
            String borderColor,
            String menuBackground,
            String buttonBackground,
            String buttonBorder,
            String sourceChipBackground,
            String sourceChipBorder,
            String sourceChipText,
            String sourceChipHoverBackground,
            String jumpRing,
            String jumpRingTrack,
            String hoverBackground,
            String hoverForeground,
            String iconColorValue,
            String scrollbarTrack,
            String scrollbarThumb,
            String scrollbarHoverThumb,
            int baseBodyFontSize,
            int bodyFontSize,
            int codeFontSize,
            int tableFontSize,
            int languageFontSize,
            String syntaxHighlightCss
    ) {
        @Override
        public String toString() {
            return "DocumentChrome[panelBackground=%s, bodyFontSize=%d, codeFontSize=%d]"
                    .formatted(panelBackground, bodyFontSize, codeFontSize);
        }
    }

    private record TranscriptRenderSnapshot(
            List<Entry> entries,
            RenderMode renderMode,
            boolean dark,
            boolean jumpButtonVisible,
            Palette palette,
            DocumentChrome chrome,
            int codeFontSize,
            float fontScaleFactor
    ) {
        @Override
        public String toString() {
            return "TranscriptRenderSnapshot[entries=%d, renderMode=%s, dark=%s, jumpButtonVisible=%s, codeFontSize=%d, fontScaleFactor=%s]"
                    .formatted(entries.size(), renderMode, dark, jumpButtonVisible, codeFontSize, fontScaleFactor);
        }
    }

    @FunctionalInterface
    public interface TranscriptActionListener {
        void handle(String action, int messageIndex, String text);
    }

    public record TranscriptAttachment(String storagePath, String originalName, String mimeType, long sizeBytes, boolean image) {
        public TranscriptAttachment {
            storagePath = StringUtils.defaultString(storagePath);
            originalName = StringUtils.defaultString(originalName);
            mimeType = StringUtils.defaultString(mimeType);
        }

        @Override
        public String toString() {
            return "TranscriptAttachment[storagePath=<masked>, originalName=%s, mimeType=%s, sizeBytes=%d, image=%s]"
                    .formatted(originalName, mimeType, sizeBytes, image);
        }
    }

    public record Entry(
            EntryKind kind,
            Role role,
            String title,
            String text,
            boolean collapsed,
            int messageIndex,
            List<TranscriptAttachment> attachments
    ) {
        public Entry {
            title = StringUtils.defaultString(title);
            text = StringUtils.defaultString(text);
            attachments = attachments == null ? emptyList() : List.copyOf(attachments);
        }

        @Override
        public String toString() {
            return "Entry[kind=%s, role=%s, title=%s, text=<masked>, collapsed=%s, messageIndex=%d, attachments=%d]"
                    .formatted(kind, role, title, collapsed, messageIndex, attachments.size());
        }

        public static Entry message(Role role, String text, int messageIndex) {
            return message(role, text, messageIndex, emptyList());
        }

        public static Entry message(Role role, String text, int messageIndex, List<TranscriptAttachment> attachments) {
            return new Entry(EntryKind.MESSAGE, role, "", StringUtils.defaultString(text), false, messageIndex, attachments);
        }

        public static Entry activity(String title, String text, boolean collapsed) {
            return new Entry(
                    EntryKind.ACTIVITY,
                    Role.ASSISTANT,
                    StringUtils.defaultIfBlank(title, "Activity"),
                    StringUtils.defaultString(text),
                    collapsed,
                    -1,
                    emptyList()
            );
        }
    }

    public enum EntryKind {
        MESSAGE,
        ACTIVITY
    }
}
