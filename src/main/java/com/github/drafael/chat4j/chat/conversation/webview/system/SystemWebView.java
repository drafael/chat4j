package com.github.drafael.chat4j.chat.conversation.webview.system;

import com.github.drafael.chat4j.chat.conversation.ConversationActionListener;
import com.github.drafael.chat4j.chat.conversation.ConversationEntry;
import com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptDocumentRenderer;
import com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptDocumentRequest;
import com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptRenderSnapshot;
import com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptRenderSupport;
import com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptUpdateScripts;
import com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptAssetMode;
import com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptBrowserAssets;
import com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptCallbackPayloads;
import com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptCallbackPayloads.TranscriptAction;
import com.github.drafael.chat4j.chat.content.ExternalLinkSupport;
import ca.weblite.webview.swing.WebViewComponent;
import com.github.drafael.chat4j.chat.render.RenderMode;
import java.awt.event.HierarchyEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.*;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import static java.util.Collections.emptyList;

public final class SystemWebView {

    private final WebViewComponent webView;
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
    private Path currentDocumentPath;
    @Getter
    private boolean disposed;
    @Setter
    private ConversationActionListener actionListener;

    public SystemWebView() {
        webView = WebViewComponent.create();
        webView.addOnBeforeLoad(TranscriptBrowserAssets.transcriptActionsScript());
        webView.addHierarchyListener(event -> {
            long flags = event.getChangeFlags();
            if ((flags & (HierarchyEvent.DISPLAYABILITY_CHANGED | HierarchyEvent.SHOWING_CHANGED)) == 0) {
                return;
            }
            applyPendingDocumentUrl();
        });
        webView.addJavascriptCallback("chat4jOpenExternalLink", raw -> {
            String link = TranscriptCallbackPayloads.callbackArg(raw);
            if (StringUtils.isNotBlank(link)) {
                ExternalLinkSupport.openExternalLink(link);
            }
        });
        webView.addJavascriptCallback("chat4jTranscriptAction", raw -> {
            TranscriptAction action = TranscriptCallbackPayloads.transcriptAction(raw);
            if (actionListener != null && action != null) {
                actionListener.handle(action.action(), action.messageIndex(), action.text());
            }
        });
    }

    public JComponent component() {
        return webView;
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
            if (jumpButtonChanged || activeReadAloudChanged) {
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
            String document = TranscriptRenderSupport.withSnapshotFonts(snapshot, () -> renderDocument(scrollToBottom, snapshot));
            SwingUtilities.invokeLater(() -> {
                if (disposed || requestId != renderRequestCounter.get()) {
                    return;
                }
                applyDocumentUrl(requestId, toDocumentUrl(document), scrollToBottom);
            });
        });
    }

    public void scrollToBottom() {
        webView.eval(TranscriptUpdateScripts.scrollToBottom());
    }

    public void dispose() {
        disposed = true;
        renderRequestCounter.incrementAndGet();
        renderExecutor.shutdownNow();
        deletePendingDocumentUrl();
        replaceCurrentDocumentPath(null);
        webView.dispose();
    }

    private String renderDocument(boolean scrollToBottom, TranscriptRenderSnapshot snapshot) {
        return transcriptDocumentRenderer.renderDocument(new TranscriptDocumentRequest(
                scrollToBottom,
                snapshot,
                TranscriptAssetMode.INLINE_ALL,
                "",
                ""
        ));
    }

    private void updateJumpButtonChrome() {
        String script = TranscriptUpdateScripts.jumpButtonChrome(jumpButtonVisible);
        webView.eval(script);
    }

    private void applyDocumentUrl(long requestId, DocumentUrl documentUrl, boolean scrollToBottom) {
        if (disposed || requestId != renderRequestCounter.get()) {
            documentUrl.deleteFile();
            return;
        }
        if (!webView.isDisplayable()) {
            deletePendingDocumentUrl();
            pendingDocumentUrl = documentUrl;
            pendingDocumentRequestId = requestId;
            pendingDocumentScrollToBottom = scrollToBottom;
            return;
        }

        pendingDocumentUrl = null;
        pendingDocumentRequestId = 0L;
        pendingDocumentScrollToBottom = false;
        webView.setUrl(documentUrl.url());
        replaceCurrentDocumentPath(documentUrl.path());
        documentInitialized = true;
        documentLoadPending = false;
        schedulePostReloadTranscriptUpdate(requestId, scrollToBottom);
    }

    private void applyPendingDocumentUrl() {
        if (disposed || pendingDocumentUrl == null || !webView.isDisplayable()) {
            return;
        }
        applyDocumentUrl(pendingDocumentRequestId, pendingDocumentUrl, pendingDocumentScrollToBottom);
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
                entriesHtml,
                snapshot.jumpButtonVisible(),
                scrollToBottom
        );
        webView.eval(script);
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
        try {
            Path document = Files.createTempFile("chat4j-transcript-", ".html");
            Files.writeString(document, html, StandardCharsets.UTF_8);
            document.toFile().deleteOnExit();
            return new DocumentUrl(document.toUri().toString(), document);
        } catch (Exception e) {
            String encoded = Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8));
            return new DocumentUrl("data:text/html;charset=UTF-8;base64,%s".formatted(encoded), null);
        }
    }


    private record DocumentUrl(String url, Path path) {
        @Override
        public String toString() {
            return "DocumentUrl[url=<masked>, path=<masked>]";
        }

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
