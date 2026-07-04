package com.github.drafael.chat4j.chat.conversation.webview.jcef;

import com.github.drafael.chat4j.chat.conversation.ConversationActionListener;
import com.github.drafael.chat4j.chat.conversation.ConversationEntry;
import com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptAssetMode;
import com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptDocumentRenderer;
import com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptDocumentRequest;
import com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptRenderSnapshot;
import com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptRenderSupport;
import com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptUpdateScripts;
import com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptBrowserAssets;
import com.github.drafael.chat4j.chat.content.ExternalLinkSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.formdev.flatlaf.util.SystemInfo;
import com.github.drafael.chat4j.chat.render.RenderMode;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.*;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.callback.CefResourceReadCallback;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefLoadHandlerAdapter;
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
import static java.util.Collections.emptyList;

public final class JcefBrowserView {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String MERMAID_SCRIPT_URL = "https://chat4j.local/assets/mermaid/mermaid.min.js";
    private static final String SMILES_DRAWER_SCRIPT_URL = "https://chat4j.local/assets/smilesdrawer/smiles-drawer.min.js";

    private final JPanel browserPanel = new JPanel(new BorderLayout());
    private final Map<String, String> htmlByUrl = new ConcurrentHashMap<>();
    private CefClient cefClient;
    private CefMessageRouter messageRouter;
    private CefBrowser browser;
    private final TranscriptDocumentRenderer transcriptDocumentRenderer = new TranscriptDocumentRenderer();
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("chat4j-transcript-render-", 0).factory()
    );
    private final AtomicLong renderRequestCounter = new AtomicLong();
    private List<ConversationEntry> entries = emptyList();
    private RenderMode renderMode = RenderMode.PREVIEW;
    private boolean dark;
    private boolean jumpButtonVisible;
    private boolean readAloudAvailable;
    private int activeReadAloudMessageIndex = -1;
    private boolean documentInitialized;
    private boolean documentLoadPending;
    private DocumentUrl pendingDocumentUrl;
    private long pendingDocumentRequestId;
    private boolean pendingDocumentScrollToBottom;
    private long loadingDocumentRequestId;
    private boolean loadingDocumentScrollToBottom;
    private String loadingDocumentUrl = "";
    private String currentDocumentUrl = "";
    private Path currentDocumentPath;
    private boolean disposed;
    @Setter
    private ConversationActionListener actionListener;

    public JcefBrowserView() {
        browserPanel.setPreferredSize(new Dimension(800, 600));
        browserPanel.setMinimumSize(new Dimension(320, 220));
        browserPanel.add(new JLabel("JCEF transcript not loaded", SwingConstants.CENTER), BorderLayout.CENTER);
        installBrowserResizeWorkaround();
        browserPanel.addHierarchyListener(event -> applyPendingDocumentUrl());
    }

    public JComponent component() {
        return browserPanel;
    }

    public void setTranscript(
            List<ConversationEntry> entries,
            RenderMode renderMode,
            boolean dark,
            boolean scrollToBottom,
            boolean jumpButtonVisible
    ) {
        setTranscript(entries, renderMode, dark, scrollToBottom, jumpButtonVisible, false);
    }

    public void setTranscript(
            List<ConversationEntry> entries,
            RenderMode renderMode,
            boolean dark,
            boolean scrollToBottom,
            boolean jumpButtonVisible,
            boolean readAloudAvailable
    ) {
        setTranscript(entries, renderMode, dark, scrollToBottom, jumpButtonVisible, readAloudAvailable, -1);
    }

    public void setTranscript(
            List<ConversationEntry> entries,
            RenderMode renderMode,
            boolean dark,
            boolean scrollToBottom,
            boolean jumpButtonVisible,
            boolean readAloudAvailable,
            int activeReadAloudMessageIndex
    ) {
        RenderMode nextRenderMode = renderMode == null ? RenderMode.PREVIEW : renderMode;
        boolean styleChanged = this.renderMode != nextRenderMode || this.dark != dark;
        boolean jumpButtonChanged = this.jumpButtonVisible != jumpButtonVisible;
        boolean readAloudChanged = this.readAloudAvailable != readAloudAvailable;
        boolean activeReadAloudChanged = this.activeReadAloudMessageIndex != activeReadAloudMessageIndex;
        this.entries = List.copyOf(entries == null ? emptyList() : entries);
        this.renderMode = nextRenderMode;
        this.dark = dark;
        this.jumpButtonVisible = jumpButtonVisible;
        this.readAloudAvailable = readAloudAvailable;
        this.activeReadAloudMessageIndex = activeReadAloudMessageIndex;

        if (jumpButtonChanged) {
            updateJumpButtonChrome();
            SwingUtilities.invokeLater(this::updateJumpButtonChrome);
        }
        if (styleChanged || readAloudChanged) {
            reload(scrollToBottom);
            return;
        }
        if (documentLoadPending) {
            if (activeReadAloudChanged) {
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
            String document = injectJcefBridge(TranscriptRenderSupport.withSnapshotFonts(snapshot, () -> renderDocument(scrollToBottom, snapshot)));
            SwingUtilities.invokeLater(() -> {
                if (disposed || requestId != renderRequestCounter.get()) {
                    return;
                }
                applyDocumentUrl(requestId, toDocumentUrl(document), scrollToBottom);
            });
        });
    }

    public void scrollToBottom() {
        executeJavaScript(TranscriptUpdateScripts.scrollToBottom());
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
        return transcriptDocumentRenderer.renderDocument(new TranscriptDocumentRequest(
                scrollToBottom,
                snapshot,
                TranscriptAssetMode.INTERNAL_URL_FOR_LARGE_LIBRARIES,
                MERMAID_SCRIPT_URL,
                SMILES_DRAWER_SCRIPT_URL
        ));
    }

    private void updateJumpButtonChrome() {
        String script = TranscriptUpdateScripts.jumpButtonChrome(jumpButtonVisible);
        executeJavaScript(script);
    }

    private void applyDocumentUrl(long requestId, DocumentUrl documentUrl, boolean scrollToBottom) {
        if (disposed || requestId != renderRequestCounter.get()) {
            removeDocumentUrl(documentUrl);
            return;
        }
        if (!isBrowserPanelReadyForDocumentLoad()) {
            storePendingDocumentUrl(requestId, documentUrl, scrollToBottom);
            return;
        }

        pendingDocumentUrl = null;
        pendingDocumentRequestId = 0L;
        pendingDocumentScrollToBottom = false;
        documentInitialized = false;
        documentLoadPending = true;
        loadingDocumentRequestId = requestId;
        loadingDocumentScrollToBottom = scrollToBottom;
        loadingDocumentUrl = documentUrl.url();
        if (!loadUrl(documentUrl.url())) {
            clearLoadingDocumentState(requestId, documentUrl.url());
            removeDocumentUrl(documentUrl);
            return;
        }
        replaceCurrentDocumentUrl(documentUrl.url());
        replaceCurrentDocumentPath(documentUrl.path());
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
            removeDocumentUrl(pendingDocumentUrl);
        }
        pendingDocumentUrl = null;
        pendingDocumentRequestId = 0L;
        pendingDocumentScrollToBottom = false;
    }

    private void removeDocumentUrl(DocumentUrl documentUrl) {
        if (documentUrl == null) {
            return;
        }
        htmlByUrl.remove(documentUrl.url());
        documentUrl.deleteFile();
    }

    private void replaceCurrentDocumentUrl(String nextUrl) {
        String previousUrl = currentDocumentUrl;
        currentDocumentUrl = StringUtils.defaultString(nextUrl);
        if (StringUtils.isNotBlank(previousUrl) && !Strings.CS.equals(previousUrl, currentDocumentUrl)) {
            htmlByUrl.remove(previousUrl);
        }
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

    private void handleDocumentLoadEnd(String loadedUrl) {
        if (disposed || !isActiveLoadingDocument(loadedUrl)) {
            return;
        }
        long requestId = loadingDocumentRequestId;
        boolean scrollToBottom = loadingDocumentScrollToBottom;
        clearLoadingDocumentState(requestId, loadedUrl);
        documentInitialized = true;
        documentLoadPending = false;
        scheduleTranscriptHtmlUpdate(scrollToBottom, transcriptRenderSnapshot());
    }

    private void handleDocumentLoadError(String failedUrl) {
        if (disposed || !isActiveLoadingDocument(failedUrl)) {
            return;
        }
        clearLoadingDocumentState(loadingDocumentRequestId, failedUrl);
        documentInitialized = false;
        documentLoadPending = false;
    }

    private boolean isActiveLoadingDocument(String url) {
        return loadingDocumentRequestId == renderRequestCounter.get()
                && StringUtils.isNotBlank(loadingDocumentUrl)
                && Strings.CS.equals(loadingDocumentUrl, url);
    }

    private void clearLoadingDocumentState(long requestId, String url) {
        if (loadingDocumentRequestId != requestId || !Strings.CS.equals(loadingDocumentUrl, url)) {
            return;
        }
        loadingDocumentRequestId = 0L;
        loadingDocumentScrollToBottom = false;
        loadingDocumentUrl = "";
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
            String entriesHtml = TranscriptRenderSupport.withSnapshotFonts(snapshot, () -> renderEntriesHtml(snapshot, requestId));
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
        return TranscriptRenderSupport.snapshot(entries, renderMode, dark, jumpButtonVisible, readAloudAvailable, activeReadAloudMessageIndex);
    }


    private void updateTranscriptHtml(boolean scrollToBottom, TranscriptRenderSnapshot snapshot, String entriesHtml) {
        String script = TranscriptUpdateScripts.transcriptHtmlUpdate(
                encodeSupplementaryCodePoints(entriesHtml),
                snapshot.jumpButtonVisible(),
                scrollToBottom
        );
        executeJavaScript(script);
    }

    private String renderEntriesHtml(TranscriptRenderSnapshot snapshot) {
        return transcriptDocumentRenderer.renderEntriesHtml(snapshot);
    }

    private String renderEntriesHtml(TranscriptRenderSnapshot snapshot, long requestId) {
        return transcriptDocumentRenderer.renderEntriesHtml(
                snapshot,
                () -> !disposed && requestId == renderRequestCounter.get()
        );
    }


    private DocumentUrl toDocumentUrl(String html) {
        String url = "https://chat4j.local/transcript/%s.html".formatted(UUID.randomUUID());
        htmlByUrl.put(url, encodeSupplementaryCodePoints(html));
        return new DocumentUrl(url, null);
    }

    private String htmlForUrl(String url) {
        return htmlByUrl.get(url);
    }

    private static String scriptForUrl(String url) {
        if (Strings.CS.equals(url, MERMAID_SCRIPT_URL)) {
            return TranscriptBrowserAssets.mermaidScript();
        }
        if (Strings.CS.equals(url, SMILES_DRAWER_SCRIPT_URL)) {
            return TranscriptBrowserAssets.smilesDrawerScript();
        }
        return null;
    }

    static NavigationDecision navigationDecision(String url, boolean userGesture) {
        if (isInternalUrl(url)) {
            return userGesture ? NavigationDecision.BLOCK : NavigationDecision.ALLOW;
        }
        return userGesture && ExternalLinkSupport.isAllowedExternalLink(url)
                ? NavigationDecision.OPEN_EXTERNAL
                : NavigationDecision.BLOCK;
    }

    private static boolean isInternalUrl(String url) {
        try {
            URI uri = new URI(StringUtils.defaultString(url));
            return Strings.CI.equals(uri.getScheme(), "https") && Strings.CI.equals(uri.getHost(), "chat4j.local");
        } catch (Exception e) {
            return false;
        }
    }

    private String injectJcefBridge(String html) {
        return html.replace(
                "</head>",
                "<script>" + jcefCallbackScript() + TranscriptBrowserAssets.transcriptActionsScript() + "</script>\n</head>"
        );
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

    private boolean loadUrl(String url) {
        boolean createdBrowser = ensureBrowser(url);
        if (browser == null) {
            return false;
        }
        if (!createdBrowser) {
            browser.loadURL(url);
        }
        return true;
    }

    private boolean ensureBrowser(String initialUrl) {
        if (browser != null || disposed) {
            return false;
        }
        try {
            cefClient = JcefRuntime.getInstance().createClient();
            messageRouter = createMessageRouter();
            cefClient.addMessageRouter(messageRouter);
            cefClient.addRequestHandler(new TranscriptRequestHandler());
            cefClient.addLoadHandler(new TranscriptLoadHandler());
            browser = cefClient.createBrowser(initialUrl, false, false);
            Component browserComponent = browser.getUIComponent();
            browserComponent.setPreferredSize(new Dimension(800, 600));
            browserComponent.setMinimumSize(new Dimension(320, 220));
            browserPanel.removeAll();
            browserPanel.add(browserComponent, BorderLayout.CENTER);
            browserPanel.revalidate();
            browserPanel.repaint();
            repairBrowserLayout();
            return true;
        } catch (Throwable t) {
            browserPanel.removeAll();
            browserPanel.add(new JLabel("JCEF transcript failed to start", SwingConstants.CENTER), BorderLayout.CENTER);
            browserPanel.revalidate();
            browserPanel.repaint();
            return false;
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
            currentDocumentUrl = "";
            htmlByUrl.clear();
        }
    }


    enum NavigationDecision {
        ALLOW,
        BLOCK,
        OPEN_EXTERNAL
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


    private final class TranscriptLoadHandler extends CefLoadHandlerAdapter {
        @Override
        public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
            if (frame == null || !frame.isMain()) {
                return;
            }
            String loadedUrl = frame.getURL();
            SwingUtilities.invokeLater(() -> handleDocumentLoadEnd(loadedUrl));
        }

        @Override
        public void onLoadError(CefBrowser browser, CefFrame frame, CefLoadHandler.ErrorCode errorCode, String errorText, String failedUrl) {
            if (frame == null || !frame.isMain()) {
                return;
            }
            SwingUtilities.invokeLater(() -> handleDocumentLoadError(failedUrl));
        }
    }

    private final class TranscriptRequestHandler extends CefRequestHandlerAdapter {
        @Override
        public boolean onBeforeBrowse(CefBrowser browser, CefFrame frame, CefRequest request, boolean userGesture, boolean isRedirect) {
            String url = request == null ? "" : request.getURL();
            NavigationDecision decision = navigationDecision(url, userGesture);
            if (decision == NavigationDecision.OPEN_EXTERNAL) {
                ExternalLinkSupport.openExternalLink(url);
            }
            return decision != NavigationDecision.ALLOW;
        }

        @Override
        public boolean onOpenURLFromTab(CefBrowser browser, CefFrame frame, String targetUrl, boolean userGesture) {
            if (navigationDecision(targetUrl, userGesture) == NavigationDecision.OPEN_EXTERNAL) {
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
            String url = request == null ? "" : request.getURL();
            String html = htmlForUrl(url);
            if (html != null) {
                return new DirectResourceRequestHandler(html, "text/html", "text/html; charset=utf-8");
            }
            String script = scriptForUrl(url);
            return script == null
                    ? null
                    : new DirectResourceRequestHandler(script, "application/javascript", "application/javascript; charset=utf-8");
        }
    }

    private static final class DirectResourceRequestHandler extends CefResourceRequestHandlerAdapter {
        private final String content;
        private final String mimeType;
        private final String contentType;

        private DirectResourceRequestHandler(String content, String mimeType, String contentType) {
            this.content = content;
            this.mimeType = mimeType;
            this.contentType = contentType;
        }

        @Override
        public DirectResourceHandler getResourceHandler(CefBrowser browser, CefFrame frame, CefRequest request) {
            return new DirectResourceHandler(content, mimeType, contentType);
        }
    }

    private static final class DirectResourceHandler extends CefResourceHandlerAdapter {
        private final byte[] bytes;
        private final String mimeType;
        private final String contentType;
        private int offset;

        private DirectResourceHandler(String content, String mimeType, String contentType) {
            bytes = content.getBytes(StandardCharsets.UTF_8);
            this.mimeType = mimeType;
            this.contentType = contentType;
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
            response.setMimeType(mimeType);
            response.setHeaderByName("Content-Type", contentType, true);
            response.setHeaderByName("Cache-Control", "no-store", true);
            if (Strings.CS.equals(mimeType, "text/html")) {
                response.setHeaderByName("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'self' 'unsafe-inline'; img-src data:; font-src data:;", true);
            }
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


}
